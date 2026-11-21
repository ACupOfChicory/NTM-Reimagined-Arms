package acup.gunfeel.host;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The writes and identity a version must preserve. */
public abstract class BulletBlocksContract {
    protected abstract WorldEdit edit();
    protected abstract BulletBlocks blocks();
    protected abstract WorldView view();
    protected abstract Object level();
    protected abstract Object pos();
    protected abstract int first();
    protected abstract int sameBlock();
    protected abstract int otherBlock();

    @Test
    void tokensBelongToBlocksRatherThanStates() {
        assertNotEquals(first(), sameBlock());
        assertSame(blocks().blockToken(first()), blocks().blockToken(sameBlock()));
        assertNotSame(blocks().blockToken(first()), blocks().blockToken(otherBlock()));
    }

    @Test
    void writesAreSynchronousAndCracksLeaveTheStateAlone() {
        edit().set(level(), pos(), first(), 3);
        assertEquals(first(), view().state(0, 0, 0));
        edit().cracks(level(), 17, pos(), 9);
        assertEquals(first(), view().state(0, 0, 0));
        edit().set(level(), pos(), sameBlock(), 3);
        assertEquals(sameBlock(), view().state(0, 0, 0));
        edit().destroy(level(), pos(), false, null);
        assertTrue(view().air(view().state(0, 0, 0)));
    }
}
