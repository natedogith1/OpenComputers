package li.cil.oc.common

import java.util.Calendar

import li.cil.oc._
import li.cil.oc.api.Network
import li.cil.oc.api.detail.ItemInfo
import li.cil.oc.api.machine.MachineHost
import li.cil.oc.client.renderer.PetRenderer
import li.cil.oc.common.asm.ClassTransformer
import li.cil.oc.common.item.data.MicrocontrollerData
import li.cil.oc.common.item.data.RobotData
import li.cil.oc.common.item.data.TabletData
import li.cil.oc.common.recipe.Recipes
import li.cil.oc.common.tileentity.Robot
import li.cil.oc.integration.Mods
import li.cil.oc.integration.util
import li.cil.oc.server.component.Keyboard
import li.cil.oc.server.machine.Callbacks
import li.cil.oc.server.machine.Machine
import li.cil.oc.server.machine.luac.LuaStateFactory
import li.cil.oc.server.{PacketSender => ServerPacketSender}
import li.cil.oc.util.ExtendedWorld._
import li.cil.oc.util._
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.server.MinecraftServer
import net.minecraft.tileentity.TileEntity
import net.minecraftforge.common.util.FakePlayer
import net.minecraftforge.event.entity.EntityJoinWorldEvent
import net.minecraftforge.event.world.BlockEvent
import net.minecraftforge.event.world.ChunkEvent
import net.minecraftforge.event.world.WorldEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.PlayerEvent._
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ServerTickEvent
import net.minecraftforge.fml.common.network.FMLNetworkEvent.ClientConnectedToServerEvent

