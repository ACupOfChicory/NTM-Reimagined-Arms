package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import acup.gunfeel.mixin.mc.ModelPartAccess;

// Adapted from Countered's LivingEntityRendererMixin (MIT). See third-party/accurate-hitboxes.txt.
// Capture only the visible base model's parts; no armour/item layers. Skip hidden and skipDraw cubes and bound
// traversal. Work is throttled to 10 poses/sec/entity, at most 16 entities per 2 ticks, within 96 blocks.
public final class ModelHitClient {
    private static final Map<LivingEntityRenderState, java.lang.ref.WeakReference<LivingEntity>> ENTITIES = new WeakHashMap<>();
    private static final Map<LivingEntity, Long> CAPTURED = new WeakHashMap<>();
    private static final Map<LivingEntity, Long> SEEN = new WeakHashMap<>();
    private static final java.util.Set<LivingEntity> SELECTED = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static Level level;
    private static long period = Long.MIN_VALUE, nextCheck;
    private static int budget;
    private static boolean active;
    private static long nextDiagnostic;

    private ModelHitClient() {}

    public static void diagnostics() {
        if (Minecraft.getInstance().level == null) return;
        long now = System.currentTimeMillis();
        if (now < nextDiagnostic) return;
        nextDiagnostic = now + 5000;
        org.slf4j.LoggerFactory.getLogger("ntm-gunfeel").info(
                "[modeldebug] client heartbeat enabled={} tick={} seen={} selected={} bound_states={} counters={} integrated_server={}",
                ModelHits.enabled(), Minecraft.getInstance().level.getGameTime(), SEEN.size(), SELECTED.size(), ENTITIES.size(),
                ModelHitLog.counts("client"), Minecraft.getInstance().getSingleplayerServer() == null ? "remote" : ModelHitLog.counts("server"));
    }

    public static void clear() {
        if (level != null) ModelHits.clear(level);
        ENTITIES.clear();
        CAPTURED.clear();
        SEEN.clear();
        SELECTED.clear();
        level = null;
        nextCheck = 0;
        period = Long.MIN_VALUE;
    }

