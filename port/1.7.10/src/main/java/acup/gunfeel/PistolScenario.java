package acup.gunfeel;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

// Dev (-Pscenario=pistols, ShootScenario's flat world): a revolver and a pistol held at a turned camera (yaw 0 and
// ±135°, pitch 0 and ±30°), run, jump and aim, for pistolHold, aimHold, walkSway and sprintPose. The game draws the
// hand from the eye, so the gun's place in the eye (the trace's walkSprintHand) must not change with the camera. Every
// tick the trace gets a pistolStand line (gun, phase, yaw, pitch); the game quits at the end.
// port: the 1.12.2 port's stand (de900ad) with 1.7.10's names (thePlayer, getHeldItem, the server's player list).
public final class PistolScenario {
    private PistolScenario() {
    }

    static final String[] GUNS = {"gun_light_revolver", "gun_lag"};

    // one gun's phases: name, ticks, yaw, pitch, forward, sprint, jump, aimed
    private static final Object[][] PHASES = {
            {"take", 80, 0F, 0F, false, false, false, false},
            {"rest", 30, 0F, 0F, false, false, false, false},
            {"yaw135", 30, 135F, 0F, false, false, false, false},
            {"yaw-135", 30, -135F, 0F, false, false, false, false},
            {"yaw135pitch30", 30, 135F, 30F, false, false, false, false},
            {"yaw135pitch-30", 30, 135F, -30F, false, false, false, false},
            {"yaw-135pitch30", 30, -135F, 30F, false, false, false, false},
            {"yaw-135pitch-30", 30, -135F, -30F, false, false, false, false},
            {"run135", 60, 135F, 0F, true, true, false, false},
            {"runStop", 30, 135F, 0F, false, false, false, false},
            {"jump-135", 50, -135F, 30F, false, false, true, false},
            {"jumpStop", 30, -135F, 30F, false, false, false, false},
            {"aim135pitch-30", 40, 135F, -30F, false, false, false, true},
            {"aim-135pitch30", 40, -135F, 30F, false, false, false, true},
            {"aim0", 40, 0F, 0F, false, false, false, true},
            {"aimOff", 40, 0F, 0F, false, false, false, false},
    };

    private static int gun, phase, at;
    private static boolean forward, sprint, jump;

    static int length() {
        int n = 0;
        for (Object[] p : PHASES) n += (Integer) p[1];
        return n * GUNS.length;
    }

    // the scenario's steps, at the end of each client tick (ShootScenario)
    static void step(Minecraft mc, int t) {
        if (t == 0) {
            TestLog.set(true, true);
            TestLog.set(false, true);
            Trace.recording = true;
            return;
        }
        if (gun >= GUNS.length) return;
        Object[] p = PHASES[phase];
        if (at == 0) {
            // one task for the server's next tick (onServer keeps the last one)
            final String name = phase == 0 ? GUNS[gun] : null;
            final boolean aimed = (Boolean) p[7];
            ShootScenario.onServer(() -> {
                if (name != null) AimLagScenario.load(0, name);
                aim(aimed);
            });
            if (name != null) mc.thePlayer.inventory.currentItem = 0;
            forward = (Boolean) p[4];
            sprint = (Boolean) p[5];
            jump = (Boolean) p[6];
        }
        // the camera is set every tick: a run or a jump does not turn it, but the stand keeps it exact
        float yaw = (Float) p[2], pitch = (Float) p[3];
        mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = yaw;
        mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = pitch;
        TestLog.c(-1, "pistolStand", "gun", GUNS[gun], "phase", p[0], "yaw", yaw, "pitch", pitch, "sprinting", mc.thePlayer.isSprinting(),
                "onGround", mc.thePlayer.onGround);
        hold(mc.gameSettings.keyBindForward, forward);
        hold(mc.gameSettings.keyBindSprint, sprint);
        hold(mc.gameSettings.keyBindJump, jump);
        if (++at >= (Integer) p[1]) {
            at = 0;
            if (++phase >= PHASES.length) {
                phase = 0;
                if (++gun >= GUNS.length) {
                    hold(mc.gameSettings.keyBindForward, false);
                    hold(mc.gameSettings.keyBindSprint, false);
                    hold(mc.gameSettings.keyBindJump, false);
                    GunFeel.LOGGER.info("Scenario pistols: done");
                    mc.shutdown();
                }
            }
        }
    }

    private static java.lang.reflect.Field pressed;

    private static void hold(KeyBinding key, boolean down) {
        try {
            if (pressed == null) pressed = ShootScenario.field(KeyBinding.class, "pressed", "field_74513_e");
            pressed.setBoolean(key, down);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void aim(boolean on) {
        net.minecraft.server.MinecraftServer s = net.minecraft.server.MinecraftServer.getServer();
        if (s == null || s.getConfigurationManager().playerEntityList.isEmpty()) return;
        EntityPlayerMP p = (EntityPlayerMP) s.getConfigurationManager().playerEntityList.get(0);
        ItemStack stack = p.getHeldItem();
        if (stack == null || !(stack.getItem() instanceof ItemGunBaseNT)) return;
        ItemGunBaseNT.setIsAiming(stack, on);
        p.inventoryContainer.detectAndSendChanges();
    }
}
