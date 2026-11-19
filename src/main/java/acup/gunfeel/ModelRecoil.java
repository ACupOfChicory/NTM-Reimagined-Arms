package acup.gunfeel;

import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

// Gun model kick per shot along the gun's recoil preset (RecoilPresets: kickPitch ... kickPosZ), as the preset has it
// (times modelKick): the preset is for the gun's class already, so NTM's recoil number does not scale it again. Yaw
// and roll get a random direction and size per shot. Overlapping shots add up. Rotation pivots on the right-hand grip.
// The kicks and their sum are core's (ModelKick); here: the settings, the clock and the gun's pose.
public final class ModelRecoil {
    private static final float[] SUM = new float[5];

    private ModelRecoil() {
    }

    public static boolean idle() {
        return ModelKick.idle();
    }

    // every kick played to its end by now, drawn or not (a gun NTM draws without the mod's hook never clears them)
    public static boolean over() {
        return ModelKick.over(GunClock.nanos());
    }

    public static void shot(boolean aiming) {
        float s = Config.modelKick * (aiming ? Config.adsRecoil : 1F) * Prone.recoil(net.minecraft.client.Minecraft.getInstance().player);
        if (s <= 0F) return;
        float loose = aiming ? 0.5F : 1F;
        float speed = Config.modelSpeed;
        java.util.Random random = Rng.model();
        ModelKick.Shot n = ModelKick.add(GunClock.nanos(), s, speed, loose, random,
                RecoilPresets.channel("kickPitch"), RecoilPresets.channel("kickYaw"), RecoilPresets.channel("kickRoll"),
                RecoilPresets.channel("kickPosY"), RecoilPresets.channel("kickPosZ"));
        if (TestLog.frames()) {
            TestLog.c(-1, "kick_shot", "strength", n.strength(), "speed", n.speed(), "pitch", n.pitch(), "yaw", n.yaw(), "roll", n.roll());
        }
    }

    // Pose is in NTM's gun frame before the model scale: +Z forward, +Y up. size = gun length there.
    public static void apply(PoseStack pose, float size, Vector3f pivot) {
        if (ModelKick.idle()) return;
        ModelKick.at(GunClock.nanos(), SUM);
        float pitch = SUM[0], yaw = SUM[1], roll = SUM[2], y = SUM[3], z = SUM[4];
        if (TestLog.frames()) TestLog.c(-1, "kick", "shots", ModelKick.shots(), "pitch", pitch, "yaw", yaw, "roll", roll, "y", y, "z", z, "size", size, "pivot", pivot);
        float px = size * 0.016F; // one kick unit (1/16 of the gun length scale) in this frame
        pose.translate(0F, y * px, -z * px);
        pose.translate(pivot.x, pivot.y, pivot.z);
        pose.mulPose(Axis.XP.rotationDegrees(-pitch * Config.modelRotate));
        pose.mulPose(Axis.YP.rotationDegrees(yaw * Config.modelRotate));
        pose.mulPose(Axis.ZP.rotationDegrees(roll * Config.modelRotate));
        pose.translate(-pivot.x, -pivot.y, -pivot.z);
    }
}
