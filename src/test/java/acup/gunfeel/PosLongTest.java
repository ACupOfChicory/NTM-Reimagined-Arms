package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.SplittableRandom;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import org.junit.jupiter.api.Test;

// core's PosLong packs and unpacks as BlockPos and SectionPos do: the same long for the same coordinates (past the packed
// field too: cut by the mask, wrapped), the same coordinates out of any long, not only a packed one
class PosLongTest {
    // the ends: 0, ±1, the int's ends, the packed fields' ends (block x/z ±2^25, y ±2^11; section x/z ±2^21, y ±2^19)
    // and one past them, the world border
    private static final int[] EDGE = {0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE + 1, Integer.MAX_VALUE - 1,
            1 << 25, (1 << 25) - 1, -(1 << 25), -(1 << 25) - 1, 1 << 26, -(1 << 26),
            1 << 11, (1 << 11) - 1, -(1 << 11), -(1 << 11) - 1, 1 << 12, -(1 << 12),
            1 << 21, (1 << 21) - 1, -(1 << 21), -(1 << 21) - 1, 1 << 19, (1 << 19) - 1, -(1 << 19), -(1 << 19) - 1,
            30_000_000, -30_000_000, 29_999_999, -30_000_001, 2047, -2048, 2048, -2049, 320, -64};
    private static final long[] EDGE_LONG = {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 1L << 63, (1L << 38) - 1, 1L << 38,
            1L << 37, 1L << 12, (1L << 12) - 1, 1L << 11, 1L << 42, (1L << 42) - 1, 1L << 41, 1L << 20, 1L << 19, 0x5555_5555_5555_5555L,
            0xAAAA_AAAA_AAAA_AAAAL};

    private static int pick(SplittableRandom r) {
        return switch (r.nextInt(4)) {
            case 0 -> EDGE[r.nextInt(EDGE.length)];
            case 1 -> r.nextInt();
            case 2 -> r.nextInt(-30_000_000, 30_000_001);
            default -> r.nextInt(-2100, 2100);
        };
    }

    private static void unpacks(String what, long l) {
        assertEquals(BlockPos.getX(l), PosLong.blockX(l), () -> "blockX " + what);
        assertEquals(BlockPos.getY(l), PosLong.blockY(l), () -> "blockY " + what);
        assertEquals(BlockPos.getZ(l), PosLong.blockZ(l), () -> "blockZ " + what);
        assertEquals(SectionPos.x(l), PosLong.sectionX(l), () -> "sectionX " + what);
        assertEquals(SectionPos.y(l), PosLong.sectionY(l), () -> "sectionY " + what);
        assertEquals(SectionPos.z(l), PosLong.sectionZ(l), () -> "sectionZ " + what);
    }

    @Test
    void packsAsBlockPosAndSectionPos() {
        SplittableRandom r = new SplittableRandom(20261119);
        for (int i = 0; i < 300_000; i++) {
            int x = pick(r), y = pick(r), z = pick(r);
            String at = x + " " + y + " " + z;
            long b = BlockPos.asLong(x, y, z), s = SectionPos.asLong(x, y, z);
            assertEquals(b, PosLong.block(x, y, z), () -> "block " + at);
            assertEquals(s, PosLong.section(x, y, z), () -> "section " + at);
            unpacks("of block " + at, b);
            unpacks("of section " + at, s);
        }
    }

    @Test
    void everyEdgeTogether() {
        for (int x : EDGE)
            for (int y : EDGE)
                for (int z : EDGE) {
                    String at = x + " " + y + " " + z;
                    assertEquals(BlockPos.asLong(x, y, z), PosLong.block(x, y, z), () -> "block " + at);
                    assertEquals(SectionPos.asLong(x, y, z), PosLong.section(x, y, z), () -> "section " + at);
                }
    }

    // any long, not only a packed one
    @Test
    void unpacksAnyLong() {
        SplittableRandom r = new SplittableRandom(19112026);
        for (long l : EDGE_LONG) unpacks(Long.toHexString(l), l);
        for (int i = 0; i < 300_000; i++) {
            long l = r.nextLong();
            unpacks(Long.toHexString(l), l);
        }
    }
}
