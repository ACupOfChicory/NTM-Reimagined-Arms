package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

// Camera recoil per shot along Curve.CAM_*: a snap up within one frame, a short hold,
// return by ~0.4 s, plus a damped roll shake. The amount comes from NTM's recoil numbers
// (lever shotgun: 10 up -> 5.5 deg with cameraScale 0.55). The camera always comes back. A server can set a minimum
// the player can't go below (cameraScaleMin); the shake (roll) stays the player's, as do the gun model and the arms.
public final class CameraRecoil {
    private record Shot(long start, float up, float left, float shake, float speed, Curve cPitch, Curve cYaw, Curve cRoll, float time) {
        float length() {
            return Math.max(cPitch.length(), Math.max(cYaw.length(), cRoll.length()));
        }
    }

    private static final List<Shot> SHOTS = new ArrayList<>();
    private static float appliedPitch, appliedYaw;
    // the test log: where the view was before the first kick of a burst, and whether the ±90° stop cut a kick short
    private static float burstPitch;
    private static boolean burstStopped, burst;
    public static float roll;

    private CameraRecoil() {
    }

    // vertical/horizontal: what NTM's recoil lambdas asked for since the last tick
    public static void addImpulse(float vertical, float horizontal) {
        float strength = ModelRecoil.strength(vertical, horizontal);
        float speed = 1F / (0.45F + 0.55F * Mth.clamp(strength, 0F, 1F));
        if (SHOTS.size() >= 16) SHOTS.remove(0);
        // the server's minimum (cameraScaleMin in ntm-gunfeel-server.properties): with one set, the player's scale goes
        // no lower, and the up and sideways curves and their time are the mod's, so no curve of the player's flattens it
        float min = ReaimTimes.number("cameraScaleMin", 0F);
        boolean held = min > 0F;
        float scale = Math.max(Config.cameraScale, min);
        LocalPlayer pl = Minecraft.getInstance().player;
        if (pl != null && !burst) {
            burst = true;
            burstPitch = pl.getXRot();
            burstStopped = false;
        }
        if (TestLog.client) TestLog.c(-1, "recoil", "phase", "kick", "scale", scale, "cameraScale", Config.cameraScale, "min", min, "held", held,
                "time", held ? Math.max(1F, Config.cameraTime) : Config.cameraTime, "ownCurves", !held, "up", vertical * scale, "pitch", pl == null ? null : pl.getXRot());
        SHOTS.add(new Shot(System.nanoTime(), vertical * scale, horizontal * scale,
                strength * Config.cameraShake * (Math.random() < 0.5 ? 1F : -1F), speed,
                held ? Curve.CAM_PITCH : Curve.of(Config.cameraPitch, Curve.CAM_PITCH),
                held ? Curve.CAM_YAW : Curve.of(Config.cameraYaw, Curve.CAM_YAW),
                Curve.of(Config.cameraRoll, Curve.CAM_ROLL), held ? Math.max(1F, Config.cameraTime) : Config.cameraTime));
    }

    public static void reset() {
        SHOTS.clear();
        appliedPitch = appliedYaw = roll = 0F;
    }

    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            reset();
            return;
        }
        if (mc.isPaused()) return;
        long now = System.nanoTime();
        float pitch = 0F, yaw = 0F, r = 0F;
        for (int i = SHOTS.size() - 1; i >= 0; i--) {
            Shot s = SHOTS.get(i);
            float t = (now - s.start) / 1e9F * s.speed / s.time;
            if (t >= s.length()) {
                SHOTS.remove(i);
                continue;
            }
            pitch += s.cPitch.at(t) * s.up;
            yaw += s.cYaw.at(t) * s.left;
            r += s.cRoll.at(t) * s.shake;
        }
        if (SHOTS.isEmpty()) pitch = yaw = r = 0F;
        roll = r;
        // only what the ±90° stop let through counts as applied: else the return would take back a kick that never
        // happened and the view would end below where the player aimed
        appliedPitch += turn(player, pitch - appliedPitch, yaw - appliedYaw);
        appliedYaw = yaw;
        if (SHOTS.isEmpty()) {
            appliedPitch = 0F;
            if (burst) {
                burst = false;
                // the view should be back where it was (the player's own mouse moves count here too)
                if (TestLog.client) TestLog.c(-1, "recoil", "phase", "back", "from", burstPitch, "to", player.getXRot(), "stopped", burstStopped);
            }
        }
    }

    // Like Entity.turn: move both current and previous rotation so the change shows in this frame. Returns the pitch
    // change made.
    private static float turn(LocalPlayer player, float up, float left) {
        if (up == 0F && left == 0F) return 0F;
        float applied = pitchTurn(player.getXRot(), up);
        if (Math.abs(applied - up) > 1e-4F) burstStopped = true;
        player.setXRot(player.getXRot() - applied);
        player.xRotO -= applied;
        player.setYRot(player.getYRot() - left);
        player.yRotO -= left;
        return applied;
    }

    // how much of a turn up by `up` degrees fits from pitch xRot before the ±90° stop
    static float pitchTurn(float xRot, float up) {
        return xRot - Mth.clamp(xRot - up, -90F, 90F);
    }
}
