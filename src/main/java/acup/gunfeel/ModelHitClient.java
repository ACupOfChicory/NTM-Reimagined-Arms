package acup.gunfeel;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

// Пассивный съём уже рассчитанной позы: обычный ModelFeatureRenderer либо снимок Flywheel/Vanillate.
// Здесь никогда не создаётся render state, не вызывается setupAnim и не повторяется submit рендерера.
public final class ModelHitClient {
    private record Ready(LivingEntity entity, ModelPoseLayout layout, VanillatePose.Data pose,
            double x, double y, double z, float yaw) {}
    private static final Map<UUID, Ready> READY = new ConcurrentHashMap<>();
    private record Sync(LivingEntity entity, float yaw, List<ModelBox> boxes, String source, long tick) {}
    private static final Map<UUID, Sync> SYNC = new java.util.HashMap<>();
    private static final Map<UUID, Long> SENT = new java.util.HashMap<>();
    private static final java.util.Set<Long> MESH_SENT = new java.util.HashSet<>();
    private static volatile boolean enabled = true;
    private static Level level;
    private static long nextCheck, nextDiagnostic, sendPeriod = Long.MIN_VALUE;
    private static int sendBudget;
    private static long frameNanos, totalNanos, peakNanos, frames;

    private ModelHitClient() {}

    public static boolean enabled() { return enabled; }
    public static void captureCost(long nanos) { frameNanos += nanos; }

    // Вызывается с рабочих потоков. Снимок и его массивы неизменяемы; в очереди остаётся только последний.
    public static void offer(LivingEntity entity, ModelPoseLayout layout, VanillatePose.Data pose, double x, double y, double z, float yaw) {
        if (!enabled || READY.size() >= 512 && !READY.containsKey(entity.getUUID())) return;
        READY.put(entity.getUUID(), new Ready(entity, layout, pose, x, y, z, yaw));
        ModelHitLog.count("client", "flywheel_pose");
    }

