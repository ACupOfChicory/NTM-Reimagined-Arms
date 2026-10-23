package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

// Camera recoil per shot along Curve.CAM_*: a snap up within one frame, a short hold,
// return by ~0.4 s, plus a damped roll shake. The amount comes from NTM's recoil numbers
// (lever shotgun: 10 up -> 5.5 deg with cameraScale 0.55). The camera always comes back.
public final class CameraRecoil {
    private record Shot(long start, float up, float left, float shake, float speed, Curve cPitch, Curve cYaw, Curve cRoll) {
        float length() {
            return Math.max(cPitch.length(), Math.max(cYaw.length(), cRoll.length()));
        }
    }

    private static final List<Shot> SHOTS = new ArrayList<>();
    private static float appliedPitch, appliedYaw;
    public static float roll;

    private CameraRecoil() {
    }

    // vertical/horizontal: what NTM's recoil lambdas asked for since the last tick
    public static void addImpulse(float vertical, float horizontal) {
        float strength = ModelRecoil.strength(vertical, horizontal);
        float speed = 1F / (0.45F + 0.55F * Mth.clamp(strength, 0F, 1F));
        if (SHOTS.size() >= 16) SHOTS.remove(0);
        SHOTS.add(new Shot(System.nanoTime(), vertical * Config.cameraScale, horizontal * Config.cameraScale,
                strength * Config.cameraShake * (Math.random() < 0.5 ? 1F : -1F), speed,
                Curve.of(Config.cameraPitch, Curve.CAM_PITCH), Curve.of(Config.cameraYaw, Curve.CAM_YAW),
                Curve.of(Config.cameraRoll, Curve.CAM_ROLL)));
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
            float t = (now - s.start) / 1e9F * s.speed / Config.cameraTime;
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
        if (SHOTS.isEmpty()) appliedPitch = 0F;
    }

    // Like Entity.turn: move both current and previous rotation so the change shows in this frame. Returns the pitch
    // change made.
    private static float turn(LocalPlayer player, float up, float left) {
        if (up == 0F && left == 0F) return 0F;
        float applied = pitchTurn(player.getXRot(), up);
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