    private static boolean active() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) {
            clear();
            level = mc.level;
        }
        if (mc.player == null || mc.getConnection() == null) return false;
        long now = System.currentTimeMillis();
        if (now >= nextCheck) { active = ModelHits.enabled(); nextCheck = now + 1000; }
        return active;
    }

    public static void attach(LivingEntity entity, LivingEntityRenderState state) {
        ModelHitLog.count("client", "attach_called");
        if (active()) {
            ENTITIES.put(state, new java.lang.ref.WeakReference<>(entity));
            SEEN.put(entity, level.getGameTime());
            ModelHitLog.event("client", "attached", entity, () -> "state=" + System.identityHashCode(state)
                    + " class=" + state.getClass().getName());
        }
    }

    public static <S extends LivingEntityRenderState> void capture(EntityModel<? super S> model, S state,
            PoseStack stack, CameraRenderState camera) {
        ModelHitLog.count("client", "submit_called");
        if (!active()) { ModelHitLog.count("client", "inactive"); return; }
        var ref = ENTITIES.get(state);
        LivingEntity entity = ref == null ? null : ref.get();
        Minecraft mc = Minecraft.getInstance();
        if (entity == null || !entity.isAlive() || entity.distanceToSqr(mc.player) > ModelHits.RANGE * ModelHits.RANGE) {
            ModelHitLog.event("client", "target_rejected", entity, () -> "state=" + System.identityHashCode(state)
                    + " class=" + state.getClass().getName() + " alive=" + (entity != null && entity.isAlive())
                    + " distance2=" + (entity == null ? -1 : entity.distanceToSqr(mc.player)));
            return;
        }
        long tick = level.getGameTime();
        if (tick / ModelHits.INTERVAL != period) {
            period = tick / ModelHits.INTERVAL;
            budget = ModelHits.MAX_UPDATES;
            SELECTED.clear();
            // Oldest first: a crowded scene must not permanently starve entities late in render order.
            SEEN.entrySet().stream().filter(e -> tick - e.getValue() <= 2 && e.getKey().isAlive()
                            && e.getKey().distanceToSqr(mc.player) <= ModelHits.RANGE * ModelHits.RANGE)
                    .map(Map.Entry::getKey).sorted(java.util.Comparator
                            .comparingLong((LivingEntity e) -> CAPTURED.getOrDefault(e, Long.MIN_VALUE / 2))
                            .thenComparingDouble(e -> e.distanceToSqr(mc.player)))
                    .limit(ModelHits.MAX_UPDATES).forEach(SELECTED::add);
        }
        if (!SELECTED.contains(entity) || budget <= 0 || tick - CAPTURED.getOrDefault(entity, Long.MIN_VALUE / 2) < ModelHits.INTERVAL) {
            ModelHitLog.count("client", "throttled");
            return;
        }
        ModelHitLog.count("client", "capture_attempt");
        CAPTURED.put(entity, tick);
        budget--;
        // submit defers setupAnim with the draw command on entities without layers; explicitly apply this state
        // rather than reading whichever entity last used this shared renderer's model.
        model.setupAnim(state);
        List<ModelBox> boxes = new ArrayList<>();
        int[] nodes = {0};
        boolean complete = traverse("root", model.root(), stack, camera.pos.x - state.x, camera.pos.y - state.y,
                camera.pos.z - state.z, boxes, nodes, 0);
        if (!complete || boxes.isEmpty() || boxes.stream().anyMatch(b -> !b.valid(ModelHits.radius(entity)))) {
            ModelHitLog.event("client", "capture_rejected", entity, () -> "model=" + model.getClass().getName()
                    + " complete=" + complete + " nodes=" + nodes[0] + " cubes=" + boxes.size()
                    + " radius=" + ModelHits.radius(entity) + " entity_pos=" + entity.position()
                    + " state_pos=" + state.x + "," + state.y + "," + state.z + " camera=" + camera.pos
                    + " invalid_cube=" + boxes.stream().filter(b -> !b.valid(ModelHits.radius(entity))).findFirst().orElse(null));
            return;
        }
        ModelHits.put(level, entity, entity.yBodyRot, boxes);
        ClientPacketDistributor.sendToServer(new ModelPosePayload(entity.getId(), entity.getUUID(), entity.getX(), entity.getY(),
                entity.getZ(), entity.yBodyRot, boxes));
        ModelHitLog.event("client", "sent", entity, () -> "cubes=" + boxes.size() + " model=" + model.getClass().getName()
                + " tick=" + tick + " yaw=" + entity.yBodyRot + " first_cube=" + boxes.getFirst());
    }

    private static boolean traverse(String name, ModelPart part, PoseStack stack, double x, double y, double z,
            List<ModelBox> boxes, int[] nodes, int depth) {
        if (++nodes[0] > 2048 || depth > 32 || name.length() > ModelBox.MAX_PART_NAME) return false;
        if (!part.visible) return true;
        stack.pushPose();
        try {
            part.translateAndRotate(stack);
            ModelPartAccess access = (ModelPartAccess) (Object) part;
            if (!part.skipDraw) for (ModelPart.Cube cube : access.gunfeel$cubes()) {
                if (cube.maxX <= cube.minX || cube.maxY <= cube.minY || cube.maxZ <= cube.minZ) continue;
                if (boxes.size() >= ModelPosePayload.MAX_BOXES) return false;
                Matrix4f m = stack.last().pose();
                Vector3f origin = m.transformPosition(cube.minX / 16F, cube.minY / 16F, cube.minZ / 16F, new Vector3f());
                Vector3f u = m.transformDirection((cube.maxX - cube.minX) / 16F, 0, 0, new Vector3f());
                Vector3f v = m.transformDirection(0, (cube.maxY - cube.minY) / 16F, 0, new Vector3f());
                Vector3f w = m.transformDirection(0, 0, (cube.maxZ - cube.minZ) / 16F, new Vector3f());
                // Same quantization locally and in the packet; subtract the world origin before converting to float.
                boxes.add(new ModelBox(name, (float) (origin.x + x), (float) (origin.y + y), (float) (origin.z + z),
                        u.x, u.y, u.z, v.x, v.y, v.z, w.x, w.y, w.z));
            }
            for (var entry : access.gunfeel$children().entrySet())
                if (!traverse(name + "/" + entry.getKey(), entry.getValue(), stack, x, y, z, boxes, nodes, depth + 1)) return false;
            return true;
        } finally {
            stack.popPose();
        }
    }
}