import scala.collection.convert.WrapAsScala._
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object EventHandler {
  private val pending = mutable.Buffer.empty[() => Unit]

  private val runningRobots = mutable.Set.empty[Robot]

  private val keyboards = java.util.Collections.newSetFromMap[Keyboard](new java.util.WeakHashMap[Keyboard, java.lang.Boolean])

  private val machines = mutable.Set.empty[Machine]

  def onRobotStart(robot: Robot): Unit = runningRobots += robot

  def onRobotStopped(robot: Robot): Unit = runningRobots -= robot

  def addKeyboard(keyboard: Keyboard): Unit = keyboards += keyboard

  def scheduleClose(machine: Machine): Unit = machines += machine

  def unscheduleClose(machine: Machine): Unit = machines -= machine

  def schedule(tileEntity: TileEntity) {
    if (SideTracker.isServer) pending.synchronized {
      pending += (() => Network.joinOrCreateNetwork(tileEntity))
    }
  }

  def schedule(f: () => Unit) {
    pending.synchronized {
      pending += f
    }
  }

  def scheduleWirelessRedstone(rs: server.component.RedstoneWireless) {
    if (SideTracker.isServer) pending.synchronized {
      pending += (() => if (rs.node.network != null) {
        util.WirelessRedstone.addReceiver(rs)
        util.WirelessRedstone.updateOutput(rs)
      })
    }
  }

  @SubscribeEvent
  def onServerTick(e: ServerTickEvent) = if (e.phase == TickEvent.Phase.START) {
    pending.synchronized {
      val adds = pending.toArray
      pending.clear()
      adds
    } foreach (callback => {
      try callback() catch {
        case t: Throwable => OpenComputers.log.warn("Error in scheduled tick action.", t)
      }
    })

    val invalid = mutable.ArrayBuffer.empty[Robot]
    runningRobots.foreach(robot => {
      if (robot.isInvalid) invalid += robot
      else if (robot.world != null) robot.machine.update()
    })
    runningRobots --= invalid
  }
  else if (e.phase == TickEvent.Phase.END) {
    // Clean up machines *after* a tick, to allow stuff to be saved, first.
    val closed = mutable.ArrayBuffer.empty[Machine]
    machines.foreach(machine => if (machine.tryClose()) {
      closed += machine
      if (machine.host.world == null || !machine.host.world.blockExists(BlockPosition(machine.host))) {
        if (machine.node != null) machine.node.remove()
      }
    })
    machines --= closed
  }

  @SubscribeEvent
  def playerLoggedIn(e: PlayerLoggedInEvent) {
    if (SideTracker.isServer) e.player match {
      case player: EntityPlayerMP =>
        if (!LuaStateFactory.isAvailable) {
          player.addChatMessage(Localization.Chat.WarningLuaFallback)
        }
        if (!Settings.get.pureIgnorePower && Settings.get.ignorePower) {
          player.addChatMessage(Localization.Chat.WarningPower)
        }
        if (Recipes.hadErrors) {
          player.addChatMessage(Localization.Chat.WarningRecipes)
        }
        if (ClassTransformer.hadErrors) {
          player.addChatMessage(Localization.Chat.WarningClassTransformer)
        }
        if (ClassTransformer.hadSimpleComponentErrors) {
          player.addChatMessage(Localization.Chat.WarningSimpleComponent)
        }
        // Gaaah, MC 1.8 y u do this to me? Sending the packets here directly can lead to them
        // arriving on the client before it has a world and player instance, which causes all
        // sorts of trouble. It worked perfectly fine in MC 1.7.10... oSWDEG'PIl;dg'poinEG\a'pi=
        EventHandler.schedule(() => {
          ServerPacketSender.sendPetVisibility(None, Some(player))
          ServerPacketSender.sendLootDisks(player)
        })
        // Do update check in local games and for OPs.
        if (!Mods.VersionChecker.isAvailable && (!MinecraftServer.getServer.isDedicatedServer || MinecraftServer.getServer.getConfigurationManager.canSendCommands(player.getGameProfile))) {
          Future {
            UpdateCheck.info onSuccess {
              case Some(release) => player.addChatMessage(Localization.Chat.InfoNewVersion(release.tag_name))
            }
          }
        }
      case _ =>
    }
  }

  @SubscribeEvent
  def clientLoggedIn(e: ClientConnectedToServerEvent) {
    PetRenderer.isInitialized = false
    PetRenderer.hidden.clear()
    Loot.disksForClient.clear()

    client.Sound.startLoop(null, "computer_running", 0f, 0)
    schedule(() => client.Sound.stopLoop(null))
  }

  @SubscribeEvent
  def onBlockBreak(e: BlockEvent.BreakEvent): Unit = {
    e.world.getTileEntity(e.pos) match {
      case c: tileentity.Case =>
        if (c.isCreative && (!e.getPlayer.capabilities.isCreativeMode || !c.canInteract(e.getPlayer.getName))) {
          e.setCanceled(true)
        }
      case r: tileentity.RobotProxy =>
        val robot = r.robot
        if (robot.isCreative && (!e.getPlayer.capabilities.isCreativeMode || !robot.canInteract(e.getPlayer.getName))) {
          e.setCanceled(true)
        }
      case _ =>
    }
  }

  @SubscribeEvent
  def onPlayerRespawn(e: PlayerRespawnEvent) {
    keyboards.foreach(_.releasePressedKeys(e.player))
  }

  @SubscribeEvent
  def onPlayerChangedDimension(e: PlayerChangedDimensionEvent) {
    keyboards.foreach(_.releasePressedKeys(e.player))
  }

  @SubscribeEvent
  def onPlayerLogout(e: PlayerLoggedOutEvent) {
    keyboards.foreach(_.releasePressedKeys(e.player))
  }

  @SubscribeEvent
  def onEntityJoinWorld(e: EntityJoinWorldEvent): Unit = {
    if (Settings.get.giveManualToNewPlayers && !e.world.isRemote) e.entity match {
      case player: EntityPlayer if !player.isInstanceOf[FakePlayer] =>
        val nbt = player.getEntityData
        if (!nbt.hasKey(EntityPlayer.PERSISTED_NBT_TAG)) {
          nbt.setTag(EntityPlayer.PERSISTED_NBT_TAG, new NBTTagCompound())
        }
        val ocData = nbt.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG)
        if (!ocData.getBoolean(Settings.namespace + "receivedManual")) {
          ocData.setBoolean(Settings.namespace + "receivedManual", true)
          player.inventory.addItemStackToInventory(api.Items.get(Constants.ItemName.Manual).createItemStack(1))
        }
      case _ =>
    }
  }

  lazy val drone = api.Items.get(Constants.ItemName.Drone)
  lazy val eeprom = api.Items.get(Constants.ItemName.EEPROM)
  lazy val mcu = api.Items.get(Constants.BlockName.Microcontroller)
  lazy val navigationUpgrade = api.Items.get(Constants.ItemName.NavigationUpgrade)
  lazy val robot = api.Items.get(Constants.BlockName.Robot)
  lazy val tablet = api.Items.get(Constants.ItemName.Tablet)

  @SubscribeEvent
  def onCrafting(e: ItemCraftedEvent) = {
    var didRecraft = false

    didRecraft = recraft(e, navigationUpgrade, stack => {
      // Restore the map currently used in the upgrade.
      Option(api.Driver.driverFor(e.crafting)) match {
        case Some(driver) => Option(ItemStack.loadItemStackFromNBT(driver.dataTag(stack).getCompoundTag(Settings.namespace + "map")))
        case _ => None
      }
    }) || didRecraft

    didRecraft = recraft(e, mcu, stack => {
      // Restore EEPROM currently used in microcontroller.
      new MicrocontrollerData(stack).components.find(api.Items.get(_) == eeprom)
    }) || didRecraft

    didRecraft = recraft(e, drone, stack => {
      // Restore EEPROM currently used in drone.
      new MicrocontrollerData(stack).components.find(api.Items.get(_) == eeprom)
    }) || didRecraft

    didRecraft = recraft(e, robot, stack => {
      // Restore EEPROM currently used in robot.
      new RobotData(stack).components.find(api.Items.get(_) == eeprom)
    }) || didRecraft

    didRecraft = recraft(e, tablet, stack => {
      // Restore EEPROM currently used in tablet.
      new TabletData(stack).items.collect { case Some(item) => item }.find(api.Items.get(_) == eeprom)
    }) || didRecraft

    // Presents?
    if (!e.player.worldObj.isRemote) e.player match {
      case _: FakePlayer => // No presents for you, automaton. Such discrimination. Much bad conscience.
      case player: EntityPlayerMP =>
        // Presents!? If we didn't recraft, it's an OC item, and the time is right...
        if (Settings.get.presentChance > 0 && !didRecraft && api.Items.get(e.crafting) != null &&
          e.player.getRNG.nextFloat() < Settings.get.presentChance && timeForPresents) {
          // Presents!
          val present = api.Items.get(Constants.ItemName.Present).createItemStack(1)
          e.player.worldObj.playSoundAtEntity(e.player, "note.pling", 0.2f, 1f)
          InventoryUtils.addToPlayerInventory(present, e.player)
        }
      case _ => // Nope.
    }

    Achievement.onCraft(e.crafting, e.player)
  }

  private def timeForPresents = {
    val now = Calendar.getInstance()
    val month = now.get(Calendar.MONTH)
    val dayOfMonth = now.get(Calendar.DAY_OF_MONTH)
    // On the 12th day of Christmas, my robot brought to me~
    (month == Calendar.DECEMBER && dayOfMonth > 24) || (month == Calendar.JANUARY && dayOfMonth < 7) ||
      (month == Calendar.FEBRUARY && dayOfMonth == 14) ||
      (month == Calendar.APRIL && dayOfMonth == 22) ||
      (month == Calendar.MAY && dayOfMonth == 1) ||
      (month == Calendar.OCTOBER && dayOfMonth == 3) ||
      (month == Calendar.DECEMBER && dayOfMonth == 14)
  }

  def isItTime = {
    val now = Calendar.getInstance()
    val month = now.get(Calendar.MONTH)
    val dayOfMonth = now.get(Calendar.DAY_OF_MONTH)
    month == Calendar.APRIL && dayOfMonth == 1
  }

  private def recraft(e: ItemCraftedEvent, item: ItemInfo, callback: ItemStack => Option[ItemStack]): Boolean = {
    if (api.Items.get(e.crafting) == item) {
      for (slot <- 0 until e.craftMatrix.getSizeInventory) {
        val stack = e.craftMatrix.getStackInSlot(slot)
        if (api.Items.get(stack) == item) {
          callback(stack).foreach(extra =>
            InventoryUtils.addToPlayerInventory(extra, e.player))
        }
      }
      true
    }
    else false
  }

  @SubscribeEvent
  def onWorldUnload(e: WorldEvent.Unload) {
    if (!e.world.isRemote) {
      e.world.loadedTileEntityList.collect {
        case te: tileentity.traits.TileEntity => te.dispose()
      }
      e.world.loadedEntityList.collect {
        case host: MachineHost => host.machine.stop()
      }

      Callbacks.clear()
    }
  }

  @SubscribeEvent
  def onChunkUnload(e: ChunkEvent.Unload): Unit = {
    if (!e.world.isRemote) {
      e.getChunk.getEntityLists.foreach(_.collect {
        case host: MachineHost => host.machine match {
          case machine: Machine => scheduleClose(machine)
        }
      })
    }
  }
}
