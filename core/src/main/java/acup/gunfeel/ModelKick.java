package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// The gun model's kicks in flight (ModelRecoil asks for them and turns the model by their sum): each shot's pitch,
// yaw, roll, up and back movement along the preset's curves, with a random direction and size of yaw and roll per
// shot. Time is the mod's clock in nanoseconds (GunClock.nanos), given by the caller, as is the random (Rng.model).
final class ModelKick {
    record Shot(long start, float strength, float speed, float pitch, float yaw, float roll,
            Recoil cPitch, Recoil cYaw, Recoil cRoll, Recoil cY, Recoil cZ) {
        float length() {
            return Math.max(Math.max(cPitch.length(), cYaw.length()), Math.max(cRoll.length(), Math.max(cY.length(), cZ.length())));
        }
    }

    private static final List<Shot> SHOTS = new ArrayList<>();

    private ModelKick() {
    }

    static boolean idle() {
        return SHOTS.isEmpty();
    }

    // every kick played to its end by now
    static boolean over(long now) {
        for (Shot s : SHOTS) if ((now - s.start) / 1e9F * s.speed < s.length()) return false;
        return true;
    }

    // a shot of strength s at time now; loose: the share of the random yaw and roll (half while aiming). At most 12
    // at once, the oldest gives way.
    static Shot add(long now, float s, float speed, float loose, Random random, Recoil cPitch, Recoil cYaw, Recoil cRoll,
            Recoil cY, Recoil cZ) {
        if (SHOTS.size() >= 12) SHOTS.remove(0);
        Shot n = new Shot(now, s, speed,
                0.85F + 0.3F * random.nextFloat(),
                loose * (random.nextBoolean() ? 1F : -1F) * (0.5F + 0.7F * random.nextFloat()),
                loose * (random.nextBoolean() ? 1F : -1F) * (0.6F + 0.6F * random.nextFloat()),
                cPitch, cYaw, cRoll, cY, cZ);
        SHOTS.add(n);
        return n;
    }

    static int shots() {
        return SHOTS.size();
    }

    // the kicks' pitch, yaw, roll (before modelRotate), up and back (kick units) at time now (ns) into out; the
    // finished ones leave
    static void at(long now, float[] out) {
        float pitch = 0F, yaw = 0F, roll = 0F, y = 0F, z = 0F;
        for (int i = SHOTS.size() - 1; i >= 0; i--) {
            Shot s = SHOTS.get(i);
            float t = (now - s.start) / 1e9F * s.speed;
            if (t >= s.length()) {
                SHOTS.remove(i);
                continue;
            }
            pitch += s.cPitch.at(t) * s.strength * s.pitch;
            yaw += s.cYaw.at(t) * s.strength * s.yaw;
            roll += s.cRoll.at(t) * s.strength * s.roll;
            y += s.cY.at(t) * s.strength;
            z += s.cZ.at(t) * s.strength;
        }
        out[0] = pitch;
        out[1] = yaw;
        out[2] = roll;
        out[3] = y;
        out[4] = z;
    }
}
