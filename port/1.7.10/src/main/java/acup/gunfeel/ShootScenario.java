package acup.gunfeel;

import java.lang.reflect.Field;

import com.hbm.handler.HbmKeybinds;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.mags.IMagazine;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

// A dev scenario: -Dntmgunfeel.scenario=shoot (gradle runClient -Pscenario=shoot) makes a new flat creative world from
// the main menu, hands out a loaded lever shotgun (gun_maresleg: cameraClimb) and an automatic (gun_am180:
// cameraClimbDeg, cameraDriftDeg, cameraClimbRamp), fires three single shots and one burst through NTM's own key path
// (HbmKeybinds.gunPrimaryKey, handleProps), writes each step into the test log and quits. With -Dntmgunfeel.trace the
// log is a trace (recoil, camera, kick with the gun's length and grip, sway) for tools/trace_diff.py.
// Without the property it does nothing. The world (and the server's thread) also serves TraceScenario: with another
// scenario name it starts that one in the world instead.
public final class ShootScenario {
    static final String NAME = System.getProperty("ntmgunfeel.scenario", "").trim();
    static final String WORLD = "ntmgunfeel-scenario";

    // the steps, in client ticks since the player stands in the world with no screen open
    private static final int GIVE = 40, FIRST_SHOT = 80, SHOT_GAP = 40, SHOTS = 3, SWITCH = 220, BURST = 260,
            BURST_TICKS = 40, DONE = 400;

    private static boolean started;
    private static int tick = -1;
    private static boolean held;
    private static volatile Runnable serverTask;
    private static Field pressed;

    private ShootScenario() {
    }

    static boolean on() {
        return NAME.equals("shoot");
    }

    // a scenario that needs the flat world: this one or a trace scenario (TraceScenario)
    static boolean world() {
        return on() || TraceScenario.enabled();
    }

    // run on the integrated server's thread at its next tick
    static void onServer(Runnable r) {
        serverTask = r;
    }

    static void register() {
        if (!world()) return;
        FMLCommonHandler.instance().bus().register(new ShootScenario());
        GunFeel.LOGGER.info("Scenario {}: world {}, quits at tick {}", NAME, WORLD, DONE);
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!started) {
            if (mc.currentScreen instanceof GuiMainMenu) start(mc);
            return;
        }
        if (mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) return;
        if (!on()) {
            // a trace scenario starts a second after the player stands in the world (TraceScenario)
            if (++tick == 20) {
                log("trace_scenario", mc);
                TraceScenario.start();
            }
            return;
        }
        // a held trigger stays held, whatever else the game did with the key this tick
        if (held) press(true);
        step(mc, ++tick);
    }

    // the integrated server's thread: what has to happen there (the guns, loaded)
    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Runnable r = serverTask;
        if (r == null) return;
        serverTask = null;
        r.run();
    }

    private static void start(Minecraft mc) {
        started = true;
        mc.gameSettings.pauseOnLostFocus = false;
        mc.getSaveLoader().flushCache();
        mc.getSaveLoader().deleteWorldDirectory(WORLD);
        WorldSettings settings = new WorldSettings(Trace.DEFAULT_SEED, WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT);
        settings.enableCommands();
        log("start", mc);
        mc.launchIntegratedServer(WORLD, WORLD, settings);
    }

    private static void step(Minecraft mc, int t) {
        if (t == 0) {
            mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = 0F;
            mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = 0F;
            log("in_world", mc);
        } else if (t == GIVE) {
            onServer(() -> {
                give(0, "gun_maresleg");
                give(1, "gun_am180");
            });
            mc.thePlayer.inventory.currentItem = 0;
            log("give", mc);
        } else if (t >= FIRST_SHOT && t < FIRST_SHOT + SHOTS * SHOT_GAP && (t - FIRST_SHOT) % SHOT_GAP == 0) {
            trigger(true, mc);
        } else if (t >= FIRST_SHOT && t < FIRST_SHOT + SHOTS * SHOT_GAP && (t - FIRST_SHOT) % SHOT_GAP == 2) {
            trigger(false, mc);
        } else if (t == SWITCH) {
            mc.thePlayer.inventory.currentItem = 1;
            log("switch", mc);
        } else if (t == BURST) {
            trigger(true, mc);
        } else if (t == BURST + BURST_TICKS) {
            trigger(false, mc);
        } else if (t == DONE) {
            log("done", mc);
            GunFeel.LOGGER.info("Scenario {} done: {}", NAME, TestLog.file());
            mc.shutdown();
        }
    }

    private static void trigger(boolean down, Minecraft mc) {
        held = down;
        press(down);
        log(down ? "press" : "release", mc);
    }

    // NTM's own path for its gun keys: the key's state, then handleProps sends it to the server and the gun
    private static void press(boolean down) {
        KeyBinding key = HbmKeybinds.gunPrimaryKey;
        try {
            if (pressed == null) pressed = field(KeyBinding.class, "pressed", "field_74513_e");
            if (pressed.getBoolean(key) == down) return;
            pressed.setBoolean(key, down);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("KeyBinding.pressed", ex);
        }
        HbmKeybinds.handleProps(down, key.getKeyCode());
    }

    private static Field field(Class<?> c, String... names) throws NoSuchFieldException {
        for (String n : names) {
            try {
                Field f = c.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // the other name: deobfuscated in dev, SRG in the game
            }
        }
        throw new NoSuchFieldException(String.join("/", names));
    }

    // a gun in the slot with every magazine full of its first accepted ammo
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void give(int slot, String name) {
        EntityPlayerMP player = (EntityPlayerMP) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
        Item item = (Item) Item.itemRegistry.getObject("hbm:item." + name);
        if (!(item instanceof ItemGunBaseNT)) throw new IllegalStateException("no NTM gun " + name);
        ItemGunBaseNT gun = (ItemGunBaseNT) item;
        ItemStack stack = new ItemStack(item);
        int rounds = 0;
        for (int c = 0; c < gun.getConfigCount(); c++) {
            for (Receiver r : gun.getConfig(stack, c).getReceivers(stack)) {
                IMagazine mag = r.getMagazine(stack);
                mag.setType(stack, mag.getType(stack, null));
                mag.setAmount(stack, mag.getCapacity(stack));
                rounds += mag.getCapacity(stack);
            }
        }
        player.inventory.setInventorySlotContents(slot, stack);
        player.inventoryContainer.detectAndSendChanges();
        TestLog.s(-1, "scenario", "step", "loaded", "gun", "hbm:" + name, "slot", slot, "rounds", rounds);
    }

    private static void log(String step, Minecraft mc) {
        ItemStack held = mc.thePlayer == null ? null : mc.thePlayer.getHeldItem();
        String gun = held == null ? null : GunFeelClient.gunId(held.getItem());
        GunFeel.LOGGER.info("Scenario {}: {} at tick {} ({})", NAME, step, tick, gun);
        TestLog.c(-1, "scenario", "step", step, "tick", tick, "gun", gun,
                "pitch", mc.thePlayer == null ? null : mc.thePlayer.rotationPitch, "yaw", mc.thePlayer == null ? null : mc.thePlayer.rotationYaw);
    }
}
