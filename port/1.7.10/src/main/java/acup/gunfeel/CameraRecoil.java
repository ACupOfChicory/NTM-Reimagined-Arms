package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.item.ItemStack;

// Camera recoil per shot along the gun's recoil preset (RecoilPresets: cameraPitch, cameraYaw, cameraRoll): a snap up,
// a return, a damped roll shake. The amount comes from NTM's recoil numbers (lever shotgun: 10 up -> 5.5 deg with
// cameraScale 0.55). The camera always comes back. A server can set a minimum the player can't go below
// (cameraScaleMin): the up and sideways movements are then the jar's preset of the gun, at least as strong and long
// as it ships; the shake (roll) stays the player's, as do the gun model and the arms.
//
// port: copied from 26.2 (4a6ec20, notes/ports/port-1.7.10-copied.md). The numbers are 26.2's line for line; the
// player is reached through View, and the clock and the shake's side come in as arguments (addImpulse, frame), so the
// tests drive the same code with fixed inputs. The editor's last-kick memory (LAST) has no editor here yet.
public final class CameraRecoil {
    // the player's view: pitch in degrees (+ = down, as Entity.rotationPitch), turned like Entity.setAngles
    interface View {
        float pitch();

        // turn the view up by `up` and left by `left` degrees, current and previous rotation alike, so the change
        // shows in this very frame
        void turn(float up, float left);
    }

    private static final class Shot {
        final long start;
        final float up, left, shake, speed, time;
        final Recoil cPitch, cYaw, cRoll;

        Shot(long start, float up, float left, float shake, float speed, Recoil cPitch, Recoil cYaw, Recoil cRoll, float time) {
            this.start = start;
            this.up = up;
            this.left = left;
            this.shake = shake;
            this.speed = speed;
            this.cPitch = cPitch;
            this.cYaw = cYaw;
            this.cRoll = cRoll;
            this.time = time;
        }

        float length() {
            return Math.max(cPitch.length(), Math.max(cYaw.length(), cRoll.length()));
        }
    }

    private static final List<Shot> SHOTS = new ArrayList<Shot>();
    private static float appliedPitch, appliedYaw;
    // the test log: where the view was before the first kick of a burst, and whether the ±90° stop cut a kick short
    private static float burstPitch;
    private static boolean burstStopped, burst;
    public static float roll;

    private CameraRecoil() {
    }

    // vertical/horizontal: what NTM's recoil lambdas asked for since the last tick
    public static void addImpulse(float vertical, float horizontal) {
        EntityClientPlayerMP holder = Minecraft.getMinecraft().thePlayer;
        ItemStack held = holder == null ? null : holder.getHeldItem();
        boolean aiming = held != null && ItemGunBaseNT.getIsAiming(held);
        addImpulse(vertical, horizontal, aiming, holder == null ? null : holder.rotationPitch, System.nanoTime(),
                Math.random() < 0.5 ? 1F : -1F);
    }

    // pitch: the player's pitch now (null = no player); side: the shake's direction, +1 or -1 (26.2: at random)
    static void addImpulse(float vertical, float horizontal, boolean aiming, Float pitch, long now, float side) {
        if (SHOTS.size() >= 16) SHOTS.remove(0);
        // the server's minimum (cameraScaleMin in ntm-gunfeel-server.properties): with one set, the player's scale goes
        // no lower, and the up and sideways curves and their time are the mod's, so no curve of the player's flattens it
        float min = ReaimTimes.number("cameraScaleMin", 0F);
        boolean held = min > 0F;
        float ads = aiming ? Config.adsRecoil : 1F;
        float scale = Math.max(Config.cameraScale * ads, min);
        if (pitch != null && !burst) {
            burst = true;
            burstPitch = pitch;
            burstStopped = false;
        }
        if (TestLog.client) TestLog.c(-1, "recoil", "phase", "kick", "scale", scale, "cameraScale", Config.cameraScale, "min", min, "held", held,
                "time", held ? Math.max(1F, Config.cameraTime) : Config.cameraTime, "ownCurves", !held, "preset", RecoilPresets.presetOf("cameraPitch"), "up", vertical * scale, "pitch", pitch);
        SHOTS.add(new Shot(now, vertical * scale, horizontal * scale,
                Config.cameraShake * ads * side, 1F,
                held ? shipped("cameraPitch") : RecoilPresets.channel("cameraPitch"),
                held ? shipped("cameraYaw") : RecoilPresets.channel("cameraYaw"),
                RecoilPresets.channel("cameraRoll"), held ? Math.max(1F, Config.cameraTime) : Config.cameraTime));
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

    public static void reset() {
        SHOTS.clear();
        appliedPitch = appliedYaw = roll = 0F;
    }

    public static void frame() {
        final Minecraft mc = Minecraft.getMinecraft();
        final EntityClientPlayerMP player = mc.thePlayer;
        if (player == null) {
            reset();
            return;
        }
        if (mc.isGamePaused()) return;
        frame(System.nanoTime(), new View() {
            @Override
            public float pitch() {
                return player.rotationPitch;
            }

            // like 26.2's turn: both current and previous rotation, as Entity.setAngles moves them
            @Override
            public void turn(float up, float left) {
                player.rotationPitch -= up;
                player.prevRotationPitch -= up;
                player.rotationYaw -= left;
                player.prevRotationYaw -= left;
            }
        });
    }

    static void frame(long now, View view) {
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
        appliedPitch += turn(view, pitch - appliedPitch, yaw - appliedYaw);
        appliedYaw = yaw;
        if (SHOTS.isEmpty()) {
            appliedPitch = 0F;
            if (burst) {
                burst = false;
                // the view should be back where it was (the player's own mouse moves count here too)
                if (TestLog.client) TestLog.c(-1, "recoil", "phase", "back", "from", burstPitch, "to", view.pitch(), "stopped", burstStopped);
            }
        }
    }

    // Like Entity.turn: move both current and previous rotation so the change shows in this frame. Returns the pitch
    // change made.
    private static float turn(View view, float up, float left) {
        if (up == 0F && left == 0F) return 0F;
        float applied = pitchTurn(view.pitch(), up);
        if (Math.abs(applied - up) > 1e-4F) burstStopped = true;
        view.turn(applied, left);
        return applied;
    }

    // how much of a turn up by `up` degrees fits from pitch xRot before the ±90° stop
    // (26.2: Mth.clamp, the same for every number but NaN)
    static float pitchTurn(float xRot, float up) {
        return xRot - Math.clamp(xRot - up, -90F, 90F);
    }
}
