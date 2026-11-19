package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

// The walk of a bullet through a block's 16³ cells, now ShapeCells.march and leave in core, against PassableClip's
// own walk as it was before it went there (copied below as it was, 9ec3b5a): the same hit, bit for bit, on random
// cells, entries (on cell faces, edges and corners too) and ways (along an axis, nearly so, any).
class CellWalkTest {
    // 9ec3b5a's PassableClip.march and leave, word for word
    static BlockHitResult oldMarch(long[] bits, BlockHitResult hit, Vec3 way, int cells) {
        BlockPos pos = hit.getBlockPos();
        double len = way.length();
        if (len < 1e-9) return hit;
        double dx = way.x / len, dy = way.y / len, dz = way.z / len;
        double px = (hit.getLocation().x - pos.getX()) * 16, py = (hit.getLocation().y - pos.getY()) * 16, pz = (hit.getLocation().z - pos.getZ()) * 16;
        // На границе клетки берём сторону, в которую летит пуля. Иначе отрицательное направление
        // на x=3/16 проверяло пустую клетку 3 вместо металла в клетке 2 (верхняя половина двери).
        int x = Math.clamp((int) Math.floor(px + dx * 1e-7), 0, 15);
        int y = Math.clamp((int) Math.floor(py + dy * 1e-7), 0, 15);
        int z = Math.clamp((int) Math.floor(pz + dz * 1e-7), 0, 15);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tx = Math.abs(dx) < 1e-12 ? Double.POSITIVE_INFINITY : ((sx > 0 ? x + 1 : x) - px) / dx;
        double ty = Math.abs(dy) < 1e-12 ? Double.POSITIVE_INFINITY : ((sy > 0 ? y + 1 : y) - py) / dy;
        double tz = Math.abs(dz) < 1e-12 ? Double.POSITIVE_INFINITY : ((sz > 0 ? z + 1 : z) - pz) / dz;
        double ddx = Math.abs(1 / dx), ddy = Math.abs(1 / dy), ddz = Math.abs(1 / dz);
        double t = 0;
        Direction face = hit.getDirection();
        for (int n = 0; n < cells; n++) {
            if (ShapeCells.cell(bits, x, y, z)) {
                Vec3 at = new Vec3(pos.getX() + (px + dx * t) / 16, pos.getY() + (py + dy * t) / 16, pos.getZ() + (pz + dz * t) / 16);
                return new BlockHitResult(at, face, pos, false);
            }
            if (tx <= ty && tx <= tz) {
                t = tx; tx += ddx; x += sx; face = sx > 0 ? Direction.WEST : Direction.EAST;
            } else if (ty <= tz) {
                t = ty; ty += ddy; y += sy; face = sy > 0 ? Direction.DOWN : Direction.UP;
            } else {
                t = tz; tz += ddz; z += sz; face = sz > 0 ? Direction.NORTH : Direction.SOUTH;
            }
            if (x < 0 || x > 15 || y < 0 || y > 15 || z < 0 || z > 15) return null;
        }
        return null;
    }

    // From where the bullet meets a set cell, along `dir` (unit), to the first empty cell or the block's side: the
    // hit there on that far face, as exit() gives it
    static BlockHitResult oldLeave(long[] bits, BlockHitResult in, Vec3 dir) {
        BlockPos pos = in.getBlockPos();
        // a little into the cell it met: on its boundary the floor may name the empty one before it
        double px = (in.getLocation().x - pos.getX()) * 16 + dir.x * 1e-3, py = (in.getLocation().y - pos.getY()) * 16 + dir.y * 1e-3,
                pz = (in.getLocation().z - pos.getZ()) * 16 + dir.z * 1e-3;
        int x = Math.clamp((int) Math.floor(px), 0, 15), y = Math.clamp((int) Math.floor(py), 0, 15), z = Math.clamp((int) Math.floor(pz), 0, 15);
        double dx = dir.x, dy = dir.y, dz = dir.z;
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double tx = Math.abs(dx) < 1e-12 ? Double.POSITIVE_INFINITY : ((sx > 0 ? x + 1 : x) - px) / dx;
        double ty = Math.abs(dy) < 1e-12 ? Double.POSITIVE_INFINITY : ((sy > 0 ? y + 1 : y) - py) / dy;
        double tz = Math.abs(dz) < 1e-12 ? Double.POSITIVE_INFINITY : ((sz > 0 ? z + 1 : z) - pz) / dz;
        double ddx = Math.abs(1 / dx), ddy = Math.abs(1 / dy), ddz = Math.abs(1 / dz);
        double t = 0;
        Direction face = in.getDirection().getOpposite();
        for (int n = 0; n < 64 && ShapeCells.cell(bits, x, y, z); n++) {
            if (tx <= ty && tx <= tz) {
                t = tx; tx += ddx; x += sx; face = sx > 0 ? Direction.EAST : Direction.WEST;
            } else if (ty <= tz) {
                t = ty; ty += ddy; y += sy; face = sy > 0 ? Direction.UP : Direction.DOWN;
            } else {
                t = tz; tz += ddz; z += sz; face = sz > 0 ? Direction.SOUTH : Direction.NORTH;
            }
            if (x < 0 || x > 15 || y < 0 || y > 15 || z < 0 || z > 15) break;
        }
        Vec3 at = new Vec3(pos.getX() + (px + dx * t) / 16, pos.getY() + (py + dy * t) / 16, pos.getZ() + (pz + dz * t) / 16);
        return new BlockHitResult(at, face, pos, false);
    }

