package acup.gunfeel.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import acup.gunfeel.BlockFacts;

/**
 * What every version's world must say (WorldView, BlockTable): a place's number stays its state's, 0 is plain air,
 * one number has one facts and one key, the key is the block's id then its properties sorted by name. A version's
 * test extends this with a world of its own: plain air at {@link #air}, stone at {@link #stone}, a block with
 * properties at {@link #withProperties}.
 */
public abstract class WorldViewContract {
    /** the version's world, with plain air, stone and a block with properties at the places below */
    protected abstract WorldView world();

    protected abstract int[] air();

    protected abstract int[] stone();

    protected abstract int[] withProperties();

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
}
