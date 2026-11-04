package acup.gunfeel;

import java.lang.reflect.Field;

import com.hbm.handler.HbmKeybinds;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.mags.IMagazine;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
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

    // "look": screenshots to look at (LookScenario)
    static boolean look() {
        return NAME.equals("look");
    }

    // "shapes": ModelCells' table from the drawn blocks (ShapesScenario)
    static boolean shapes() {
        return NAME.equals("shapes");
    }

    // "scan": machines taken in the game as the jar has them (ScanScenario)
    static boolean scan() {
        return NAME.equals("scan");
    }

    // "mobs": what the model hitboxes take from each living type (MobsScenario)
    static boolean mobs() {
        return NAME.equals("mobs");
    }

    // "holes": the holes on plain blocks, in and out and over edges (HolesScenario)
    static boolean holes() {
        return NAME.equals("holes");
    }

    // "fences": holes where two fences, walls, panes or bars join lie on their posts and rails (FencesScenario)
    static boolean fences() {
        return NAME.equals("fences");
    }

    // "aim": the right and middle buttons on a gun that aims (aimOnRightButton, AimButton)
    static boolean aim() {
        return NAME.equals("aim");
    }

    // "aimlag": how far a bullet is behind the turn the player saw (clickAim, AimLagScenario)
    static boolean aimlag() {
        return NAME.equals("aimlag");
    }

    // "hands": hideOffhand and smokeWidth (HandsScenario)
    static boolean hands() {
        return NAME.equals("hands");
    }

    // "walksprint": the G3 walking, aiming and sprinting (walkSway, sprintPose, sprintFireDelay; WalkSprintScenario)
    static boolean walksprint() {
        return NAME.equals("walksprint");
    }

    // a scenario that needs the flat world: this one, "look", "shapes", "scan" or a trace scenario (TraceScenario)
    static boolean world() {
        return on() || aim() || look() || shapes() || scan() || mobs() || holes() || fences() || aimlag() || hands() || walksprint() || TraceScenario.enabled();
    }

    // run on the integrated server's thread at its next tick
    static void onServer(Runnable r) {
        serverTask = r;
    }

    static void register() {
        if (!world()) return;
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new ShootScenario());
        if (aimlag()) AimLagScenario.register();
        if (walksprint()) WalkSprintScenario.register();
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
        if (mc.player == null || mc.world == null) return;
        // the look scenario's right click and inventory steps go on under the screen they open (and close it)
        if (mc.currentScreen != null && !(look() && LookScenario.underScreen(tick + 1))) return;
        if (shapes()) {
            ShapesScenario.step(mc, ++tick);
            return;
        }
        if (scan()) {
            ScanScenario.step(mc, ++tick);
            return;
        }
        if (mobs()) {
            MobsScenario.step(mc, ++tick);
            return;
        }
        if (holes()) {
            HolesScenario.step(mc, ++tick);
            return;
        }
        if (fences()) {
            FencesScenario.step(mc, ++tick);
            return;
        }
        if (aimlag()) {
            if (held) press(true);
            AimLagScenario.step(mc, ++tick);
            return;
        }
        if (hands()) {
            if (held) press(true);
            HandsScenario.step(mc, ++tick);
            return;
        }
        if (aim()) {
            aimStep(mc, ++tick);
            return;
        }
        if (walksprint()) {
            if (held) press(true);
            WalkSprintScenario.step(mc, ++tick);
            return;
        }
        if (look()) {
            if (held) press(true);
            if (++tick == 0) step(mc, 0);
            else LookScenario.step(mc, tick);
            return;
        }
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
        WorldSettings settings = new WorldSettings(Trace.DEFAULT_SEED, net.minecraft.world.GameType.CREATIVE, false, false, WorldType.FLAT);
        settings.enableCommands();
        log("start", mc);
        mc.launchIntegratedServer(WORLD, WORLD, settings);
    }

    private static void step(Minecraft mc, int t) {
        if (t == 0) {
            mc.player.rotationPitch = mc.player.prevRotationPitch = 0F;
            mc.player.rotationYaw = mc.player.prevRotationYaw = 0F;
            log("in_world", mc);
        } else if (t == GIVE) {
            onServer(() -> {
                give(0, "gun_maresleg");
                give(1, "gun_am180");
            });
            mc.player.inventory.currentItem = 0;
            log("give", mc);
        } else if (t >= FIRST_SHOT && t < FIRST_SHOT + SHOTS * SHOT_GAP && (t - FIRST_SHOT) % SHOT_GAP == 0) {
            trigger(true, mc);
        } else if (t >= FIRST_SHOT && t < FIRST_SHOT + SHOTS * SHOT_GAP && (t - FIRST_SHOT) % SHOT_GAP == 2) {
            trigger(false, mc);
        } else if (t == SWITCH) {
            mc.player.inventory.currentItem = 1;
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

    // the aim scenario: the gun aims on its third key (NTM CE: the middle button, -98); with aimOnRightButton the right
    // button (-99) aims and the middle one takes the second key. Each button is pressed and let go, then what the
    // server's gun got is written down: the keys it holds and whether it aims.
    private static final int AIM_RIGHT = 80, AIM_RIGHT_AGAIN = 140, AIM_MIDDLE = 200, AIM_DONE = 260;

    private static void aimStep(Minecraft mc, int t) {
        if (t == 0) step(mc, 0);
        else if (t == GIVE) {
            onServer(() -> give(0, "gun_maresleg"));
            mc.player.inventory.currentItem = 0;
            log("give", mc);
        } else if (t == AIM_RIGHT || t == AIM_RIGHT_AGAIN) button(mc, -99, true, "right_press");
        else if (t == AIM_MIDDLE) button(mc, -98, true, "middle_press");
        else if (t == AIM_RIGHT + 1 || t == AIM_RIGHT_AGAIN + 1 || t == AIM_MIDDLE + 1) aimState(mc, "held");
        else if (t == AIM_RIGHT + 3 || t == AIM_RIGHT_AGAIN + 3) button(mc, -99, false, "right_release");
        else if (t == AIM_MIDDLE + 3) button(mc, -98, false, "middle_release");
        else if (t == AIM_RIGHT + 30 || t == AIM_RIGHT_AGAIN + 30 || t == AIM_MIDDLE + 30) aimState(mc, "after");
        else if (t == AIM_DONE) {
            log("done", mc);
            GunFeel.LOGGER.info("Scenario {} done: {}", NAME, TestLog.file());
            mc.shutdown();
        }
    }

    // a mouse button as NTM CE's input handler takes it: the key bindings' state, then NTM's keys from it
    private static void button(Minecraft mc, int code, boolean down, String step) {
        net.minecraft.client.settings.KeyBinding.setKeyBindState(code, down);
        HbmKeybinds.handleProps(down, code);
        log(step, mc);
    }

    private static void aimState(Minecraft mc, String when) {
        ItemStack c = mc.player.getHeldItemMainhand();
        TestLog.c(-1, "aim", "when", when, "tick", tick, "aiming", ItemGunBaseNT.getIsAiming(c),
                "progress", ItemGunBaseNT.aimingProgress);
        int at = tick;
        onServer(() -> {
            EntityPlayerMP p = (EntityPlayerMP) net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance()
                    .getPlayerList().getPlayers().get(0);
            ItemStack st = p.getHeldItemMainhand();
            TestLog.s(-1, "aim", "when", when, "tick", at, "aiming", ItemGunBaseNT.getIsAiming(st),
                    "secondary", ItemGunBaseNT.getSecondary(st, 0), "tertiary", ItemGunBaseNT.getTertiary(st, 0));
        });
    }

    static void trigger(boolean down, Minecraft mc) {
        held = down;
        press(down);
        log(down ? "press" : "release", mc);
    }

    // NTM's own path for its gun keys: handleProps reads the trigger's state and sends it to the server and the gun.
    // port: NTM CE reads the trigger as the left mouse button itself (ClientProxy.getIsKeyPressed: Mouse.isButtonDown(0));
    // while the scenario holds it, MixinGunKeysRadial answers it held (triggerHeld)
    private static void press(boolean down) {
        HbmKeybinds.handleProps(down, -100);
    }

    // the scenario's trigger, for MixinGunKeysRadial
    public static boolean triggerHeld() {
        return held;
    }

    static Field field(Class<?> c, String... names) throws NoSuchFieldException {
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
    static void give(int slot, String name) {
        EntityPlayerMP player = (EntityPlayerMP) net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().getPlayers().get(0);
        Item item = (Item) Item.REGISTRY.getObject(new net.minecraft.util.ResourceLocation("hbm", name));
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
        ItemStack held = mc.player == null ? null : mc.player.getHeldItemMainhand();
        String gun = held == null ? null : GunFeelClient.gunId(held.getItem());
        GunFeel.LOGGER.info("Scenario {}: {} at tick {} ({})", NAME, step, tick, gun);
        TestLog.c(-1, "scenario", "step", step, "tick", tick, "gun", gun,
                "pitch", mc.player == null ? null : mc.player.rotationPitch, "yaw", mc.player == null ? null : mc.player.rotationYaw);
    }
}