    public static void frame() {
        totalNanos += frameNanos;
        peakNanos = Math.max(peakNanos, frameNanos);
        frames++;
        frameNanos = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) { clear(); level = mc.level; }
        if (level == null || mc.player == null || mc.getConnection() == null) return;
        long now = System.currentTimeMillis();
        if (now >= nextCheck) { enabled = ModelHits.enabled(); nextCheck = now + 1000; }
        if (!enabled) return;
        long started = System.nanoTime();
        NtmMeshHits.flush();
        for (var entry : READY.entrySet()) if (READY.remove(entry.getKey(), entry.getValue())) {
            Ready ready = entry.getValue();
            LivingEntity entity = ready.entity();
            if (!eligible(entity)) continue;
            var pose = ready.pose();
            Matrix4f root = new Matrix4f().translation((float) ready.x(), (float) ready.y(), (float) ready.z()).mul(pose.root());
            List<ModelBox> boxes = ready.layout().boxes(root, pose.transforms(), pose.draw(), pose.texture());
            publish(entity, ready.yaw(), boxes, "flywheel_snapshot");
        }
        sync();
        frameNanos += System.nanoTime() - started;
    }

    // Обычная отрисовка: модель уже прошла setupAnim. Забираем только основную модель, без брони и слоёв.
    public static void rendered(ModelFeatureRenderer.Submit<?> submit) {
        if (!enabled || !(submit.state() instanceof EntityRenderState state)
                || !(state instanceof ModelHitState info) || info.gunfeel$primary() != submit.model()
                || !(submit.model() instanceof EntityModel<?> model)) return;
        LivingEntity entity = info.gunfeel$target();
        if (entity == null || !eligible(entity)) return;
        long started = System.nanoTime();
        PoseStack pose = new PoseStack();
        pose.last().set(submit.pose());
        var camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        var result = ModelPoseCapture.collect(model.root(), pose, camera.x - state.x, camera.y - state.y,
                camera.z - state.z, ModelBodyGeometry.parts(model,info.gunfeel$texture()));
        float yaw = state instanceof LivingEntityRenderState living ? living.bodyRot
                : net.minecraft.util.Mth.rotLerp(state.partialTick, entity.yBodyRotO, entity.yBodyRot);
        if (result.complete()) publish(entity, yaw, result.boxes(), "vanilla_prepared");
        frameNanos += System.nanoTime() - started;
    }

    public static boolean eligible(LivingEntity entity) {
        var mc = Minecraft.getInstance();
        return entity.level() == level && entity.isAlive() && mc.player != null
                && entity.distanceToSqr(mc.player) <= ModelHits.RANGE * ModelHits.RANGE;
    }

    public static void publish(LivingEntity entity, float yaw, List<ModelBox> boxes, String source) {
        if (boxes.isEmpty() || boxes.size() > ModelPosePayload.MAX_BOXES
                || boxes.stream().anyMatch(b -> !b.valid(ModelHits.radius(entity)))) {
            ModelHitLog.event("client", "capture_rejected", entity, () -> "source=" + source + " cubes=" + boxes.size());
            return;
        }
        ModelHits.put(level, entity, yaw, boxes);
        MobSmoke.captured(entity, entity.yBodyRot, boxes, source);
        ModelHitLog.count("client", "local_pose");
        SYNC.put(entity.getUUID(), new Sync(entity, yaw, List.copyOf(boxes), source, level.getGameTime()));
    }

    private static void sync() {
        long tick = level.getGameTime();
        if (tick / ModelHits.INTERVAL != sendPeriod) {
            sendPeriod = tick / ModelHits.INTERVAL;
            sendBudget = ModelHits.MAX_UPDATES;
        }
        SYNC.entrySet().removeIf(e -> tick - e.getValue().tick() > ModelHits.MAX_SYNC_AGE || !eligible(e.getValue().entity()));
        if (sendBudget <= 0) return;
        // В толпе приоритет у давно не отправлявшихся сущностей, а не у первых в порядке рисования.
        var selected = SYNC.values().stream().filter(s -> tick - SENT.getOrDefault(s.entity().getUUID(), Long.MIN_VALUE / 2) >= ModelHits.INTERVAL)
                .sorted(java.util.Comparator.comparingLong(s -> SENT.getOrDefault(s.entity().getUUID(), Long.MIN_VALUE / 2)))
                .limit(sendBudget).toList();
        for (Sync pose : selected) {
            LivingEntity entity = pose.entity();
            for (ModelBox box : pose.boxes()) if (box.mesh() != 0 && MESH_SENT.add(box.mesh())) {
                ModelMesh mesh = ModelMesh.get(box.mesh());
                ClientPacketDistributor.sendToServer(new ModelMeshPayload(mesh.id(), mesh.triangles()));
            }
            sendBudget--;
            SENT.put(entity.getUUID(), tick);
            ClientPacketDistributor.sendToServer(new ModelPosePayload(entity.getId(), entity.getUUID(), entity.getX(), entity.getY(),
                    entity.getZ(), pose.yaw(), pose.boxes()));
            ModelHitLog.event("client", "sent", entity, () -> "source=" + pose.source() + " cubes=" + pose.boxes().size() + " tick=" + tick);
        }
        if (SENT.size() > 512) SENT.entrySet().removeIf(e -> tick - e.getValue() > 100);
    }

    public static void diagnostics() {
        if (level == null) return;
        long now = System.currentTimeMillis();
        if (now < nextDiagnostic) return;
        nextDiagnostic = now + 5000;
        org.slf4j.LoggerFactory.getLogger("ntm-gunfeel").info(
                "[modeldebug] client heartbeat enabled={} pending={} capture_ms/frame={} capture_peak_ms={} counters={} integrated_server={}",
                enabled, READY.size(), frames == 0 ? 0 : totalNanos / (frames * 1_000_000.0), peakNanos / 1_000_000.0,
                ModelHitLog.counts("client"), Minecraft.getInstance().getSingleplayerServer() == null ? "remote" : ModelHitLog.counts("server"));
        frames = totalNanos = peakNanos = 0;
    }

    public static void clear() {
        ModelBodyGeometry.clear();
        if (level != null) ModelHits.clear(level);
        READY.clear(); SENT.clear(); MESH_SENT.clear(); SYNC.clear(); ModelTextureMask.clear();
        NtmMeshHits.clear();
        level = null;
        nextCheck = 0;
        sendPeriod = Long.MIN_VALUE;
    }
}
