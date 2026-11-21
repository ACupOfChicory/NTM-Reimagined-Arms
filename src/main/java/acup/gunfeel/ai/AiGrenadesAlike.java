package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** The stands' pure answers, after the working call; no second budget charge. */
final class AiGrenadesAlike implements AiGrenadesBase.BurstObserver {
    enum Input { BURST, AIR, PATH, PLAN, STICK, SHARE, SHIELD, BOX, SAFE, COVER }
    enum LiveInput { BOUNCY, MK4, UNIVERSAL, OTHER, S3, S7, S15, IMPACT, UNKNOWN, GLUE, EXPIRED, NO_CONFIG,
        BELOW, PERIOD4, PERIOD1, PREDICTED, PARTED, BURSTING, ANY, IDENTITY }
    enum Event { JOIN, DUP, REMOVE, EMPTY, FORGET, AT, BURST, PARTED, TRACED, PATH }

    final long[] liveCovered = new long[LiveInput.values().length];
    final long[] events = new long[Event.values().length];
    private final List<String> actual = new ArrayList<>(), expected = new ArrayList<>();

    private void liveCompared(LiveInput input, boolean same) {
        liveCovered[input.ordinal()]++;
        if (!same) differ++;
    }

    boolean liveComplete() {
        for (long n : liveCovered) if (n == 0) return false;
        for (long n : events) if (n == 0) return false;
        return true;
    }

    void close() {
        AiGrenadesBase.journal = null;
        AiGrenadesOld.journal = null;
        AiGrenadesOld.LEVELS.clear();
        AiGrenadesOld.partings.clear();
        actual.clear();
        expected.clear();
    }

    /** Two independent Live lists; entities and immutable vectors are inputs, never written by the replay. */
    private void snapshot(ServerLevel level) {
        List<AiGrenadesBase.Live> source = AiGrenadesBase.LEVELS.get(level);
        AiGrenadesOld.LEVELS.remove(level);
        if (source == null) return;
        List<AiGrenadesOld.Live> copy = new ArrayList<>();
        for (AiGrenadesBase.Live live : source) {
            AiGrenadesOld.Live old = new AiGrenadesOld.Live((net.minecraft.world.entity.projectile.Projectile) acup.gunfeel.EntityBody.entity(live.e));
            old.at = live.at;
            old.burst = (Vec3) live.burstRef;
            old.known.addAll(live.known);
            old.path = live.path == null ? null : copy(live.path);
            old.start = live.start;
            old.parted = live.parted;
            copy.add(old);
        }
        AiGrenadesOld.LEVELS.put(level, copy);
    }

    void before(ServerLevel level) {
        snapshot(level);
        actual.clear();
        expected.clear();
        AiGrenadesBase.journal = actual::add;
        AiGrenadesOld.journal = expected::add;
        AiGrenadesOld.trace = AiGrenadesBase.trace;
        AiGrenadesOld.traced = AiGrenadesBase.traced;
        AiGrenadesOld.partings.clear();
    }

    private void finished(ServerLevel level) {
        try {
            if (!actual.equals(expected)) differ++;
            for (String event : actual) events[Event.valueOf(event.split(" ", 2)[0]).ordinal()]++;
            List<AiGrenadesBase.Live> now = AiGrenadesBase.LEVELS.get(level);
            List<AiGrenadesOld.Live> old = AiGrenadesOld.LEVELS.get(level);
            boolean same = now == null || old == null ? now == null && old == null : now.size() == old.size();
            if (same && now != null) for (int i = 0; i < now.size(); i++) {
                AiGrenadesBase.Live a = now.get(i);
                AiGrenadesOld.Live b = old.get(i);
                same &= acup.gunfeel.EntityBody.entity(a.e) == b.e && a.at == b.at && vector((Vec3) a.burstRef, b.burst)
                        && a.known.equals(b.known) && java.util.Objects.equals(a.start, b.start) && java.util.Objects.equals(a.parted, b.parted)
                        && (a.path == null || b.path == null ? a.path == null && b.path == null : path(a.path, b.path));
                same &= a.burst == null ? a.burstRef == null : a.burstRef != null && bits(a.burst.x(), ((Vec3) a.burstRef).x)
                        && bits(a.burst.y(), ((Vec3) a.burstRef).y) && bits(a.burst.z(), ((Vec3) a.burstRef).z);
                if (a.burstRef != null && b.burst != null)
                    same &= (a.burstRef == b.e.position()) == (b.burst == b.e.position());
                for (int j = 0; j < now.size(); j++)
                    same &= (a.burstRef == now.get(j).burstRef) == (b.burst == old.get(j).burst);
            }
            same &= AiGrenadesBase.traced == AiGrenadesOld.traced;
            List<String> appended = actual.stream().filter(s -> s.startsWith("PARTED ")).map(s -> s.substring(7)).toList();
            same &= appended.size() == AiGrenadesOld.partings.size();
            liveCompared(LiveInput.IDENTITY, same);
        } finally {
            AiGrenadesOld.LEVELS.remove(level);
            AiGrenadesBase.journal = null;
            AiGrenadesOld.journal = null;
            AiGrenadesOld.partings.clear();
            actual.clear();
            expected.clear();
        }
    }

