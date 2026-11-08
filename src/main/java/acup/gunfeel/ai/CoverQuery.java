package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;

/**
 * Where to take cover from a threat, as the shooters' position queries do it (Crysis, Unreal): cells of the tactical
 * map round the mob are generated, the cheap tests throw most out (no cover towards the threat, too near it or too
 * far, held by another mob), the rest are scored (high cover over low, cover that stops bullets over cover that only
 * hides, near the mob, at a fighting distance from the threat, the place it is already going to), and only then, best
 * first and within a budget, rays decide: the threat must not see the mob's head in the cell (its legs, behind low
 * cover, which it shoots over), and a cell beside high cover, across the line to the threat, from which the threat
 * can be seen is where it steps out to shoot (its peek).
 */
public final class CoverQuery {
    private CoverQuery() {
    }

    public static final class Params {
        /** how far from the mob to look, blocks */
        public double radius = 16;
        /** no nearer the threat than this, nor further than that */
        public double minFromThreat = 5, maxFromThreat = 64;
        /** the distance it would rather fight at */
        public double fightAt = 20;
        /** only cover that stops bullets */
        public boolean hardOnly;
        /** only a cell with a place beside it to shoot from */
        public boolean needPeek;
        /** rays it may spend */
        public int rayBudget = 32;
        /** the cell it is going to already, kept unless something is clearly better (against dithering) */
        public long current = Long.MIN_VALUE;
        /** the cover cells and peeks its own side holds: no cover within APART of theirs, no peek of theirs */
        public long[] friendCovers = {}, friendPeeks = {};
        /** what a place to shoot from adds (more in a fight) */
        public double peekBonus = PEEK;
        /** threats after the first: each that sees its head costs SEEN_BY, instead of ruling the cell out */
        public boolean othersSoft;
        /** a flank (AiSquads): the squad's middle; seen from the threat, cells within 45 degrees of it or past 135 are no flank */
        public @org.jspecify.annotations.Nullable Vec3 flankFrom;
    }

    /** peek: the best place beside it to shoot from (the cell itself over low cover); peeks: every such place, up to MAX_PEEKS */
    public record Spot(long cell, double score, int height, boolean hard, long peek, long[] peeks) {
        public Spot(long cell, double score, int height, boolean hard, long peek) {
            this(cell, score, height, hard, peek, peek == Long.MIN_VALUE ? new long[0] : new long[] {peek});
        }

        public boolean hasPeek() {
            return peek != Long.MIN_VALUE;
        }
    }

    /** places beside high cover to shoot from, at most */
    static final int MAX_PEEKS = 3;
    /** the spots that pass the rays before the best of them is taken */
    static final int POOL = 4;

    // the weights
    static final double HIGH = 4, LOW = 1.5, HARD = 2, PER_BLOCK = 0.25, OFF_RANGE = 0.05, CURRENT = 2, PEEK = 1.5, PEEK_FIGHT = 4;
    /** no nearer a friend's cover than this, blocks across */
    static final double APART = 3;
    /** a threat besides the one it fights that sees its head there (othersSoft) */
    static final double SEEN_BY = 2.5;
    /** a place to shoot from: the target seen from it and no bullet-stopping block between (glass that stops nothing aside) */
    static final int SHOOT_MASK = Voxel.SIGHT | Voxel.PROTECT_MASK;
    /** the most kept to be tried with rays */
    static final int KEEP = 48;

    // for /armsai info
    static long runs, rays, nanos;

