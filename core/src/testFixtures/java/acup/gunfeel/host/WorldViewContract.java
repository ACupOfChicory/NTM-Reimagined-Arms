package acup.gunfeel.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import acup.gunfeel.BlockFacts;

/**
 * What every version's world must say (WorldView, BlockTable): a place's number stays its state's, 0 is plain air,
 * one number has one facts and one key, the key is the block's id then its properties sorted by name. A version's
 * test extends this with a world of its own: plain air at {@link #air}, stone at {@link #stone}, a block with
 * properties at {@link #withProperties}, cave air at {@link #caveAir}. Air is the game's: plain and cave air are, stone
 * and a fence are not; the destroy speed at a place is the game's there.
 *
 * <p>The level's own questions: its tick grows by one a tick, its side is the game's; the game's ray from the air next to
 * the stone (on one of its faces, {@link #air} and {@link #stone} side by side) hits the stone's face there, and one
 * staying in the air's block misses (its end, the face back to its start, the end's block); the stone's shape is the
 * one whole block, a bottom slab's its lower half, air's none; the air's room is free, the stone's is not.
 */
public abstract class WorldViewContract {
    /** the version's world, with plain air, stone and a block with properties at the places below */
    protected abstract WorldView world();

    protected abstract int[] air();

    protected abstract int[] stone();

    protected abstract int[] withProperties();

    /** a place of cave air */
    protected abstract int[] caveAir();

    /** stone's destroy speed as the game gives it */
    protected abstract float stoneDestroySpeed();

    /** a place of a bottom slab (stone's, oak's: a half block from the floor) */
    protected abstract int[] slab();

    /** the side the world is on: the game's */
    protected abstract boolean clientSide();

    /** the level goes on one game tick */
    protected abstract void tick();

    private int at(int[] p) {
        return world().state(p[0], p[1], p[2]);
    }

    @Test
    void airIsZero() {
        assertEquals(BlockTable.AIR, at(air()));
        assertEquals("minecraft:air", world().facts(BlockTable.AIR).id());
        assertEquals("minecraft:air", world().key(BlockTable.AIR));
    }

    @Test
    void numbersStay() {
        int s = at(stone()), p = at(withProperties());
        for (int i = 0; i < 3; i++) {
            assertEquals(s, at(stone()));
            assertEquals(p, at(withProperties()));
        }
        assertNotEquals(s, p);
        assertNotEquals(BlockTable.AIR, s);
    }

    @Test
    void oneNumberOneFactsOneKey() {
        int s = at(stone());
        BlockFacts f = world().facts(s);
        assertEquals("minecraft:stone", f.id());
        assertEquals(f, world().facts(s));
        assertEquals(world().key(s), world().key(s));
        assertEquals("minecraft:stone", world().key(s));
    }

    @Test
    void keyIsIdThenSortedProperties() {
        int p = at(withProperties());
        String key = world().key(p), id = world().facts(p).id();
        assertTrue(key.startsWith(id + "["), key);
        assertTrue(key.endsWith("]"), key);
        String last = "";
        for (String pair : key.substring(id.length() + 1, key.length() - 1).split(",")) {
            String[] nv = pair.split("=", -1);
            assertEquals(2, nv.length, pair);
            assertTrue(nv[0].compareTo(last) > 0, key);
            last = nv[0];
        }
    }

    @Test
    void airAsTheGameTellsIt() {
        assertTrue(world().air(at(air())));
        int cave = at(caveAir());
        assertNotEquals(BlockTable.AIR, cave);
        assertTrue(world().air(cave));
        assertEquals(false, world().air(at(stone())));
        assertEquals(false, world().air(at(withProperties())));
    }

    @Test
    void destroySpeedThere() {
        int[] s = stone();
        assertEquals(stoneDestroySpeed(), world().destroySpeed(s[0], s[1], s[2]));
    }

    @Test
    void timeGrowsByATick() {
        long t = world().gameTime();
        assertEquals(t, world().gameTime());
        tick();
        assertEquals(t + 1, world().gameTime());
    }

