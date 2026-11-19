package acup.gunfeel;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

// Camera recoil per shot along the gun's recoil preset (RecoilPresets: cameraPitch, cameraYaw, cameraRoll): a snap up,
// a return, a damped roll shake. The amount comes from NTM's recoil numbers (lever shotgun: 10 up -> 5.5 deg with
// cameraScale 0.55). The camera comes back, all but the gun's cameraClimb share of the up movement, its
// cameraClimbDeg degrees up and its cameraDriftDeg sideways: that part moves with the kick and stays, so a burst walks
// the aim up and wanders it sideways; cameraClimbRamp lets it grow over a burst's first shots. A server can set a
// minimum the player can't go below
// (cameraScaleMin): the up and sideways movements are then the jar's preset of the gun, at least as strong and long
// as it ships; the shake (roll) stays the player's, as do the gun model and the arms.
public final class CameraRecoil {
    // the kicks themselves, their curves and what stays of them, are core's (CameraKick)
    private static final float[] SUM = new float[3];
    private static float appliedPitch, appliedYaw;
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
        CameraKick.makeRoom();
        // the server's minimum (cameraScaleMin in ntm-gunfeel-server.properties): with one set, the player's scale goes
        // no lower, and the up and sideways curves and their time are the mod's, so no curve of the player's flattens it
        float min = ReaimTimes.number("cameraScaleMin", 0F);
        boolean held = min > 0F;
        boolean aiming = holder != null && com.hbm.items.weapon.sedna.ItemGunBaseNT.getIsAiming(holder.getMainHandItem());
        float ads = aiming ? Config.adsRecoil : 1F;
        // powerArmorRecoil and proneRecoil: in NTM's power armour and lying down the whole kick is smaller, the server's
        // minimum included
        float armor = Prone.cameraRecoil(holder);
        float scale = Math.max(Config.cameraScale * ads, min) * armor;
        if (TestLog.client && armor != 1F) TestLog.c(-1, "power_armor", "camera", armor, "model", Prone.recoil(holder), "armor", PowerArmor.recoil(holder), "lying", Prone.lying(holder));
        Recoil cPitch = held ? shipped("cameraPitch") : RecoilPresets.channel("cameraPitch");
        LocalPlayer pl = Minecraft.getInstance().player;
        boolean first = !burst;
        if (pl != null && !burst) {
            burst = true;
            burstPitch = pl.getXRot();
            burstStopped = false;
        }
        burstShots = first ? 1 : burstShots + 1;
        float ramp = CameraKick.ramp(burstShots, Config.cameraClimbRamp);
        float climb = Config.cameraClimb * ramp;
        // degrees up, always: for guns whose NTM recoil is noise around 0 (G3, M2, miniguns), where a share of the kick
        // would wander up and down; lighter while aiming, like the kick
        float deg = Config.cameraClimbDeg * ads * armor * ramp;
        float side = 0F;
        if (Config.cameraDriftDeg > 0F) {
            drift = CameraKick.nextDrift(drift, first, (float) Rng.camera().nextGaussian());
            side = Config.cameraDriftDeg * ads * armor * ramp * drift;
        }
        float[] reach = climb > 0F || deg > 0F || side != 0F ? CameraKick.reachTable(cPitch) : CameraKick.NO_REACH;
        if (TestLog.client) TestLog.c(-1, "recoil", "phase", "kick", "scale", scale, "cameraScale", Config.cameraScale, "min", min, "held", held,
                "time", held ? Math.max(1F, Config.cameraTime) : Config.cameraTime, "ownCurves", !held, "preset", RecoilPresets.presetOf("cameraPitch"), "up", vertical * scale, "pitch", pl == null ? null : pl.getXRot(),
                "cameraClimb", climb, "cameraClimbDeg", deg, "ramp", ramp, "shot", burstShots, "keep", vertical * scale * climb * reach[reach.length - 1] + deg,
                "side", side);
        CameraKick.add(new CameraKick.Shot(GunClock.nanos(), vertical * scale, horizontal * scale,
                Config.cameraShake * ads * armor * (Rng.camera().nextDouble() < 0.5 ? 1F : -1F), 1F,
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
        return CameraKick.idle();
    }

    public static void reset() {
        CameraKick.clear();
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
        long now = GunClock.nanos();
        boolean traced = TestLog.frames() && !CameraKick.idle();
        CameraKick.at(now, SUM);
        float pitch = SUM[0], yaw = SUM[1], r = SUM[2];
        roll = r;
        // only what the ±90° stop let through counts as applied: else the return would take back a kick that never
        // happened and the view would end below where the player aimed
        appliedPitch += turn(player, pitch - appliedPitch, yaw - appliedYaw);
        appliedYaw = yaw;
        if (traced) TestLog.c(-1, "camera", "shots", CameraKick.shots(), "pitch", pitch, "yaw", yaw, "roll", r, "applied", appliedPitch,
                "xRot", player.getXRot(), "yRot", player.getYRot(), "kept", CameraKick.kept(), "keptSide", CameraKick.keptSide());
        if (CameraKick.idle()) {
            // what is applied now stays: the climb, as much of it as the stop let through
            float climbed = appliedPitch, drifted = appliedYaw;
            appliedPitch = appliedYaw = 0F;
            CameraKick.clearKept();
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
        float applied = CameraKick.pitchTurn(player.getXRot(), up);
        if (Math.abs(applied - up) > 1e-4F) burstStopped = true;
        player.setXRot(player.getXRot() - applied);
        player.xRotO -= applied;
        player.setYRot(player.getYRot() - left);
        player.yRotO -= left;
        return applied;
    }
}
