package acup.gunfeel;

import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.LivingEntity;

// port/continuum: a stand-in for NtmMeshHits (NTM NEXT's OBJ mobs through HFRWavefrontObject/GroupObject and Flywheel;
// Continuum's are CompatEntityRenderers over WavefrontObj). Vanilla-model mobs keep their hitboxes; NTM's OBJ mobs fall
// back to their box.
public final class NtmMeshHits {
    public static Runnable gpuFlush = () -> {
    };

    private NtmMeshHits() {
    }

    public static void begin(EntityRenderState state, CameraRenderState camera) {
    }

    public static void end() {
    }

    public static void model(Object state, Object model, PoseStack stack) {
    }

    public static void blocks(PoseStack stack, List<?> parts) {
    }

    public static void offer(LivingEntity entity, float yaw, List<ModelBox> boxes, org.joml.Vector3f origin) {
    }

    public static void flush() {
    }

    public static void failed(Object submit) {
    }

    public static boolean prepared(Object submit) {
        return false;
    }

    public static void clear() {
        SubmittedModelGeometry.clear();
    }
}