    @Test
    void sideIsTheGames() {
        assertEquals(clientSide(), world().client());
    }

    // the air's and the stone's centres and the step from the air to the stone (one of -1, 0, 1 on each axis)
    private double[] centres() {
        int[] a = air(), s = stone();
        int dx = s[0] - a[0], dy = s[1] - a[1], dz = s[2] - a[2];
        assertEquals(1, Math.abs(dx) + Math.abs(dy) + Math.abs(dz), "the air is next to the stone on a face");
        return new double[] {a[0] + .5, a[1] + .5, a[2] + .5, dx, dy, dz};
    }

    // the face (as Direction's ordinal: DOWN, UP, NORTH, SOUTH, WEST, EAST) a step points to
    private static int face(double dx, double dy, double dz) {
        return dy < 0 ? 0 : dy > 0 ? 1 : dz < 0 ? 2 : dz > 0 ? 3 : dx < 0 ? 4 : 5;
    }

    @Test
    void rayHitsTheStonesFace() {
        double[] c = centres();
        int[] s = stone();
        RayHit out = new RayHit();
        assertTrue(world().clip(c[0], c[1], c[2], c[0] + c[3], c[1] + c[4], c[2] + c[5], WorldView.COLLIDER, WorldView.FLUID_NONE, null, out));
        assertTrue(out.block);
        assertFalse(out.inside);
        // half way: the stone's face to the air
        assertArrayEquals(new double[] {c[0] + c[3] / 2, c[1] + c[4] / 2, c[2] + c[5] / 2}, new double[] {out.x, out.y, out.z});
        assertEquals(face(-c[3], -c[4], -c[5]), out.face);
        assertArrayEquals(s, new int[] {out.bx, out.by, out.bz});
    }

    @Test
    void rayInTheAirMisses() {
        double[] c = centres();
        int[] a = air();
        RayHit out = new RayHit();
        out.block = true;
        double tx = c[0] - c[3] / 4, ty = c[1] - c[4] / 4, tz = c[2] - c[5] / 4;
        assertFalse(world().clip(c[0], c[1], c[2], tx, ty, tz, WorldView.COLLIDER, WorldView.FLUID_NONE, null, out));
        assertFalse(out.block);
        // the game's miss: at its end, the face back to its start, the end's block
        assertArrayEquals(new double[] {tx, ty, tz}, new double[] {out.x, out.y, out.z});
        assertEquals(face(c[3], c[4], c[5]), out.face);
        assertArrayEquals(a, new int[] {out.bx, out.by, out.bz});
    }

    @Test
    void boxesOfAShape() {
        int[] s = stone(), h = slab(), a = air();
        double[] out = new double[12];
        for (int kind : new int[] {WorldView.COLLIDER, WorldView.OUTLINE}) {
            assertEquals(1, world().boxes(s[0], s[1], s[2], kind, null, out));
            assertArrayEquals(new double[] {0, 0, 0, 1, 1, 1}, java.util.Arrays.copyOf(out, 6));
            assertEquals(1, world().boxes(h[0], h[1], h[2], kind, null, out));
            assertArrayEquals(new double[] {0, 0, 0, 1, .5, 1}, java.util.Arrays.copyOf(out, 6));
            assertEquals(0, world().boxes(a[0], a[1], a[2], kind, null, out));
        }
        // too short: how many, nothing cut
        double[] none = {7};
        assertEquals(-1, world().boxes(s[0], s[1], s[2], WorldView.COLLIDER, null, none));
        assertArrayEquals(new double[] {7}, none);
        assertThrows(IllegalArgumentException.class, () -> world().boxes(s[0], s[1], s[2], WorldView.FALLDAMAGE_RESETTING, null, out));
    }

    @Test
    void roomInTheAirNotInTheStone() {
        int[] a = air(), s = stone();
        assertTrue(world().free(null, a[0] + .25, a[1] + .25, a[2] + .25, a[0] + .75, a[1] + .75, a[2] + .75));
        assertFalse(world().free(null, s[0] + .25, s[1] + .25, s[2] + .25, s[0] + .75, s[1] + .75, s[2] + .75));
    }
}
