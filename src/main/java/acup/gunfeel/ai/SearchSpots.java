package acup.gunfeel.ai;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * Where to look for a target that went out of sight: cells of the tactical map within the search radius of where it was
 * last seen that the mob cannot see from where it stands (were it in plain sight, the mob would see it). Nearest to that
 * place first, a little shuffled so two mobs do not pick the same order, and a cell with cover towards the mob before
 * one without (where one would hide), and one on the way the target was going before one against it (ExitSpots: where
 * it may come out). A cell the mob has looked into goes to its cleared set and is not offered again.
 */
public final class SearchSpots {
    /** how much nearer a cell with cover towards the mob counts, in blocks */
    static final double COVER_BONUS = 4;
    /** how much a cell against the way the target was going counts against it, blocks (straight behind: twice) */
    static final double AGAINST = 2;

    private SearchSpots() {
    }

    /** cells as BlockPos longs, best first; at most n, at most rayBudget rays spent; (vx, vz) its motion when lost */
    public static long[] pick(CellSource map, VoxelRay.Source voxels, Vec3 eye, double cx, double cy, double cz, double vx, double vz,
            double radius, int n, LongSet cleared, RandomSource rng, int rayBudget) {
        double speed = Math.sqrt(vx * vx + vz * vz);
        double ux = speed >= ExitSpots.MOVING ? vx / speed : 0, uz = speed >= ExitSpots.MOVING ? vz / speed : 0;
        int keep = Math.max(n * 4, rayBudget);
        long[] cand = new long[keep];
        double[] score = new double[keep];
        int count = 0;
        double r2 = radius * radius;
        int x0 = (int) Math.floor(cx - radius), x1 = (int) Math.floor(cx + radius);
        int z0 = (int) Math.floor(cz - radius), z1 = (int) Math.floor(cz + radius);
        int y0 = (int) Math.floor(cy) - 4, y1 = (int) Math.floor(cy) + 4;
        for (int sx = x0 >> 4; sx <= x1 >> 4; sx++)
            for (int sz = z0 >> 4; sz <= z1 >> 4; sz++)
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++) {
                    SectionCells c = map.cells(SectionPos.asLong(sx, sy, sz));
                    if (c == null) continue;
                    for (int i = 0; i < c.count(); i++) {
                        int x = (sx << 4) + c.x(i), y = (sy << 4) + c.y(i), z = (sz << 4) + c.z(i);
                        if (y < y0 || y > y1) continue;
                        double dx = x + 0.5 - cx, dz = z + 0.5 - cz, d2 = dx * dx + dz * dz;
                        if (d2 > r2) continue;
                        long key = BlockPos.asLong(x, y, z);
                        if (cleared.contains(key)) continue;
                        double s = Math.sqrt(d2) + Math.abs(y - cy) + rng.nextDouble() * 3;
                        if (SectionCells.height(c.cover(i), Dirs.of(eye.x - x - 0.5, eye.z - z - 0.5)) > 0) s -= COVER_BONUS;
                        double d = Math.sqrt(d2);
                        if (speed >= ExitSpots.MOVING && d > 1e-6) s += (1 - (dx * ux + dz * uz) / d) * AGAINST;
                        if (count < keep) {
                            cand[count] = key;
                            score[count++] = s;
                        } else {
                            int worst = 0;
                            for (int k = 1; k < keep; k++) if (score[k] > score[worst]) worst = k;
                            if (s < score[worst]) {
                                cand[worst] = key;
                                score[worst] = s;
                            }
                        }
                    }
                }
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Double.compare(score[a], score[b]));
        long[] out = new long[n];
        int found = 0;
        for (int k = 0; k < count && found < n && rayBudget > 0; k++) {
            long key = cand[order[k]];
            int x = BlockPos.getX(key), y = BlockPos.getY(key), z = BlockPos.getZ(key);
            rayBudget--;
            // a target standing in the cell: its chest
            if (VoxelRay.clear(voxels, eye.x, eye.y, eye.z, x + 0.5, y + 1.0, z + 0.5, Voxel.SIGHT)) {
                cleared.add(key);
                continue;
            }
            out[found++] = key;
        }
        return java.util.Arrays.copyOf(out, found);
    }
}
