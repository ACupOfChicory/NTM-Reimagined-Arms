package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

// The camera's kicks in flight (CameraRecoil asks for them): each shot's up, sideways and roll movement along its
// curves, what stays of the finished ones (cameraClimb, cameraDriftDeg), and the sum at a moment. Time is the mod's
// clock in nanoseconds (GunClock.nanos), given by the caller; the view itself (pitch, yaw, the ±90° stop) is the
// version's.
final class CameraKick {
    record Shot(long start, float up, float left, float shake, float speed, Recoil cPitch, Recoil cYaw, Recoil cRoll, float time,
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

    static final float[] NO_REACH = {0F};
    // reach(t), in steps of this much curve time
    static final float REACH_STEP = 0.001F;

    private static final List<Shot> SHOTS = new ArrayList<>();
    // degrees that stay from the burst's finished shots (cameraClimb), up and sideways
    private static float kept, keptSide;

    private CameraKick() {
    }

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
        // Minecraft's Mth.lerp(x - i, a, b), to the bit
        float d = x - i, a = table[i], b = table[i + 1];
        return a + d * (b - a);
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

    // how much of a turn up by `up` degrees fits from pitch xRot before the ±90° stop
    static float pitchTurn(float xRot, float up) {
        // Minecraft's Mth.clamp(xRot - up, -90, 90), to the bit (NaN stays NaN)
        float v = xRot - up;
        return xRot - (v < -90F ? -90F : Math.min(v, 90F));
    }

    // no kick playing
    static boolean idle() {
        return SHOTS.isEmpty();
    }

    // at most 16 kicks at once: the oldest gives way, what it would have left stays
    static void makeRoom() {
        if (SHOTS.size() >= 16) {
            Shot old = SHOTS.remove(0);
            kept += old.keep();
            keptSide += old.side;
        }
    }

    static void add(Shot s) {
        SHOTS.add(s);
    }

    static int shots() {
        return SHOTS.size();
    }

    static float kept() {
        return kept;
    }

    static float keptSide() {
        return keptSide;
    }

    // the kicks over (the staying parts are in the view by now)
    static void clearKept() {
        kept = keptSide = 0F;
    }

    static void clear() {
        SHOTS.clear();
        kept = keptSide = 0F;
    }

    // the kicks' up, sideways and roll movement at time now (ns) into out; the finished ones leave, what stays of
    // them is added in
    static void at(long now, float[] out) {
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
        out[0] = pitch;
        out[1] = yaw;
        out[2] = r;
    }
}
