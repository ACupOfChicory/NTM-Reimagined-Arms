package acup.gunfeel;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import com.hbm.render.loader.GroupObject;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector.CustomGeometryRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

// Контекст переносится вместе с настоящим отложенным draw callback. Повторного рендера нет.
public final class NtmMeshHits {
    private record Frame(LivingEntity entity, EntityRenderState state, Vec3 offset, float yaw,
            LinkedHashSet<ModelBox> boxes) {}
    private static final ThreadLocal<Frame> SUBMIT = new ThreadLocal<>(), DRAW = new ThreadLocal<>();
    private static final java.util.Map<Frame, Boolean> PENDING = new java.util.IdentityHashMap<>();
    private record Prepared(LivingEntity entity, float yaw, List<ModelBox> boxes) {}
    private static final java.util.Map<UUID, Prepared> PREPARED = new java.util.concurrent.ConcurrentHashMap<>();

    private NtmMeshHits() {}

    public static void begin(EntityRenderState state, CameraRenderState camera) {
        SUBMIT.remove();
        if (!ModelHitClient.enabled() || !acup.gunfeel.compat.Feature.MODEL_HITBOXES.on() || !camera.initialized
                || !(state instanceof ModelHitState info) || info.gunfeel$target() == null
                || !ModelHitClient.eligible(info.gunfeel$target())
                || !net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(state.entityType).getNamespace().equals("hbm")) return;
        LivingEntity entity = info.gunfeel$target();
        Frame frame = new Frame(entity, state, camera.pos.subtract(state.x, state.y, state.z),
                Mth.rotLerp(state.partialTick, entity.yBodyRotO, entity.yBodyRot), new LinkedHashSet<>());
        SUBMIT.set(frame);
    }

    public static void end() { SUBMIT.remove(); }

    public static void model(Object state, Object model) {
        Frame frame = SUBMIT.get();
        if (frame != null && frame.state() == state && !(state instanceof LivingEntityRenderState)
                && model instanceof EntityModel<?> entityModel)
            ((ModelHitState) state).gunfeel$primary(entityModel, null);
    }

    public static CustomGeometryRenderer wrap(CustomGeometryRenderer renderer) {
        Frame frame = SUBMIT.get();
        if (frame == null) return renderer;
        PENDING.put(frame, Boolean.TRUE);
        return (pose, buffer) -> {
            Frame previous = DRAW.get();
            DRAW.set(frame);
            try { renderer.render(pose, buffer); }
            finally { if (previous == null) DRAW.remove(); else DRAW.set(previous); }
        };
    }

    public static void group(GroupObject group, PoseStack.Pose pose) {
        Frame frame = DRAW.get();
        if (frame == null || frame.boxes().size() > ModelPosePayload.MAX_BOXES) return;
        long started = System.nanoTime();
        var offset = frame.offset();
        ModelBox box = MeshModelPose.box(group.name, group.bounds(), pose.pose(), offset.x, offset.y, offset.z);
        if (box != null) frame.boxes().add(box);
        ModelHitClient.captureCost(System.nanoTime() - started);
    }

    public static void flush() {
        SUBMIT.remove(); DRAW.remove();
        for (Frame frame : PENDING.keySet()) if (ModelHitClient.eligible(frame.entity()) && !frame.boxes().isEmpty())
            ModelHitClient.publish(frame.entity(), frame.yaw(), List.copyOf(frame.boxes()), "ntm_mesh");
        PENDING.clear();
        for (var entry : PREPARED.entrySet()) if (PREPARED.remove(entry.getKey(), entry.getValue())) {
            Prepared pose = entry.getValue();
            if (ModelHitClient.eligible(pose.entity()))
                ModelHitClient.publish(pose.entity(), pose.yaw(), pose.boxes(), "ntm_flywheel_mesh");
        }
    }

    public static void offer(LivingEntity entity, float yaw, List<ModelBox> boxes) {
        if (PREPARED.size() >= 512 && !PREPARED.containsKey(entity.getUUID())) return;
        PREPARED.put(entity.getUUID(), new Prepared(entity, yaw, List.copyOf(boxes)));
    }

    public static void clear() { SUBMIT.remove(); DRAW.remove(); PENDING.clear(); PREPARED.clear(); }
}
