package acup.gunfeel;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;

// Dev (-Pscenario=walksprint, ShootScenario's flat world): the G3 walking, aiming, sprinting and firing, for walkSway,
// sprintPose and sprintFireDelay (26.2's notes/walk-sprint stand). The keys are held as a player holds them (forward,
// sprint, NTM's fire key), the aim is set on the server's gun. The trace has WalkSprint's lines; run/client/aimprobe.tsv
// gets the frames (F: time, frame, yaw, pitch, sprinting, on ground, z), the fire key (P) and the bullets born on the
// server (S), in nanoseconds, as 26.2's probe writes them. The game quits at the end.
public final class WalkSprintScenario {
    private WalkSprintScenario() {
    }

    // ticks: stand, walk, stop, walk aimed, sprint and fire, sprint and stop, fire while walking, a short click sprinting
    private static final int GIVE = 40, WALK = 100, WALK_END = 200, AIM = 230, AIM_WALK = 240, AIM_WALK_END = 320, AIM_OFF = 330,
            RUN = 350, RUN_FIRE = 410, RUN_FIRE_END = 422, RUN_END = 470, RUN2 = 500, RUN2_END = 550, WALK3 = 580, WALK3_FIRE = 600,
            WALK3_FIRE_END = 602, WALK3_END = 640, RUN3 = 660, RUN3_FIRE = 720, RUN3_FIRE_END = 722, RUN3_END = 760, DONE = 800;

    private static boolean forward, sprint;
    private static int frames;
    private static final List<String> lines = new ArrayList<String>();

    static void register() {
        WalkSprintScenario s = new WalkSprintScenario();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(s);
    }

    private static void line(String l) {
        synchronized (lines) {
            lines.add(l);
        }
    }

    private static void fire(boolean down, Minecraft mc) {
        ShootScenario.trigger(down, mc);
        line("P\t" + System.nanoTime() + "\tkey.hbm.gunPrimary\t" + (down ? 1 : 0));
    }

    // the scenario's steps, at the end of each client tick (ShootScenario)
    static void step(Minecraft mc, int t) {
        if (t == 0) {
            // the trace's per-frame lines (WalkSprint) need the client side of the test log on
            TestLog.set(true, true);
            TestLog.set(false, true);
            Trace.recording = true;
            mc.player.rotationPitch = mc.player.prevRotationPitch = 0F;
            mc.player.rotationYaw = mc.player.prevRotationYaw = 0F;
        } else if (t == GIVE) {
            ShootScenario.onServer(() -> AimLagScenario.load(0, "gun_g3"));
            mc.player.inventory.currentItem = 0;
        } else if (t == WALK || t == AIM_WALK || t == WALK3) {
            forward = true;
        } else if (t == WALK_END || t == AIM_WALK_END || t == WALK3_END || t == RUN_END || t == RUN2_END || t == RUN3_END) {
            forward = sprint = false;
        } else if (t == AIM || t == AIM_OFF) {
            boolean on = t == AIM;
            ShootScenario.onServer(() -> aim(on));
        } else if (t == RUN || t == RUN2 || t == RUN3) {
            forward = sprint = true;
        } else if (t == RUN_FIRE || t == WALK3_FIRE || t == RUN3_FIRE) {
            fire(true, mc);
        } else if (t == RUN_FIRE_END || t == WALK3_FIRE_END || t == RUN3_FIRE_END) {
            fire(false, mc);
        } else if (t == DONE) {
            done();
            mc.shutdown();
        }
        // held as a player holds them, whatever the game did with the keys this tick
        hold(mc.gameSettings.keyBindForward, forward);
        hold(mc.gameSettings.keyBindSprint, sprint);
    }

    private static java.lang.reflect.Field pressed;

    // the binding's own state (setKeyBindState goes by the key code, and another mod's binding may have Ctrl too)
    private static void hold(KeyBinding key, boolean down) {
        try {
            if (pressed == null) pressed = ShootScenario.field(KeyBinding.class, "pressed", "field_74513_e");
            pressed.setBoolean(key, down);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void aim(boolean on) {
        EntityPlayerMP p = player();
        if (p == null) return;
        ItemStack stack = p.getHeldItemMainhand();
        if (stack.isEmpty() || !(stack.getItem() instanceof ItemGunBaseNT)) return;
        ItemGunBaseNT.setIsAiming(stack, on);
        p.inventoryContainer.detectAndSendChanges();
    }

    @SubscribeEvent
    public void frame(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) return;
        line(String.format(Locale.ROOT, "F\t%d\t%d\t%.3f\t%.3f\t%s\t%s\t%.3f\t%s\t%.2f\t%s", System.nanoTime(), frames++, mc.player.rotationYaw,
                mc.player.rotationPitch, mc.player.isSprinting(), mc.player.onGround, mc.player.posZ,
                mc.gameSettings.keyBindSprint.isKeyDown(), mc.player.movementInput == null ? 0F : mc.player.movementInput.moveForward,
                mc.player.isHandActive()));
    }

    @SubscribeEvent
    public void spawn(EntityJoinWorldEvent e) {
        if (e.getWorld().isRemote || !(e.getEntity() instanceof EntityBulletBaseMK4)) return;
        line("S\t" + System.nanoTime());
    }

    private static EntityPlayerMP player() {
        net.minecraft.server.MinecraftServer s = net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance();
        return s == null || s.getPlayerList().getPlayers().isEmpty() ? null
                : s.getPlayerList().getPlayers().get(0);
    }

    private static void done() {
        List<String> out = new ArrayList<String>();
        synchronized (lines) {
            out.addAll(lines);
        }
        try (PrintWriter w = new PrintWriter(new File("aimprobe.tsv"), StandardCharsets.UTF_8.name())) {
            for (String l : out) w.println(l);
        } catch (java.io.IOException ex) {
            GunFeel.LOGGER.warn("aimprobe.tsv: {}", ex.toString());
        }
        GunFeel.LOGGER.info("Scenario walksprint: {} frames, done", frames);
    }
}
