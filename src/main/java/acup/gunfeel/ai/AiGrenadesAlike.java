package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** The stands' pure answers, after the working call; no second budget charge. */
final class AiGrenadesAlike {
    enum Input { BURST, AIR, PATH, PLAN, STICK, SHARE, SHIELD, BOX, SAFE, COVER }

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
        return out.append("answers, ").append(differ).append(" differ").toString();
    }
}