    /**
     * best spots first, at most n. self: where the mob's feet are; threats: the eyes it must hide from, the first one
     * is the one it fights.
     */
    public static List<Spot> find(CellSource map, VoxelRay.Source voxels, Vec3 self, List<Vec3> threats, Params p, LongPredicate held, int n) {
        long t0 = System.nanoTime();
        runs++;
        List<Spot> out = new ArrayList<>();
        if (threats.isEmpty()) return out;
        Vec3 main = threats.get(0);
        long[] cand = new long[KEEP];
        double[] score = new double[KEEP];
        int[] info = new int[KEEP];
        int count = 0;
        double r2 = p.radius * p.radius;
        int x0 = (int) Math.floor(self.x - p.radius), x1 = (int) Math.floor(self.x + p.radius);
        int z0 = (int) Math.floor(self.z - p.radius), z1 = (int) Math.floor(self.z + p.radius);
        int y0 = (int) Math.floor(self.y) - 4, y1 = (int) Math.floor(self.y) + 4;
        for (int sx = x0 >> 4; sx <= x1 >> 4; sx++)
            for (int sz = z0 >> 4; sz <= z1 >> 4; sz++)
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    SectionCells c = map.cells(SectionPos.asLong(sx, sy, sz));
                    if (c == null) continue;
                    for (int i = 0; i < c.count(); i++) {
                        int x = (sx << 4) + c.x(i), y = (sy << 4) + c.y(i), z = (sz << 4) + c.z(i);
                        if (y < y0 || y > y1) continue;
                        double cx = x + 0.5, cz = z + 0.5;
                        double dx = cx - self.x, dz = cz - self.z, d2 = dx * dx + dz * dz;
                        if (d2 > r2) continue;
                        double tx = main.x - cx, tz = main.z - cz, td = Math.sqrt(tx * tx + tz * tz);
                        if (td < p.minFromThreat || td > p.maxFromThreat) continue;
                        if (p.flankFrom != null) {
                            double fc = AiSquads.cos(cx - main.x, cz - main.z, p.flankFrom.x - main.x, p.flankFrom.z - main.z);
                            if (fc > AiSquads.FLANK_COS || fc < AiSquads.BEHIND_COS) continue;
                        }
                        int dir = Dirs.of(tx, tz);
                        int cover = c.cover(i);
                        int height = SectionCells.height(cover, dir);
                        if (height == 0) continue;
                        boolean hard = SectionCells.hard(cover, dir);
                        if (p.hardOnly && !hard) continue;
                        long key = BlockPos.asLong(x, y, z);
                        if (held.test(key) && key != p.current) continue;
                        if (key != p.current && nearAny(p.friendCovers, x, z, APART)) continue;
                        double s = (height == 2 ? HIGH : LOW) + (hard ? HARD : 0) - Math.sqrt(d2) * PER_BLOCK
                                - Math.abs(td - p.fightAt) * OFF_RANGE + (key == p.current ? CURRENT : 0);
                        int worst = -1;
                        if (count < KEEP) worst = count++;
                        else {
                            worst = 0;
                            for (int k = 1; k < KEEP; k++) if (score[k] < score[worst]) worst = k;
                            if (score[worst] >= s) worst = -1;
                        }
                        if (worst >= 0) {
                            cand[worst] = key;
                            score[worst] = s;
                            info[worst] = height | (hard ? 4 : 0) | dir << 3;
                        }
                    }
                }
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(score[b], score[a]));
        int budget = p.rayBudget;
        // a few that pass, not the first: a place to shoot from and the other threats change their order
        int pool = Math.max(n, POOL);
        for (int k = 0; k < count && out.size() < pool && budget > 0; k++) {
            int i = order[k];
            long key = cand[i];
            int x = BlockPos.getX(key), y = BlockPos.getY(key), z = BlockPos.getZ(key);
            int height = info[i] & 3, dir = info[i] >> 3;
            // hidden from every threat: its head behind high cover, its legs behind low cover (it shoots over that)
            double look = height == 2 ? 1.6 : 0.5;
            boolean hidden = true;
            double seenBy = 0;
            for (int ti = 0; ti < threats.size(); ti++) {
                Vec3 t = threats.get(ti);
                if (budget-- <= 0) break;
                rays++;
                if (VoxelRay.clear(voxels, t.x, t.y, t.z, x + 0.5, y + look, z + 0.5, Voxel.SIGHT)) {
                    // the one it fights must not see it; the others, in a fight, cost it
                    if (ti > 0 && p.othersSoft) seenBy += SEEN_BY;
                    else {
                        hidden = false;
                        break;
                    }
                }
            }
            if (!hidden || budget < 0) continue;
            boolean hard = (info[i] & 4) != 0;
            long peek = Long.MIN_VALUE;
            long[] peeks = new long[MAX_PEEKS];
            int np = 0;
            if (height == 2) {
                // the cells beside it across the line to the threat, one aside each way, then two aside
                int[] sides = {(dir + 2) & 7, (dir + 6) & 7};
                // the cell one aside sees the threat (a friend's peek or not), per side
                boolean[] sees = new boolean[2];
                for (int far = 1; far <= 2 && np < MAX_PEEKS; far++)
                    for (int w = 0; w < 2; w++) {
                        if (np >= MAX_PEEKS) break;
                        // two aside only where one aside sees round the cover already (a step further out)
                        if (far == 2 && !sees[w]) continue;
                        int side = sides[w], px = x + Dirs.DX[side] * far, pz = z + Dirs.DZ[side] * far;
                        if (!open(map, px, y, pz) || budget-- <= 0) continue;
                        rays++;
                        if (!VoxelRay.clear(voxels, main.x, main.y, main.z, px + 0.5, y + 1.6, pz + 0.5, SHOOT_MASK)) continue;
                        if (far == 1) sees[w] = true;
                        long pk = BlockPos.asLong(px, y, pz);
                        if (!contains(p.friendPeeks, pk) && !contains(p.friendCovers, pk) && !held.test(pk)) peeks[np++] = pk;
                    }
                if (np > 0) peek = peeks[0];
            } else if (budget-- > 0) {
                // low cover: it shoots over it, standing where it is, if the target is seen over it and nothing
                // there stops a bullet (else the cell hides it, and that is all)
                rays++;
                if (VoxelRay.clear(voxels, main.x, main.y, main.z, x + 0.5, y + 1.6, z + 0.5, SHOOT_MASK)) {
                    peek = key;
                    peeks[np++] = key;
                }
            }
            if (p.needPeek && peek == Long.MIN_VALUE) continue;
            out.add(new Spot(key, score[i] + (peek != Long.MIN_VALUE ? p.peekBonus : 0) - seenBy, height, hard, peek, java.util.Arrays.copyOf(peeks, np)));
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        if (out.size() > n) out.subList(n, out.size()).clear();
        nanos += System.nanoTime() - t0;
        return out;
    }

    private static boolean open(CellSource map, int x, int y, int z) {
        SectionCells c = map.cells(SectionPos.asLong(x >> 4, y >> 4, z >> 4));
        return c != null && c.find(x & 15, y & 15, z & 15) >= 0;
    }

    private static boolean nearAny(long[] cells, int x, int z, double d) {
        for (long c : cells) {
            double dx = BlockPos.getX(c) - x, dz = BlockPos.getZ(c) - z;
            if (dx * dx + dz * dz < d * d) return true;
        }
        return false;
    }

    private static boolean contains(long[] cells, long k) {
        for (long c : cells) if (c == k) return true;
        return false;
    }

    static String info() {
        return String.format(java.util.Locale.ROOT, "cover queries %d, %d rays, %.0f us each", runs, rays, runs == 0 ? 0 : nanos / 1000.0 / runs);
    }
}
