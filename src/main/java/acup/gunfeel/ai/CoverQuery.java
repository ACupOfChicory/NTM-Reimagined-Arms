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
    }

    public record Spot(long cell, double score, int height, boolean hard, long peek) {
        public boolean hasPeek() {
            return peek != Long.MIN_VALUE;
        }
    }

    // the weights
    static final double HIGH = 4, LOW = 1.5, HARD = 2, PER_BLOCK = 0.25, OFF_RANGE = 0.05, CURRENT = 2, PEEK = 1.5;
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
                        int dir = Dirs.of(tx, tz);
                        int cover = c.cover(i);
                        int height = SectionCells.height(cover, dir);
                        if (height == 0) continue;
                        boolean hard = SectionCells.hard(cover, dir);
                        if (p.hardOnly && !hard) continue;
                        long key = BlockPos.asLong(x, y, z);
                        if (held.test(key) && key != p.current) continue;
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
        for (int k = 0; k < count && out.size() < n && budget > 0; k++) {
            int i = order[k];
            long key = cand[i];
            int x = BlockPos.getX(key), y = BlockPos.getY(key), z = BlockPos.getZ(key);
            int height = info[i] & 3, dir = info[i] >> 3;
            // hidden from every threat: its head behind high cover, its legs behind low cover (it shoots over that)
            double look = height == 2 ? 1.6 : 0.5;
            boolean hidden = true;
            for (Vec3 t : threats) {
                if (budget-- <= 0) break;
                rays++;
                if (VoxelRay.clear(voxels, t.x, t.y, t.z, x + 0.5, y + look, z + 0.5, Voxel.SIGHT)) {
                    hidden = false;
                    break;
                }
            }
            if (!hidden || budget < 0) continue;
            boolean hard = (info[i] & 4) != 0;
            long peek = Long.MIN_VALUE;
            if (height == 2) {
                // the two cells beside it across the line to the threat
                for (int side : new int[] {(dir + 2) & 7, (dir + 6) & 7}) {
                    int px = x + Dirs.DX[side], pz = z + Dirs.DZ[side];
                    SectionCells pc = map.cells(SectionPos.asLong(px >> 4, y >> 4, pz >> 4));
                    if (pc == null || pc.find(px & 15, y & 15, pz & 15) < 0 || budget-- <= 0) continue;
                    rays++;
                    if (VoxelRay.clear(voxels, main.x, main.y, main.z, px + 0.5, y + 1.6, pz + 0.5, Voxel.SIGHT)) {
                        peek = BlockPos.asLong(px, y, pz);
                        break;
                    }
                }
            } else peek = key; // low cover: it shoots over it, standing where it is
            if (p.needPeek && peek == Long.MIN_VALUE) continue;
            out.add(new Spot(key, score[i] + (peek != Long.MIN_VALUE ? PEEK : 0), height, hard, peek));
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        nanos += System.nanoTime() - t0;
        return out;
    }

    static String info() {
        return String.format(java.util.Locale.ROOT, "cover queries %d, %d rays, %.0f us each", runs, rays, runs == 0 ? 0 : nanos / 1000.0 / runs);
    }
}
