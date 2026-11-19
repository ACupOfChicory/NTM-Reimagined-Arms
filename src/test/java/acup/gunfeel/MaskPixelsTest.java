package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

// core's MaskPixels against fdf3485's PassableClip.solid and hiddenFace, their arithmetic word for word (the block's
// mask and its full-cube flag as arguments, the neighbour's shouldRenderFace left out): every case the same
class MaskPixelsTest {
    // PassableClip.solid at fdf3485, `m` maskOf(state), `full` state.isCollisionShapeFullBlock(...)
    private static boolean oldSolid(long[][] m, boolean full, BlockHitResult hit) {
        if (m == null) return true;
        Direction face = hit.getDirection();
        int axis = face.getAxis().ordinal();
        long[] bits = m[axis] != null ? m[axis] : m[3];
        if (bits == null) return true;
        Vec3 local = hit.getLocation().subtract(hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ());
        double u = axis == 0 ? local.z : local.x;
        double v = axis == 1 ? local.z : 1.0 - local.y;
        if (full) {
            switch (face) {
                case EAST -> u = 1.0 - local.z;
                case NORTH -> u = 1.0 - local.x;
                case DOWN -> v = 1.0 - local.z;
                default -> { }
            }
        }
        int col = Math.clamp((int) Math.floor(u * 16.0), 0, 15), row = Math.clamp((int) Math.floor(v * 16.0), 0, 15);
        int bit = row * 16 + col;
        return (bits[bit >> 6] >>> (63 - (bit & 63)) & 1L) != 0L;
    }

    // PassableClip.hiddenFace at fdf3485 up to the neighbour's question: false there, else it goes on to ask
    private static boolean oldOnFace(BlockHitResult hit) {
        Direction face = hit.getDirection();
        BlockPos pos = hit.getBlockPos();
        double at = switch (face.getAxis()) {
            case X -> hit.getLocation().x - pos.getX();
            case Y -> hit.getLocation().y - pos.getY();
            case Z -> hit.getLocation().z - pos.getZ();
        };
        double edge = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 : 0.0;
        return !(Math.abs(at - edge) > 1e-5);
    }

    // the new PassableClip.solid's arithmetic
    private static boolean newSolid(long[][] m, boolean full, BlockHitResult hit) {
        if (m == null) return true;
        Direction face = hit.getDirection();
        long[] bits = MaskPixels.side(m, face.ordinal());
        if (bits == null) return true;
        Vec3 at = hit.getLocation();
        BlockPos pos = hit.getBlockPos();
        return MaskPixels.shown(bits, face.ordinal(), at.x - pos.getX(), at.y - pos.getY(), at.z - pos.getZ(), full);
    }

    private static final double[] ODD = {0, -0.0, 1, 1 - 1e-5, 1 + 1e-5, 1e-5, -1e-5, 1.0000100000000001, 0.9999899999999999, 1.0 / 16, 15.0 / 16,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Math.nextDown(1.0), Math.nextUp(0.0)};

    private static double coord(SplittableRandom r, int base) {
        int k = r.nextInt(10);
        if (k == 0) return base + ODD[r.nextInt(ODD.length)];
        if (k == 1) return ODD[r.nextInt(ODD.length)];
        // on a pixel's edge
        if (k == 2) return base + r.nextInt(17) / 16.0;
        return base + r.nextDouble(-0.1, 1.1);
    }

    @Test
    void sameAsBefore() {
        // the faces' order: core's numbers are the game's ordinals
        assertEquals(MaskPixels.DOWN, Direction.DOWN.ordinal());
        assertEquals(MaskPixels.UP, Direction.UP.ordinal());
        assertEquals(MaskPixels.NORTH, Direction.NORTH.ordinal());
        assertEquals(MaskPixels.SOUTH, Direction.SOUTH.ordinal());
        assertEquals(MaskPixels.WEST, Direction.WEST.ordinal());
        assertEquals(MaskPixels.EAST, Direction.EAST.ordinal());
        for (Direction d : Direction.values()) assertEquals(d.getAxis().ordinal(), MaskPixels.axis(d.ordinal()));
        SplittableRandom r = new SplittableRandom(1923);
        int shown = 0, on = 0, n = 1_000_000;
        Direction[] faces = Direction.values();
        for (int i = 0; i < n; i++) {
            long[][] m = new long[4][];
            // a mask per axis, for any, or none; random bits
            for (int a = 0; a < 4; a++)
                if (r.nextInt(3) > 0) m[a] = new long[] {r.nextLong(), r.nextLong(), r.nextLong(), r.nextLong()};
            int bx = r.nextInt(-3000, 3000), by = r.nextInt(-64, 320), bz = r.nextInt(-3000, 3000);
            Vec3 at = new Vec3(coord(r, bx), coord(r, by), coord(r, bz));
            BlockHitResult hit = new BlockHitResult(at, faces[r.nextInt(6)], new BlockPos(bx, by, bz), r.nextBoolean());
            boolean full = r.nextBoolean();
            long[][] mask = r.nextInt(20) == 0 ? null : m;
            boolean want = oldSolid(mask, full, hit);
            assertEquals(want, newSolid(mask, full, hit), hit.getDirection() + " " + at + " " + hit.getBlockPos() + " " + full);
            boolean wantOn = oldOnFace(hit);
            Vec3 l = hit.getLocation();
            assertEquals(wantOn, MaskPixels.onFace(hit.getDirection().ordinal(), l.x - bx, l.y - by, l.z - bz), hit.getDirection() + " " + at);
            if (want) shown++;
            if (wantOn) on++;
        }
        assertTrue(shown > n / 10 && shown < n * 9 / 10, "shown " + shown);
        assertTrue(on > n / 200 && on < n / 2, "on " + on);
    }
}
