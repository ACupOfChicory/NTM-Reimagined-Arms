package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.lwjgl.opengl.GL11;

// Gun model kick per shot along the gun's recoil preset (RecoilPresets: kickPitch ... kickPosZ), as the preset has it
// (times modelKick): the preset is for the gun's class already, so NTM's recoil number does not scale it again. Yaw
// and roll get a random direction and size per shot. Overlapping shots add up. Rotation pivots on the right-hand grip.
//
// port: copied from 26.2 (4a6ec20, notes/ports/port-1.7.10-copied.md). The numbers are 26.2's; the clock and the
// random numbers come in as arguments (shot, sample) for the tests, and apply turns the GL matrix (26.2: PoseStack).
public final class ModelRecoil {
    private static final class Shot {
        final long start;
        final float strength, speed, pitch, yaw, roll;
        final Recoil cPitch, cYaw, cRoll, cY, cZ;

        Shot(long start, float strength, float speed, float pitch, float yaw, float roll, Recoil cPitch, Recoil cYaw, Recoil cRoll, Recoil cY, Recoil cZ) {
            this.start = start;
            this.strength = strength;
            this.speed = speed;
            this.pitch = pitch;
            this.yaw = yaw;
            this.roll = roll;
            this.cPitch = cPitch;
            this.cYaw = cYaw;
            this.cRoll = cRoll;
            this.cY = cY;
            this.cZ = cZ;
        }

        float length() {
            return Math.max(Math.max(cPitch.length(), cYaw.length()), Math.max(cRoll.length(), Math.max(cY.length(), cZ.length())));
        }
    }

    private static final Random RANDOM = new Random();
    private static final List<Shot> SHOTS = new ArrayList<Shot>();

    private ModelRecoil() {
    }

    public static boolean idle() {
        return SHOTS.isEmpty();
    }

    public static void shot(boolean aiming) {
        shot(aiming, System.nanoTime(), RANDOM);
    }

    // the same draws from `random` in the same order as 26.2
    static void shot(boolean aiming, long now, Random random) {
        float s = Config.modelKick * (aiming ? Config.adsRecoil : 1F);
        if (s <= 0F) return;
        float loose = aiming ? 0.5F : 1F;
        float speed = Config.modelSpeed;
        if (SHOTS.size() >= 12) SHOTS.remove(0);
        SHOTS.add(new Shot(now, s, speed,
                0.85F + 0.3F * random.nextFloat(),
                loose * (random.nextBoolean() ? 1F : -1F) * (0.5F + 0.7F * random.nextFloat()),
                loose * (random.nextBoolean() ? 1F : -1F) * (0.6F + 0.6F * random.nextFloat()),
                RecoilPresets.channel("kickPitch"), RecoilPresets.channel("kickYaw"), RecoilPresets.channel("kickRoll"),
                RecoilPresets.channel("kickPosY"), RecoilPresets.channel("kickPosZ")));
    }

    static void reset() {
        SHOTS.clear();
    }

    // {pitch, yaw, roll, y, z} of all shots at `now` (26.2: the sums inside apply); null = no shot left
    static float[] sample(long now) {
        if (SHOTS.isEmpty()) return null;
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
        return new float[] {pitch, yaw, roll, y, z};
    }

    // The GL matrix is NTM's gun frame before the model scale: +Z forward, +Y up. size = gun length there,
    // pivot = the right-hand grip there.
    public static void apply(float size, float[] pivot) {
        float[] k = sample(System.nanoTime());
        if (k == null) return;
        float pitch = k[0], yaw = k[1], roll = k[2], y = k[3], z = k[4];
        float px = size * 0.016F; // one kick unit (1/16 of the gun length scale) in this frame
        GL11.glTranslatef(0F, y * px, -z * px);
        GL11.glTranslatef(pivot[0], pivot[1], pivot[2]);
        GL11.glRotatef(-pitch * Config.modelRotate, 1F, 0F, 0F);
        GL11.glRotatef(yaw * Config.modelRotate, 0F, 1F, 0F);
        GL11.glRotatef(roll * Config.modelRotate, 0F, 0F, 1F);
        GL11.glTranslatef(-pivot[0], -pivot[1], -pivot[2]);
    }
}
