package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

// The fence, gate and wall cells the port takes from 26.2's table: every key a 1.7.10 block can give is there, and a
// bullet goes through the gaps and meets the posts and rails, as BulletCheck's fence cases on 26.2.
class FenceCellsTest {
    @Test
    void everyKeyOf1710IsInTheTable() {
        Map<String, long[]> f = FenceCells.families();
        boolean[] tf = {false, true};
        for (boolean e : tf) for (boolean n : tf) for (boolean s : tf) for (boolean w : tf) {
            String fence = "~fence[east=" + e + ",north=" + n + ",south=" + s + ",west=" + w + "]";
            assertNotNull(f.get(fence), fence);
            for (boolean air : tf) {
                String wall = FenceCells.wallKey(e, n, s, w, air);
                assertNotNull(f.get(wall), wall);
            }
        }
        for (int meta = 0; meta < 8; meta++) assertNotNull(f.get(FenceCells.gateKey(meta)), FenceCells.gateKey(meta));
    }

    @Test
    void gateMetaIsWhereThePlacerLooked() {
        assertEquals("~fence_gate[facing=south,in_wall=false,open=false]", FenceCells.gateKey(0));
        assertEquals("~fence_gate[facing=east,in_wall=false,open=true]", FenceCells.gateKey(7));
    }

    @Test
    void aStraightWallWithAirAboveHasNoPost() {
        assertEquals("~wall[east=low,north=none,south=none,up=false,west=low]", FenceCells.wallKey(true, false, false, true, true));
        assertEquals("~wall[east=low,north=none,south=none,up=true,west=low]", FenceCells.wallKey(true, false, false, true, false));
        assertEquals("~wall[east=none,north=none,south=none,up=true,west=none]", FenceCells.wallKey(false, false, false, false, true));
    }

    // a bullet along +z through the block at (0, 0, 0), at (x, y) in it
    private static BlockRay.Hit along(long[] cells, double x, double y) {
        BlockRay.Hit in = BlockRay.clip(new double[] {0, 0, 0, 1, 1, 1}, 1, x, y, -1, x, y, 2, 0, 0, 0);
        assertNotNull(in);
        return FenceCells.march(cells, in, 0, 0, 3, 64);
    }

    @Test
    void aFenceIsMetOnItsPostAndRailsOnly() {
        long[] row = FenceCells.families().get("~fence[east=true,north=false,south=false,west=true]");
        // BulletCheck's "fence: between the rails" and "fence: the post"
        assertNull(along(row, .25, .65));
        BlockRay.Hit post = along(row, .5, .5);
        assertNotNull(post);
        assertEquals(BlockRay.NORTH, post.face);
        assertEquals(6 / 16.0, post.az, 1e-9);
        // the upper rail (y 12..15, z 7..9)
        BlockRay.Hit rail = along(row, .25, .85);
        assertNotNull(rail);
        assertEquals(7 / 16.0, rail.az, 1e-9);
        // out of the rail where its cells end
        BlockRay.Hit out = FenceCells.leave(row, rail, 0, 0, 1);
        assertEquals(BlockRay.SOUTH, out.face);
        assertEquals(9 / 16.0, out.az, 1e-9);
    }

    @Test
    void aClosedGateAndALowWall() {
        Map<String, long[]> f = FenceCells.families();
        long[] gate = f.get(FenceCells.gateKey(0));
        assertNull(along(gate, .25, .65));
        assertNotNull(along(gate, .5, .5));
        long[] wall = f.get(FenceCells.wallKey(true, false, false, true, true));
        // BulletCheck's "wall low: over it" and "into it"
        assertNull(along(wall, .5, .95));
        assertTrue(along(wall, .5, .5) != null);
    }
}
