package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

// Camera recoil per shot along the gun's recoil preset (RecoilPresets: cameraPitch, cameraYaw, cameraRoll): a snap up,
// a return, a damped roll shake. The amount comes from NTM's recoil numbers (lever shotgun: 10 up -> 5.5 deg with
// cameraScale 0.55). The camera comes back, all but the gun's cameraClimb share of the up movement, its
// cameraClimbDeg degrees up and its cameraDriftDeg sideways: that part moves with the kick and stays, so a burst walks
// the aim up and wanders it sideways; cameraClimbRamp lets it grow over a burst's first shots. A server can set a
// minimum the player can't go below
// (cameraScaleMin): the up and sideways movements are then the jar's preset of the gun, at least as strong and long
// as it ships; the shake (roll) stays the player's, as do the gun model and the arms.
public final class CameraRecoil {
    private record Shot(long start, float up, float left, float shake, float speed, Recoil cPitch, Recoil cYaw, Recoil cRoll, float time,
            float climb, float deg, float side, float[] reach) {
        float length() {
            return Math.max(cPitch.length(), Math.max(cYaw.length(), cRoll.length()));
        }

        float pitchAt(float t) {
            return pitchOf(cPitch, reach, up, climb, deg, t);
        }

        float sideAt(float t) {
            return side == 0F ? 0F : side * rise(reach, t);
        }

        // degrees that stay once the shot is over
        float keep() {
            return up * climb * reach[reach.length - 1] + deg;
        }
    }

    private static final float[] NO_REACH = {0F};
    // reach(t), in steps of this much curve time
    static final float REACH_STEP = 0.001F;

    // the furthest the curve has gone by each step (signed): it follows the rise and holds the peak from there on
    static float[] reachTable(Recoil c) {
        int n = Math.min(4096, (int) Math.ceil(c.length() / REACH_STEP) + 1);
        float[] out = new float[n];
        float far = 0F;
        for (int i = 0; i < n; i++) {
            float v = c.at(i * REACH_STEP);
            if (Math.abs(v) > Math.abs(far)) far = v;
            out[i] = far;
        }
        return out;
    }

    static float reach(float[] table, float t) {
        float x = Math.max(0F, t) / REACH_STEP;
        int i = (int) x;
        if (i >= table.length - 1) return table[table.length - 1];
        return Mth.lerp(x - i, table[i], table[i + 1]);
    }

    // the up movement of one shot at curve time t: the climb share follows the rise and stays at the peak, the rest
    // moves as the curve and comes back; deg degrees up rise the same way and stay too. climb and deg 0 = the curve
    // itself.
    static float pitchOf(Recoil c, float[] reach, float up, float climb, float deg, float t) {
        if (climb == 0F && deg == 0F) return c.at(t) * up;
        float p = ((1F - climb) * c.at(t) + climb * reach(reach, t)) * up;
        return deg == 0F ? p : p + deg * rise(reach, t);
    }

    // the rise of the curve from 0 to 1 at curve time t, whatever its own height and sign (a flat curve: 1 at once)
    static float rise(float[] reach, float t) {
        float end = reach[reach.length - 1];
        return end == 0F ? 1F : reach(reach, t) / end;
    }

    // share of the staying parts for the shot-th shot of a burst (from 1): grows evenly to all of it at shot `shots`;
    // shots 0 or 1 = all of it from the first shot
    static float ramp(int shot, float shots) {
        return shots <= 1F ? 1F : Math.min(1F, shot / shots);
    }

    // the side the aim wanders to: a few shots one way, then it may turn; g is a fresh normal random number. Its
    // spread stays about 1 however long the burst.
    static float nextDrift(float prev, boolean first, float g) {
        float d = first ? g : 0.8F * prev + 0.6F * g;
        return Math.clamp(d, -2.5F, 2.5F);
    }

    private static final List<Shot> SHOTS = new ArrayList<>();
    private static float appliedPitch, appliedYaw;
    // degrees that stay from the burst's finished shots (cameraClimb), up and sideways
    private static float kept, keptSide;
    // the burst's shots so far (cameraClimbRamp) and where its sideways wander is heading (cameraDriftDeg)
    private static int burstShots;
    private static float drift;
    // the test log: where the view was before the first kick of a burst, and whether the ±90° stop cut a kick short
    private static float burstPitch;
    private static boolean burstStopped, burst;
    public static float roll;
    // each gun's last real kick (NTM's vertical, horizontal), for the editor's test shot
    public static final java.util.Map<net.minecraft.world.item.Item, float[]> LAST = new java.util.IdentityHashMap<>();

    private CameraRecoil() {
    }