    void joined(ServerLevel level, net.minecraft.world.entity.projectile.Projectile g) {
        AiGrenadesOld.joined(level, g);
        finished(level);
    }

    void forgot(ServerLevel level) {
        AiGrenadesOld.forget(level);
        finished(level);
    }

    void ticked(ServerLevel level) {
        List<AiGrenadesOld.Live> before = AiGrenadesOld.LEVELS.get(level);
        if (before != null) for (AiGrenadesOld.Live g : before) {
            if (g.e.isRemoved()) continue;
            boolean mk4 = g.e instanceof com.hbm.entity.projectile.EntityBulletBaseMK4;
            if (g.at == Long.MIN_VALUE || level.getGameTime() - g.at >= (mk4 ? 1 : 4)) {
                liveCovered[(mk4 ? LiveInput.PERIOD1 : LiveInput.PERIOD4).ordinal()]++;
                flightInput(g.e);
            }
        }
        AiGrenadesOld.tick(level);
        finished(level);
    }

    private void flightInput(net.minecraft.world.entity.projectile.Projectile p) {
        LiveInput kind = p instanceof com.hbm.entity.grenade.EntityGrenadeBouncyGeneric ? LiveInput.BOUNCY
                : p instanceof com.hbm.entity.projectile.EntityBulletBaseMK4 ? LiveInput.MK4
                : p instanceof com.hbm.entity.grenade.EntityGrenadeUniversal ? LiveInput.UNIVERSAL : LiveInput.OTHER;
        liveCovered[kind.ordinal()]++;
        if (p.getY() < p.level().getMinY()) liveCovered[LiveInput.BELOW.ordinal()]++;
        if (p instanceof com.hbm.entity.grenade.EntityGrenadeBouncyGeneric b) {
            liveCompared(LiveInput.BOUNCY, vector(new Vec3(b.getX(), b.getY(), b.getZ()), b.position()));
            if (b.getGrenade().getMaxTimer() - b.tickCount <= 0) liveCovered[LiveInput.EXPIRED.ordinal()]++;
        }
        if (p instanceof com.hbm.entity.projectile.EntityBulletBaseMK4 b) {
            if (b.config == null) liveCovered[LiveInput.NO_CONFIG.ordinal()]++;
            else if (b.config.expires > 0 && b.config.expires - b.tickCount <= 0) liveCovered[LiveInput.EXPIRED.ordinal()]++;
        }
        if (p instanceof com.hbm.entity.grenade.EntityGrenadeUniversal e) {
            liveCovered[switch (e.getFuze()) {
                case S3 -> LiveInput.S3.ordinal();
                case S7 -> LiveInput.S7.ordinal();
                case S15 -> LiveInput.S15.ordinal();
                case IMPACT -> LiveInput.IMPACT.ordinal();
                default -> LiveInput.UNKNOWN.ordinal();
            }]++;
            if (e.getExtra() == com.hbm.items.weapon.grenade.ItemGrenadeExtra.EnumGrenadeExtra.GLUE) liveCovered[LiveInput.GLUE.ordinal()]++;
            if (e.getFuze() != com.hbm.items.weapon.grenade.ItemGrenadeFuze.EnumGrenadeFuze.IMPACT && e.getTimer() >= 300)
                liveCovered[LiveInput.EXPIRED.ordinal()]++;
        }
    }

    void tossed(ServerLevel level, Mob mob, com.hbm.entity.grenade.EntityGrenadeUniversal g, AiGrenades.Throw t) {
        AiGrenadesOld.replayToss(level, mob, g, new AiGrenadesOld.Throw(t.yaw(), t.pitch(), t.burst(), t.share()));
        finished(level);
    }

