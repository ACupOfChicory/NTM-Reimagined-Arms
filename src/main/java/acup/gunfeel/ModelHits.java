package acup.gunfeel;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class ModelHits {
    public static final int RANGE = 96, MAX_UPDATES = 16, INTERVAL = 2, MAX_AGE = 5;
    private record PoseAt(UUID entity, Vec3 position, float yaw, Pose pose, float width, float height, long tick, List<ModelBox> boxes) {}
    private static final Map<Level, Map<Integer, PoseAt>> POSES = new WeakHashMap<>();
    private static final Map<ServerPlayer, long[]> LIMITS = new WeakHashMap<>();

    private ModelHits() {}

    public static boolean enabled() {
        return ReaimTimes.flag("modelHitboxes", true);
    }

    public static synchronized void receive(ServerPlayer observer, ModelPosePayload p) {
        ModelHitLog.count("server", "packet_received");
        if (!enabled()) {
            ModelHitLog.event("server", "disabled", observer, () -> "packet_entity=" + p.entity());
            return;
        }
        long tick = observer.level().getGameTime();
        long[] rate = LIMITS.computeIfAbsent(observer, k -> new long[] {tick / INTERVAL, 0});
        if (rate[0] != tick / INTERVAL) { rate[0] = tick / INTERVAL; rate[1] = 0; }
        if (++rate[1] > MAX_UPDATES * 2) {
            ModelHitLog.event("server", "rate_rejected", observer, () -> "packets=" + rate[1] + " tick=" + tick);
            return;
        }
        Entity target = observer.level().getEntity(p.entity());
        if (!(target instanceof LivingEntity living) || !target.isAlive() || !target.getUUID().equals(p.uuid())
                || observer.distanceToSqr(target) > RANGE * RANGE || !Double.isFinite(p.x()) || !Double.isFinite(p.y())
                || !Double.isFinite(p.z()) || !Float.isFinite(p.yaw()) || target.position().distanceToSqr(new Vec3(p.x(), p.y(), p.z())) > 16
                || p.boxes().isEmpty() || p.boxes().size() > ModelPosePayload.MAX_BOXES) {
            ModelHitLog.event("server", "target_rejected", target, () -> "observer=" + observer.getId()
                    + " packet_entity=" + p.entity() + " packet_uuid=" + p.uuid()
                    + " target_uuid=" + (target == null ? null : target.getUUID())
                    + " alive=" + (target != null && target.isAlive())
                    + " distance2=" + (target == null ? -1 : observer.distanceToSqr(target))
                    + " packet_pos=" + p.x() + "," + p.y() + "," + p.z()
                    + " target_pos=" + (target == null ? null : target.position()) + " yaw=" + p.yaw() + " cubes=" + p.boxes().size());
            return;
        }
        double radius = radius(target);
        if (p.boxes().stream().anyMatch(b -> !b.valid(radius))) {
            ModelHitLog.event("server", "geometry_rejected", target, () -> "radius=" + radius + " invalid_cube="
                    + p.boxes().stream().filter(b -> !b.valid(radius)).findFirst().orElse(null));
            return;
        }
        put(observer.level(), living, p.yaw(), p.boxes());
        ModelHitLog.event("server", "accepted", living, () -> "observer=" + observer.getId() + " cubes=" + p.boxes().size()
                + " tick=" + tick + " first_cube=" + p.boxes().getFirst());
    }

    public static double radius(Entity target) {
        // Also bounds candidate lookup: no accepted cube extends beyond this distance from the entity origin.
        return Math.min(16, Math.max(2, Math.max(target.getBbWidth(), target.getBbHeight()) * 2));
    }

    public static synchronized void put(Level level, LivingEntity target, float yaw, List<ModelBox> boxes) {
        Map<Integer, PoseAt> poses = POSES.computeIfAbsent(level, k -> new HashMap<>());
        long now = level.getGameTime();
        poses.entrySet().removeIf(e -> now - e.getValue().tick() > MAX_AGE);
        poses.put(target.getId(), new PoseAt(target.getUUID(), target.position(), yaw, target.getPose(),
                target.getBbWidth(), target.getBbHeight(), now, List.copyOf(boxes)));
    }

    public static synchronized void clear(Level level) {
        POSES.remove(level);
    }

    public static synchronized List<Entity> candidates(Level level, Entity except, List<Entity> original, AABB area,
            java.util.function.Predicate<Entity> filter) {
        Map<Integer, PoseAt> poses = POSES.get(level);
        if (poses == null || !enabled()) return original;
        List<Entity> out = new ArrayList<>(original);
        for (var entry : poses.entrySet()) {
            if (level.getGameTime() - entry.getValue().tick() > MAX_AGE) continue;
            Entity target = level.getEntity(entry.getKey());
            if (target != null && target != except && filter.test(target) && target.getUUID().equals(entry.getValue().entity()) && !out.contains(target)
                    && target.getBoundingBox().inflate(radius(target)).intersects(area)) out.add(target);
        }
        return out;
    }

    // No data, expired pose, teleport, changed size/posture: keep the original hitbox. An available model missed by the
    // ray is a real miss, never replaced with a hit on the enclosing entity AABB.
    public static synchronized Optional<Vec3> clip(Entity target, Vec3 from, Vec3 to, AABB fallback) {
        Map<Integer, PoseAt> poses = POSES.get(target.level());
        PoseAt p = poses == null ? null : poses.get(target.getId());
        boolean fresh = enabled() && p != null && p.entity().equals(target.getUUID()) && target.level().getGameTime() - p.tick() <= MAX_AGE
                && target.position().distanceToSqr(p.position()) <= 16 && p.pose() == target.getPose()
                && p.width() == target.getBbWidth() && p.height() == target.getBbHeight();
        if (!fresh || !(target instanceof LivingEntity living)) {
            if (!from.equals(to)) ModelHitLog.event(target.level().isClientSide() ? "client" : "server", "clip_fallback", target,
                    () -> "enabled=" + enabled() + " has_pose=" + (p != null) + " age=" + (p == null ? -1 : target.level().getGameTime() - p.tick())
                            + " uuid_matches=" + (p != null && p.entity().equals(target.getUUID()))
                            + " stored_pose=" + (p == null ? null : p.pose()) + " current_pose=" + target.getPose()
                            + " stored_size=" + (p == null ? "none" : p.width() + "," + p.height())
                            + " current_size=" + target.getBbWidth() + "," + target.getBbHeight()
                            + " displacement2=" + (p == null ? -1 : target.position().distanceToSqr(p.position()))
                            + " from=" + from + " to=" + to);
            return fallback.contains(from) ? Optional.of(from) : fallback.clip(from, to);
        }
        double angle = Math.toRadians(Mth.wrapDegrees(living.yBodyRot - p.yaw()));
        // A large turn invalidates the snapshot; the next rendered pose supplies head and limb rotations again.
        if (Math.abs(angle) > Math.PI / 3) {
            if (!from.equals(to)) ModelHitLog.event(target.level().isClientSide() ? "client" : "server", "turn_fallback", target,
                    () -> "stored_yaw=" + p.yaw() + " current_yaw=" + living.yBodyRot);
            return fallback.contains(from) ? Optional.of(from) : fallback.clip(from, to);
        }
        Vec3 a = unturn(from.subtract(target.position()), angle), b = unturn(to.subtract(target.position()), angle);
        double nearest = Double.POSITIVE_INFINITY;
        String part = "";
        for (ModelBox box : p.boxes()) {
            double t = box.clip(a.x, a.y, a.z, b.x, b.y, b.z);
            if (t >= 0 && t < nearest) { nearest = t; part = box.part(); }
        }
        if (!from.equals(to)) {
            final double at = nearest;
            final String name = part;
            ModelHitLog.event(target.level().isClientSide() ? "client" : "server", "clip_model", target,
                    () -> "result=" + (Double.isFinite(at) ? "hit" : "miss") + " part=" + name + " t=" + at
                            + " cubes=" + p.boxes().size() + " age=" + (target.level().getGameTime() - p.tick())
                            + " from=" + from + " to=" + to);
        }
        if (TestLog.on(target.level()) && !from.equals(to)) TestLog.at(target.level(), -1, "model_hit", "target", target.getId(),
                "part", part, "result", Double.isFinite(nearest) ? "hit" : "miss", "age", target.level().getGameTime() - p.tick());
        return Double.isFinite(nearest) ? Optional.of(from.lerp(to, nearest)) : Optional.empty();
    }

    private static Vec3 unturn(Vec3 p, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        return new Vec3(cos * p.x + sin * p.z, p.y, -sin * p.x + cos * p.z);
    }
}
