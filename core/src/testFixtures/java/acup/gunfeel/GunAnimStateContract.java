package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * What every version's gun animation (GunAnimState) must say while nothing plays: no animation, no type, no time, no
 * bus. A version's test extends this with its implementation, nothing played on it.
 */
public abstract class GunAnimStateContract {
    /** The version's implementation, with no animation playing. */
    protected abstract GunAnimState idle();

    @Test
    void nothingPlaying() {
        GunAnimState a = idle();
        assertFalse(a.playing());
        assertNull(a.type());
        assertEquals(0F, a.millis());
        assertFalse(a.previewing());
        assertFalse(a.hasBus("MAG"));
        assertEquals(0, a.busTime("MAG"));
        assertNull(a.busAt("MAG", 100));
        assertNull(a.busNow("MAG"));
    }
}