    private static String show(BlockHitResult h) {
        if (h == null) return "null";
        Vec3 l = h.getLocation();
        return Double.doubleToRawLongBits(l.x) + "," + Double.doubleToRawLongBits(l.y) + "," + Double.doubleToRawLongBits(l.z) + " " + h.getDirection() + " "
                + h.getBlockPos() + " " + h.isInside() + " " + h.getType();
    }

    private static long[] cells(SplittableRandom r) {
        long[] bits = new long[65];
        double density = switch (r.nextInt(4)) {
            case 0 -> 0.02;
            case 1 -> 0.2;
            case 2 -> 0.6;
            default -> 0.95;
        };
        for (int i = 0; i < 4096; i++) if (r.nextDouble() < density) bits[i >> 6] |= 1L << (i & 63);
        return bits;
    }

    private static double coord(SplittableRandom r) {
        return switch (r.nextInt(5)) {
            case 0 -> r.nextInt(17) / 16.0;
            case 1 -> r.nextInt(17) / 16.0 + (r.nextBoolean() ? 1e-9 : -1e-9);
            case 2 -> r.nextBoolean() ? 0.0 : 1.0;
            default -> r.nextDouble();
        };
    }

    private static double part(SplittableRandom r) {
        return switch (r.nextInt(6)) {
            case 0 -> 0.0;
            case 1 -> r.nextDouble(-1e-13, 1e-13);
            case 2 -> r.nextBoolean() ? 1.0 : -1.0;
            default -> r.nextDouble(-1, 1);
        };
    }

    @Test
    void theSameHitsAsBefore() {
        SplittableRandom r = new SplittableRandom(1119L);
        Direction[] sides = Direction.values();
        int met = 0;
        for (int i = 0; i < 300_000; i++) {
            long[] bits = cells(r);
            BlockPos pos = new BlockPos(r.nextInt(-300, 300), r.nextInt(-64, 320), r.nextInt(-300, 300));
            Vec3 at = new Vec3(pos.getX() + coord(r), pos.getY() + coord(r), pos.getZ() + coord(r));
            BlockHitResult hit = new BlockHitResult(at, sides[r.nextInt(6)], pos, r.nextInt(8) == 0);
            Vec3 way = new Vec3(part(r), part(r), part(r)).scale(r.nextBoolean() ? 1 : r.nextDouble(0.01, 40));
            int n = r.nextBoolean() ? 64 : 1 + r.nextInt(3);
            BlockHitResult a = oldMarch(bits, hit, way, n), b = PassableClip.march(bits, hit, way, n);
            assertEquals(show(a), show(b), () -> "march " + hit.getLocation() + " " + hit.getDirection() + " way " + way + " cells " + n);
            if (a != null) met++;
            double len = way.length();
            if (len < 1e-9) continue;
            Vec3 dir = way.scale(1 / len);
            assertEquals(show(oldLeave(bits, hit, dir)), show(PassableClip.leave(bits, hit, dir)), () -> "leave " + hit.getLocation() + " dir " + dir);
        }
        assertTrue(met > 50_000, "met " + met);
    }

    @Test
    void sidesAreTheGamesNumbers() {
        assertEquals(Direction.DOWN.ordinal(), ShapeCells.DOWN);
        assertEquals(Direction.UP.ordinal(), ShapeCells.UP);
        assertEquals(Direction.NORTH.ordinal(), ShapeCells.NORTH);
        assertEquals(Direction.SOUTH.ordinal(), ShapeCells.SOUTH);
        assertEquals(Direction.WEST.ordinal(), ShapeCells.WEST);
        assertEquals(Direction.EAST.ordinal(), ShapeCells.EAST);
        assertEquals(6, Direction.values().length);
    }
}