    @Nullable Vec3 burstOf(ServerLevel level, net.minecraft.world.entity.projectile.Projectile p, @Nullable Vec3 answer) {
        Vec3 old = AiGrenadesOld.burstOf(level, p);
        flightInput(p);
        liveCompared(LiveInput.IDENTITY, vector(answer, old) && (answer == p.position()) == (old == p.position()));
        return answer;
    }

    @Nullable Vec3 predicted(ServerLevel level, net.minecraft.world.entity.Entity e, @Nullable Vec3 answer) {
        snapshot(level);
        try { liveCompared(LiveInput.PREDICTED, answer == AiGrenadesOld.predicted(level, e)); }
        finally { AiGrenadesOld.LEVELS.remove(level); }
        return answer;
    }

    @Nullable String parted(ServerLevel level, net.minecraft.world.entity.Entity owner, @Nullable String answer) {
        snapshot(level);
        try { liveCompared(LiveInput.PARTED, java.util.Objects.equals(answer, AiGrenadesOld.parted(level, owner))); }
        finally { AiGrenadesOld.LEVELS.remove(level); }
        return answer;
    }

    Vec3 @Nullable [] bursting(ServerLevel level, net.minecraft.world.entity.Entity owner, Vec3 @Nullable [] answer) {
        snapshot(level);
        try {
            Vec3[] old = AiGrenadesOld.bursting(level, owner);
            liveCompared(LiveInput.BURSTING, old == null || answer == null ? old == answer : old[0] == answer[0] && old[1] == answer[1]);
        } finally { AiGrenadesOld.LEVELS.remove(level); }
        return answer;
    }

    boolean any(ServerLevel level, boolean answer) {
        snapshot(level);
        try { liveCompared(LiveInput.ANY, answer == AiGrenadesOld.any(level)); }
        finally { AiGrenadesOld.LEVELS.remove(level); }
        return answer;
    }

    final long[] covered = new long[Input.values().length];
    long differ;

    private void compared(Input input, boolean same) {
        covered[input.ordinal()]++;
        if (!same) differ++;
    }

    boolean complete() {
        for (long n : covered) if (n == 0) return false;
        return true;
    }

    boolean agrees() {
        if (differ != 0) return false;
        for (long n : covered) if (n > 0) return true;
        return false;
    }

    static boolean needsEveryInput(List<String> stages) {
        return stages.isEmpty() || stages.contains("grenade") && stages.contains("grenadier");
    }

