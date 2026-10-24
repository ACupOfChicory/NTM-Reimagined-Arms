package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

// Gun model kick per shot along the gun's recoil preset (RecoilPresets: kickPitch ... kickPosZ), as the preset has it
// (times modelKick): the preset is for the gun's class already, so NTM's recoil number does not scale it again. Yaw
// and roll get a random direction and size per shot. Overlapping shots add up. Rotation pivots on the right-hand grip.
public final class ModelRecoil {
    private record Shot(long start, float strength, float speed, float pitch, float yaw, float roll,
            Recoil cPitch, Recoil cYaw, Recoil cRoll, Recoil cY, Recoil cZ) {
        float length() {
            return Math.max(Math.max(cPitch.length(), cYaw.length()), Math.max(cRoll.length(), Math.max(cY.length(), cZ.length())));
        }
    }

    private static final Random RANDOM = new Random();
    private static final List<Shot> SHOTS = new ArrayList<>();

    private ModelRecoil() {
    }

    public static boolean idle() {
        return SHOTS.isEmpty();
    }

    public static void shot(boolean aiming) {
        float s = Config.modelKick;
        if (s <= 0F) return;
        float loose = aiming ? 0.5F : 1F;
        float speed = Config.modelSpeed;
        if (SHOTS.size() >= 12) SHOTS.remove(0);
        SHOTS.add(new Shot(System.nanoTime(), s, speed,
                0.85F + 0.3F * RANDOM.nextFloat(),
                loose * (RANDOM.nextBoolean() ? 1F : -1F) * (0.5F + 0.7F * RANDOM.nextFloat()),
                loose * (RANDOM.nextBoolean() ? 1F : -1F) * (0.6F + 0.6F * RANDOM.nextFloat()),
                RecoilPresets.channel("kickPitch"), RecoilPresets.channel("kickYaw"), RecoilPresets.channel("kickRoll"),
                RecoilPresets.channel("kickPosY"), RecoilPresets.channel("kickPosZ")));
    }

    // Pose is in NTM's gun frame before the model scale: +Z forward, +Y up. size = gun length there.
    public static void apply(PoseStack pose, float size, Vector3f pivot) {
        if (SHOTS.isEmpty()) return;
        long now = System.nanoTime();
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
        float px = size * 0.016F; // one kick unit (1/16 of the gun length scale) in this frame
        pose.translate(0F, y * px, -z * px);
        pose.translate(pivot.x, pivot.y, pivot.z);
        pose.mulPose(Axis.XP.rotationDegrees(-pitch * Config.modelRotate));
        pose.mulPose(Axis.YP.rotationDegrees(yaw * Config.modelRotate));
        pose.mulPose(Axis.ZP.rotationDegrees(roll * Config.modelRotate));
        pose.translate(-pivot.x, -pivot.y, -pivot.z);
    }
}
