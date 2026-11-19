package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

// core's ThroughWalk and HitOwner against 086e919's PassableClip (the "through" grid walk of clip(level, from, to,
// passed), owner), word for word with the game's types: the same blocks in the same order, the same owner
class ThroughWalkTest {
    // PassableClip.clip(level, from, to, passed) at 086e919, from its segment on: the blocks it asks about, in order
    private static List<Long> old(Vec3 from, Vec3 end) {
        List<Long> out = new ArrayList<>();
        Vec3 d = end.subtract(from);
        double len = d.length();
        if (len < 1e-6) return out;
        BlockPos start = BlockPos.containing(from);
        int x = start.getX(), y = start.getY(), z = start.getZ();
        int sx = d.x > 0 ? 1 : -1, sy = d.y > 0 ? 1 : -1, sz = d.z > 0 ? 1 : -1;
        double tx = d.x == 0 ? Double.MAX_VALUE : ((sx > 0 ? x + 1 - from.x : from.x - x) / Math.abs(d.x));
        double ty = d.y == 0 ? Double.MAX_VALUE : ((sy > 0 ? y + 1 - from.y : from.y - y) / Math.abs(d.y));
        double tz = d.z == 0 ? Double.MAX_VALUE : ((sz > 0 ? z + 1 - from.z : from.z - z) / Math.abs(d.z));
        double dx = d.x == 0 ? Double.MAX_VALUE : 1 / Math.abs(d.x), dy = d.y == 0 ? Double.MAX_VALUE : 1 / Math.abs(d.y),
                dz = d.z == 0 ? Double.MAX_VALUE : 1 / Math.abs(d.z);
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 512; i++) {
            if (tx <= ty && tx <= tz) {
                if (tx > 1) break;
                x += sx;
                tx += dx;
            } else if (ty <= tz) {
                if (ty > 1) break;
                y += sy;
                ty += dy;
            } else {
                if (tz > 1) break;
                z += sz;
                tz += dz;
            }
            at.set(x, y, z);
            out.add(at.asLong());
        }
        return out;
    }

    private static List<Long> now(Vec3 from, Vec3 end) {
        List<Long> out = new ArrayList<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        ThroughWalk.walk(from.x, from.y, from.z, end.x, end.y, end.z, at, (pos, x, y, z) -> {
            pos.set(x, y, z);
            out.add(pos.asLong());
        });
        return out;
    }

    // PassableClip.owner at 086e919
    private static BlockHitResult oldOwner(BlockHitResult hit) {
        Direction f = hit.getDirection();
        BlockPos pos = hit.getBlockPos();
        double at = hit.getLocation().get(f.getAxis()), lo = pos.get(f.getAxis());
        boolean onFar = f.getAxisDirection() == Direction.AxisDirection.POSITIVE ? at == lo : at == lo + 1;
        if (!onFar || hit.isInside()) return hit;
        return new BlockHitResult(hit.getLocation(), f, pos.relative(f.getOpposite()), false);
    }

    private static BlockHitResult newOwner(BlockHitResult hit) {
        Direction f = hit.getDirection();
        BlockPos pos = hit.getBlockPos();
        Vec3 at = hit.getLocation();
        if (!HitOwner.moves(f.ordinal(), at.x, at.y, at.z, pos.getX(), pos.getY(), pos.getZ(), hit.isInside())) return hit;
        return new BlockHitResult(at, f, pos.relative(f.getOpposite()), false);
    }

    private static final double[] ODD = {0, -0.0, 1, -1, 1e-7, -1e-7, 1e-9, 0.5, Math.nextUp(0.0), Math.nextDown(1.0), Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e300};

    private static double coord(SplittableRandom r, double base) {
        int k = r.nextInt(12);
        if (k == 0) return ODD[r.nextInt(ODD.length)];
        if (k == 1) return Math.floor(base) + ODD[r.nextInt(ODD.length)];
        // on a block's face
        if (k == 2) return Math.floor(base);
        return base;
    }

    @Test
    void sameBlocks() {
        SplittableRandom r = new SplittableRandom(1924);
        long visits = 0;
        int n = 1_000_000;
        for (int i = 0; i < n; i++) {
            double fx = r.nextDouble(-3000, 3000), fy = r.nextDouble(-64, 320), fz = r.nextDouble(-3000, 3000);
            // a tick's way (up to 8 blocks), now and then along an axis, a diagonal, very long or none
            double len = r.nextInt(50) == 0 ? r.nextDouble(100, 900) : r.nextDouble(0, 8);
            double dx = r.nextDouble(-1, 1), dy = r.nextDouble(-1, 1), dz = r.nextDouble(-1, 1);
            switch (r.nextInt(10)) {
                case 0 -> { dy = 0; dz = 0; }
                case 1 -> { dx = 0; dz = 0; }
                case 2 -> dz = 0;
                case 3 -> { dy = dx; dz = dx; }
                case 4 -> len = r.nextBoolean() ? 0 : 1e-7;
                default -> { }
            }
            Vec3 from = new Vec3(coord(r, fx), coord(r, fy), coord(r, fz));
            Vec3 end = new Vec3(coord(r, fx + dx * len), coord(r, fy + dy * len), coord(r, fz + dz * len));
            List<Long> want = old(from, end);
            assertEquals(want, now(from, end), from + " -> " + end);
            visits += want.size();
        }
        assertTrue(visits > n, "visits " + visits);
    }

    @Test
    void sameOwner() {
        SplittableRandom r = new SplittableRandom(1925);
        int moved = 0, n = 1_000_000;
        Direction[] faces = Direction.values();
        for (int i = 0; i < n; i++) {
            int bx = r.nextInt(-3000, 3000), by = r.nextInt(-64, 320), bz = r.nextInt(-3000, 3000);
            // on a face of the block mostly: its near or far side along each axis, or inside, or odd
            double[] p = new double[3];
            int[] b = {bx, by, bz};
            for (int a = 0; a < 3; a++) {
                int k = r.nextInt(8);
                p[a] = k < 3 ? b[a] : k < 6 ? b[a] + 1 : k == 6 ? b[a] + r.nextDouble() : ODD[r.nextInt(ODD.length)];
            }
            BlockHitResult hit = new BlockHitResult(new Vec3(p[0], p[1], p[2]), faces[r.nextInt(6)], new BlockPos(bx, by, bz), r.nextInt(4) == 0);
            BlockHitResult want = oldOwner(hit), got = newOwner(hit);
            assertEquals(want == hit, got == hit, hit.getDirection() + " " + hit.getLocation() + " " + hit.getBlockPos() + " " + hit.isInside());
            if (want != hit) {
                moved++;
                assertEquals(want.getBlockPos(), got.getBlockPos());
                assertEquals(want.getDirection(), got.getDirection());
                assertEquals(want.getLocation(), got.getLocation());
                assertEquals(want.isInside(), got.isInside());
            }
        }
        assertTrue(moved > n / 20 && moved < n / 2, "moved " + moved);
    }
}