    static boolean bits(double a, double b) { return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b); }
    static boolean bits(float a, float b) { return Float.floatToRawIntBits(a) == Float.floatToRawIntBits(b); }

    static boolean vector(@Nullable Vec3 a, @Nullable Vec3 b) {
        return a == null || b == null ? a == b : bits(a.x, b.x) && bits(a.y, b.y) && bits(a.z, b.z);
    }

    private static boolean array(double[] a, double[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (!bits(a[i], b[i])) return false;
        return true;
    }

    static List<double[]> copy(List<double[]> path) {
        List<double[]> out = new ArrayList<>();
        for (double[] p : path) out.add(p.clone());
        return out;
    }

    private static boolean path(List<double[]> a, List<double[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!array(a.get(i), b.get(i))) return false;
        return true;
    }

    @Override public void burst(VoxelRay.Source src, double x, double y, double z, double vx, double vy, double vz,
            int ticks, int armed, double bounce, int minY, double gravity, double drag, @Nullable List<double[]> path,
            double @Nullable [] land, acup.gunfeel.@Nullable Vec3d answer, int input) {
        burst(src, x, y, z, vx, vy, vz, ticks, armed, bounce, minY, gravity, drag, path, land, null,
                answer == null ? null : new Vec3(answer.x(), answer.y(), answer.z()), input);
    }

    @Nullable Vec3 burst(VoxelRay.Source src, double x, double y, double z, double vx, double vy, double vz,
            int ticks, int armed, double bounce, int minY, double gravity, double drag, @Nullable List<double[]> path,
            double @Nullable [] land, @Nullable List<double[]> before, @Nullable Vec3 answer, int input) {
        double[] oldLand = land == null ? null : new double[land.length];
        Vec3 old = switch (input) {
            case 0 -> AiGrenadesOld.burst(src, x, y, z, vx, vy, vz, ticks, armed, bounce, minY);
            case 1 -> AiGrenadesOld.burst(src, x, y, z, vx, vy, vz, ticks, armed, bounce, minY, gravity, drag);
            default -> AiGrenadesOld.burst(src, x, y, z, vx, vy, vz, ticks, armed, bounce, minY, gravity, drag, before, oldLand);
        };
        compared(Input.values()[input], vector(answer, old) && (land == null || array(land, oldLand))
                && (path == null || path(path, before)));
        return answer;
    }

    static boolean throwing(AiGrenades.@Nullable Throw a, AiGrenadesOld.@Nullable Throw b) {
        return a == null || b == null ? a == null && b == null
                : bits(a.yaw(), b.yaw()) && bits(a.pitch(), b.pitch()) && bits(a.share(), b.share()) && vector(a.burst(), b.burst());
    }

    AiGrenades.@Nullable Throw planNow(ServerLevel level, Mob mob, Vec3 at, boolean smoke, double down, double force,
            AiGrenades.@Nullable Throw answer) {
        double far = Math.hypot(at.x - mob.getX(), at.z - mob.getZ());
        share(far, AiGrenadesBase.share(far));
        planned(answer, AiGrenadesOld.plan(level, mob, at, smoke, down, force));
        return answer;
    }

    void planned(AiGrenades.@Nullable Throw answer, AiGrenadesOld.@Nullable Throw old) {
        compared(Input.PLAN, throwing(answer, old));
    }

    /** Records the working predicate once; the former code replays those same answers. */
    static final class Trials implements Predicate<Vec3> {
        private final Predicate<Vec3> safe;
        final List<Vec3> points = new ArrayList<>();
        final List<Boolean> answers = new ArrayList<>();
        int read;
        boolean same = true;

        Trials(Predicate<Vec3> safe) { this.safe = safe; }

        @Override public boolean test(Vec3 p) {
            boolean answer = safe.test(p);
            points.add(p);
            answers.add(answer);
            return answer;
        }

        boolean replay(Vec3 p) {
            int i = read++;
            if (i >= points.size()) {
                same = false;
                return false;
            }
            same &= vector(p, points.get(i));
            return answers.get(i);
        }
    }

    AiGrenades.@Nullable Throw planStick(ServerLevel level, double x, double eye, double z, Vec3 at, Trials safe,
            AiGrenades.@Nullable Throw answer) {
        double far = Math.hypot(at.x - x, at.z - z);
        share(far, AiGrenadesBase.share(far));
        AiGrenadesOld.Throw old = AiGrenadesOld.planStick(level, x, eye, z, at, safe::replay);
        stuck(answer, old, safe);
        return answer;
    }

    void stuck(AiGrenades.@Nullable Throw answer, AiGrenadesOld.@Nullable Throw old, Trials safe) {
        compared(Input.STICK, throwing(answer, old) && safe.same && safe.read == safe.points.size());
    }

    double share(double far, double answer) {
        compared(Input.SHARE, bits(answer, AiGrenadesOld.share(far)));
        return answer;
    }

    boolean shielded(VoxelRay.Source src, double x, double y, double z, double height, double bx, double by, double bz, boolean lying, boolean answer,
            boolean explicit) {
        Vec3 burst = new Vec3(bx, by, bz);
        boolean old = explicit ? AiGrenadesOld.shielded(src, x, y, z, height, burst, lying)
                : AiGrenadesOld.shielded(src, x, y, z, height, burst);
        compared(explicit ? Input.BOX : Input.SHIELD, answer == old);
        return answer;
    }

    boolean safe(VoxelRay.Source src, Vec3 burst, long cell, double clear, boolean own, boolean answer) {
        compared(Input.SAFE, answer == AiGrenadesOld.safe(src, burst, cell, clear, own));
        return answer;
    }

    boolean cover(VoxelRay.Source src, Vec3 burst, double x, double y, double z, boolean own, boolean answer) {
        compared(Input.COVER, answer == AiGrenadesOld.cover(src, burst, x, y, z, own));
        return answer;
    }

    String info() {
        StringBuilder out = new StringBuilder();
        for (Input input : Input.values()) out.append(input.name().toLowerCase(java.util.Locale.ROOT)).append('=')
                .append(covered[input.ordinal()]).append(' ');
        for (LiveInput input : LiveInput.values()) out.append("live_").append(input.name().toLowerCase(java.util.Locale.ROOT)).append('=')
                .append(liveCovered[input.ordinal()]).append(' ');
        for (Event event : Event.values()) out.append(event.name()).append('=').append(events[event.ordinal()]).append(' ');
        return out.append("answers, ").append(differ).append(" differ").toString();
    }
}
