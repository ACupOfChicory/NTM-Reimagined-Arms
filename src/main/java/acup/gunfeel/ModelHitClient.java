package acup.gunfeel;

import java.util.Map;
import java.util.WeakHashMap;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

// Adapted from Countered's LivingEntityRendererMixin (MIT). See third-party/accurate-hitboxes.txt.
// Capture only the visible base model's parts; no armour/item layers. Skip hidden and skipDraw cubes and bound
// traversal. Work is throttled to 10 poses/sec/entity, at most 16 entities per 2 ticks, within 96 blocks.
public final class ModelHitClient {
    private record Pending(LivingEntity entity, LivingEntityRenderState state) {}
    private static final Map<java.util.UUID, Pending> PENDING = new java.util.concurrent.ConcurrentHashMap<>();
    private static LivingEntityRenderState processing;
    // Получаем обычные преобразования рендерера и выбор взрослой/детской модели, но не отправляем рисование на GPU.
    private static final SubmitNodeCollector DISCARD = (SubmitNodeCollector) java.lang.reflect.Proxy.newProxyInstance(
            SubmitNodeCollector.class.getClassLoader(), new Class<?>[] {SubmitNodeCollector.class}, (proxy, method, args) -> switch (method.getName()) {
                case "order" -> proxy;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "ModelHitCapture";
                default -> null;
            });
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
        PENDING.clear();
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

    // Вызывается также с Flywheel-Worker. Здесь нет ни обхода модели, ни доступа к общим WeakHashMap.
    public static void enqueue(LivingEntity entity, LivingEntityRenderState state) {
        ModelHitLog.count("client", "attach_called");
        if (PENDING.size() < 512 || PENDING.containsKey(entity.getUUID())) PENDING.put(entity.getUUID(), new Pending(entity, state));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void frame() {
        if (!active()) return;
        Minecraft mc = Minecraft.getInstance();
        java.util.List<Pending> work = new java.util.ArrayList<>();
        for (var entry : PENDING.entrySet()) if (PENDING.remove(entry.getKey(), entry.getValue())) {
            Pending pending = entry.getValue();
            LivingEntity entity = pending.entity();
            LivingEntityRenderState state = pending.state();
            if (entity.level() != level || !entity.isAlive() || entity.distanceToSqr(mc.player) > ModelHits.RANGE * ModelHits.RANGE) continue;
            ENTITIES.put(state, new java.lang.ref.WeakReference<>(entity));
            SEEN.put(entity, level.getGameTime());
            work.add(pending);
            ModelHitLog.event("client", "attached", entity, () -> "state=" + System.identityHashCode(state)
                    + " class=" + state.getClass().getName());
        }
        for (Pending pending : work) {
            if (!ready(pending.entity(), level.getGameTime())) continue;
            var renderer = mc.getEntityRenderDispatcher().getRenderer(pending.entity());
            if (!(renderer instanceof LivingEntityRenderer living)) continue;
            var state = pending.state();
            CameraRenderState camera = new CameraRenderState();
            camera.pos = new net.minecraft.world.phys.Vec3(state.x, state.y, state.z);
            camera.blockPos = net.minecraft.core.BlockPos.containing(camera.pos);
            camera.orientation = new org.joml.Quaternionf();
            PoseStack stack = new PoseStack();
            var offset = living.getRenderOffset(state);
            stack.translate(offset.x, offset.y, offset.z);
            processing = state;
            try {
                living.submit(state, stack, DISCARD, camera);
            } finally { processing = null; }
        }
    }

    public static <S extends LivingEntityRenderState> void capture(EntityModel<? super S> model, S state,
            PoseStack stack, CameraRenderState camera) {
        if (state != processing) return;
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
        if (!ready(entity, tick)) {
            ModelHitLog.count("client", "throttled");
            return;
        }
        ModelHitLog.count("client", "capture_attempt");
        CAPTURED.put(entity, tick);
        budget--;
        model.setupAnim(state);
        var captured = ModelPoseCapture.collect(model.root(), stack, camera.pos.x - state.x, camera.pos.y - state.y,
                camera.pos.z - state.z, ModelPoseCapture.ACCESS);
        var boxes = captured.boxes();
        if (!captured.complete() || boxes.isEmpty() || boxes.stream().anyMatch(b -> !b.valid(ModelHits.radius(entity)))) {
            ModelHitLog.event("client", "capture_rejected", entity, () -> "model=" + model.getClass().getName()
                    + " complete=" + captured.complete() + " nodes=" + captured.nodes() + " cubes=" + boxes.size()
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

    private static boolean ready(LivingEntity entity, long tick) {
        Minecraft mc = Minecraft.getInstance();
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
        return SELECTED.contains(entity) && budget > 0
                && tick - CAPTURED.getOrDefault(entity, Long.MIN_VALUE / 2) >= ModelHits.INTERVAL;
    }
}
