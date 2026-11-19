package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

// Core's walk of the blocks on a line (BlockWalk) against 26.2's own (BlockGetter.traverseBlocks, the walk of
// level.clip): the same blocks in the same order on every line, stopped where the visitor stops it, for lines of every
// kind (any, on block faces and edges, along an axis, very short, long, far from the origin).
class BlockWalkVanillaTest {
    private static final long MISS = Long.MIN_VALUE;

    private static List<Long> vanilla(Vec3 from, Vec3 to, int stopAt) {
        List<Long> seen = new ArrayList<>();
        Long r = BlockGetter.traverseBlocks(from, to, seen, (s, pos) -> {
            s.add(pos.asLong());
            return s.size() == stopAt ? pos.asLong() : null;
        }, s -> MISS);
        seen.add(r);
        return seen;
    }

    private static List<Long> core(Vec3 from, Vec3 to, int stopAt) {
        List<Long> seen = new ArrayList<>();
        Long r = BlockWalk.walk(from.x, from.y, from.z, to.x, to.y, to.z, seen, (s, x, y, z) -> {
            s.add(BlockPos.asLong(x, y, z));
            return s.size() == stopAt ? BlockPos.asLong(x, y, z) : null;
        }, s -> MISS);
        seen.add(r);
        return seen;
    }

    private static void same(Vec3 from, Vec3 to, int stopAt) {
        assertEquals(vanilla(from, to, stopAt), core(from, to, stopAt), () -> from + " -> " + to + " stop " + stopAt);
    }

    // a coordinate of one of the kinds lines meet blocks at
    private static double coord(SplittableRandom r, double around) {
        double base = Math.floor(around + r.nextDouble(-40, 40));
        return switch (r.nextInt(8)) {
            case 0 -> base;
            case 1 -> base + 0.5;
            case 2 -> base + 1e-7;
            case 3 -> base - 1e-7;
            case 4 -> base + r.nextInt(16) / 16.0;
            case 5 -> base + Math.ulp(base);
            default -> base + r.nextDouble();
        };
    }

    @Test
    void theSameBlocksAsTheGame() {
        SplittableRandom r = new SplittableRandom(20261119L);
        for (int i = 0; i < 200_000; i++) {
            double around = switch (i % 4) {
                case 0 -> 0;
                case 1 -> -30_000_000 + 1000;
                case 2 -> 123_456.0;
                default -> -64;
            };
            Vec3 from = new Vec3(coord(r, around), coord(r, around), coord(r, around));
            Vec3 to = switch (r.nextInt(6)) {
                // along an axis, within a plane
                case 0 -> new Vec3(coord(r, around), from.y, from.z);
                case 1 -> new Vec3(from.x, coord(r, around), coord(r, around));
                // very short
                case 2 -> from.add(r.nextDouble(-1e-6, 1e-6), r.nextDouble(-1e-6, 1e-6), r.nextDouble(-1e-6, 1e-6));
                // a bullet's tick
                case 3 -> from.add(r.nextDouble(-20, 20), r.nextDouble(-20, 20), r.nextDouble(-20, 20));
                default -> new Vec3(coord(r, around), coord(r, around), coord(r, around));
            };
            same(from, to, Integer.MAX_VALUE);
            same(from, to, 1 + r.nextInt(5));
        }
    }

    @Test
    void edgeLines() {
        double[] at = {0, 1, -1, 0.5, 1e-7, -1e-7, 1 - 1e-7, 15.999999999, 16, -16.0000001};
        for (double a : at) for (double b : at) for (double c : at) for (double d : at) {
            same(new Vec3(a, b, c), new Vec3(d, c, a), Integer.MAX_VALUE);
            same(new Vec3(a, b, c), new Vec3(a, b, d), Integer.MAX_VALUE);
            same(new Vec3(a, b, c), new Vec3(a, b, c), Integer.MAX_VALUE);
            same(new Vec3(a, b, c), new Vec3(d + 3, d - 2, d), Integer.MAX_VALUE);
        }
    }
}