    // vertical/horizontal: what NTM's recoil lambdas asked for since the last tick
    public static void addImpulse(float vertical, float horizontal) {
        LocalPlayer holder = Minecraft.getInstance().player;
        if (holder != null && !GunEditor.replaying) LAST.put(holder.getMainHandItem().getItem(), new float[] {vertical, horizontal});
        if (SHOTS.size() >= 16) {
            Shot old = SHOTS.remove(0);
            kept += old.keep();
            keptSide += old.side;
        }
        // the server's minimum (cameraScaleMin in ntm-gunfeel-server.properties): with one set, the player's scale goes
        // no lower, and the up and sideways curves and their time are the mod's, so no curve of the player's flattens it
        float min = ReaimTimes.number("cameraScaleMin", 0F);
        boolean held = min > 0F;
        boolean aiming = holder != null && com.hbm.items.weapon.sedna.ItemGunBaseNT.getIsAiming(holder.getMainHandItem());
        float ads = aiming ? Config.adsRecoil : 1F;
        float scale = Math.max(Config.cameraScale * ads, min);
        Recoil cPitch = held ? shipped("cameraPitch") : RecoilPresets.channel("cameraPitch");
        LocalPlayer pl = Minecraft.getInstance().player;
        boolean first = !burst;
        if (pl != null && !burst) {
            burst = true;
            burstPitch = pl.getXRot();
            burstStopped = false;
        }
        burstShots = first ? 1 : burstShots + 1;
        float ramp = ramp(burstShots, Config.cameraClimbRamp);
        float climb = Config.cameraClimb * ramp;
        // degrees up, always: for guns whose NTM recoil is noise around 0 (G3, M2, miniguns), where a share of the kick
        // would wander up and down; lighter while aiming, like the kick
        float deg = Config.cameraClimbDeg * ads * ramp;
        float side = 0F;
        if (Config.cameraDriftDeg > 0F) {
            drift = nextDrift(drift, first, (float) Rng.camera().nextGaussian());
            side = Config.cameraDriftDeg * ads * ramp * drift;
        }
        float[] reach = climb > 0F || deg > 0F || side != 0F ? reachTable(cPitch) : NO_REACH;
        if (TestLog.client) TestLog.c(-1, "recoil", "phase", "kick", "scale", scale, "cameraScale", Config.cameraScale, "min", min, "held", held,
                "time", held ? Math.max(1F, Config.cameraTime) : Config.cameraTime, "ownCurves", !held, "preset", RecoilPresets.presetOf("cameraPitch"), "up", vertical * scale, "pitch", pl == null ? null : pl.getXRot(),
                "cameraClimb", climb, "cameraClimbDeg", deg, "ramp", ramp, "shot", burstShots, "keep", vertical * scale * climb * reach[reach.length - 1] + deg,
                "side", side);
        SHOTS.add(new Shot(GunClock.nanos(), vertical * scale, horizontal * scale,
                Config.cameraShake * ads * (Rng.camera().nextDouble() < 0.5 ? 1F : -1F), 1F,
                cPitch,
                held ? shipped("cameraYaw") : RecoilPresets.channel("cameraYaw"),
                RecoilPresets.channel("cameraRoll"), held ? Math.max(1F, Config.cameraTime) : Config.cameraTime,
                climb, deg, side, reach));
    }

    // the movement of the preset the jar gives the gun in hand, at least the full NTM kick (amount 1)
    private static Spring shipped(String channel) {
        String gun = Config.selectedGun();
        String p = gun == null ? null : Grips.shipped(gun, "cameraPreset");
        if (p == null && gun != null) p = Grips.shipped(gun, "kickPreset");
        if (p == null) p = Config.codeValue("kickPreset");
        Spring s = RecoilPresets.preset(p == null ? RecoilPresets.DEFAULT : p, channel, true);
        return s.amount() == 0F ? s : s.with(0, Math.copySign(Math.max(1F, Math.abs(s.amount())), s.amount()));
    }

    // no kick playing (a trace scenario waits for it)
    public static boolean idle() {
        return SHOTS.isEmpty();
    }

    public static void reset() {
        SHOTS.clear();
        appliedPitch = appliedYaw = roll = kept = keptSide = 0F;
    }

    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            reset();
            return;
        }
        if (mc.isPaused()) return;
        long now = GunClock.nanos();
        boolean traced = TestLog.frames() && !SHOTS.isEmpty();
        float pitch = 0F, yaw = 0F, r = 0F;
        for (int i = SHOTS.size() - 1; i >= 0; i--) {
            Shot s = SHOTS.get(i);
            float t = (now - s.start) / 1e9F * s.speed / s.time;
            if (t >= s.length()) {
                kept += s.keep();
                keptSide += s.side;
                SHOTS.remove(i);
                continue;
            }
            pitch += s.pitchAt(t);
            yaw += s.cYaw.at(t) * s.left + s.sideAt(t);
            r += s.cRoll.at(t) * s.shake;
        }
        pitch += kept;
        yaw += keptSide;
        roll = r;
        // only what the ±90° stop let through counts as applied: else the return would take back a kick that never
        // happened and the view would end below where the player aimed
        appliedPitch += turn(player, pitch - appliedPitch, yaw - appliedYaw);
        appliedYaw = yaw;
        if (traced) TestLog.c(-1, "camera", "shots", SHOTS.size(), "pitch", pitch, "yaw", yaw, "roll", r, "applied", appliedPitch,
                "xRot", player.getXRot(), "yRot", player.getYRot(), "kept", kept, "keptSide", keptSide);
        if (SHOTS.isEmpty()) {
            // what is applied now stays: the climb, as much of it as the stop let through
            float climbed = appliedPitch, drifted = appliedYaw;
            appliedPitch = appliedYaw = kept = keptSide = 0F;
            if (burst) {
                burst = false;
                // the view should be back where it was, higher by the climb (the player's own mouse moves count here too)
                if (TestLog.client) TestLog.c(-1, "recoil", "phase", "back", "from", burstPitch, "to", player.getXRot(), "stopped", burstStopped,
                        "climb", climbed, "drift", drifted);
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
