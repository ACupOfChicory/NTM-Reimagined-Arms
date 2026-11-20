package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import acup.gunfeel.host.*;
import org.junit.jupiter.api.Test;

class BulletBlocksTest extends BulletBlocksContract {
    private final BulletBlockFake fake = new BulletBlockFake();
    @Override protected WorldEdit edit() { return fake; }
    @Override protected BulletBlocks blocks() { return fake; }
    @Override protected WorldView view() { return fake.view; }
    @Override protected Object level() { return BulletBlockFake.LEVEL; }
    @Override protected Object pos() { return new BulletBlockFake.Position(0, 0, 0); }
    @Override protected int first() { return 1; }
    @Override protected int sameBlock() { return 2; }
    @Override protected int otherBlock() { return 3; }

    @Test
    void missingPortsThrowForEveryOperation() {
        assertThrows(UnsupportedOperationException.class, () -> WorldEdit.MISSING.cracks(level(), 0, pos(), 0));
        assertThrows(UnsupportedOperationException.class, () -> WorldEdit.MISSING.destroy(level(), pos(), false, null));
        assertThrows(UnsupportedOperationException.class, () -> WorldEdit.MISSING.set(level(), pos(), 0, 3));
        assertThrows(UnsupportedOperationException.class, () -> BulletBlocks.MISSING.mayBreak(level(), null, pos(), 0));
        assertThrows(UnsupportedOperationException.class, () -> BulletBlocks.MISSING.parseState(level(), "air"));
        assertThrows(UnsupportedOperationException.class, () -> BulletBlocks.MISSING.blockToken(0));
        assertThrows(UnsupportedOperationException.class, () -> BulletBlocks.MISSING.gone(level(), 0, new Object()));
        assertThrows(UnsupportedOperationException.class, () -> BulletBlocks.MISSING.time(level()));
        assertThrows(UnsupportedOperationException.class, () -> BulletBlocks.MISSING.position(0));
        assertThrows(UnsupportedOperationException.class, () -> BulletBlocks.MISSING.stateObject(0));
        assertThrows(UnsupportedOperationException.class, () -> BulletBlocks.MISSING.rule(0));
        assertThrows(UnsupportedOperationException.class, () -> BulletBlocks.MISSING.water(BulletBlockFake.BULLET, BulletBlockFake.HIT));
        assertThrows(UnsupportedOperationException.class, () -> BulletBlocks.MISSING.shooter(BulletBlockFake.BULLET));
        assertThrows(UnsupportedOperationException.class, () -> BulletBlocks.MISSING.shotId(BulletBlockFake.BULLET));
        assertThrows(UnsupportedOperationException.class, () -> BulletBlocks.MISSING.location(BulletBlockFake.HIT));
    }
}
