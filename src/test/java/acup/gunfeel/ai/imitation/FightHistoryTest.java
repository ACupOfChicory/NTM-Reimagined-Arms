package acup.gunfeel.ai.imitation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

// What a fighter's last moments add to a view: time in a cover, looks out of it, times since seen and hurt, and the
// numbers the model gets, the same however the view was filled.
class FightHistoryTest {
    static FightView view(long tick, double x, int cover, boolean enemyVisible, boolean exposed) {
        FightView v = new FightView();
        v.tick = tick;
        v.x = x;
        v.coverHere = cover;
        v.enemies = List.of(new FightView.Enemy(7, 30, 0, 0, enemyVisible, exposed, exposed));
        return v;
    }

    @Test
    void timeInCoverAndLooksOut() {
        FightHistory h = new FightHistory();
        FightView v = view(0, 0, 2, false, false);
        h.update(v);
        assertEquals(0, v.ticksInCover);
        v = view(40, 0.5, 2, false, false);
        h.update(v);
        assertEquals(40, v.ticksInCover, "a step within the cover is the same cover");
        // out a block aside into its sight: a look out
        v = view(44, 1.5, 0, true, true);
        h.update(v);
        assertEquals(1, v.peeksFromCover);
        assertEquals(0, v.ticksExposed);
        v = view(52, 1.5, 0, true, true);
        h.update(v);
        assertEquals(8, v.ticksExposed);
        assertEquals(1, v.peeksFromCover, "still out: the same look");
        v = view(56, 0, 2, false, false);
        h.update(v);
        v = view(60, 1.5, 0, true, true);
        h.update(v);
        assertEquals(2, v.peeksFromCover);
        assertEquals(0, v.ticksSinceSeen);
        // off to other cover far off: a new one, no looks out of it yet
        v = view(100, 10, 2, false, false);
        h.update(v);
        assertEquals(0, v.ticksInCover);
        assertEquals(0, v.peeksFromCover);
        assertEquals(40, v.ticksSinceSeen);
    }

    @Test
    void hurtAndFiredFromHealthAndRounds() {
        FightHistory h = new FightHistory();
        FightView v = view(0, 0, 0, true, true);
        v.health = 1;
        v.loaded = 30;
        h.update(v);
        assertFalse(v.hurt);
        assertFalse(v.fired);
        assertEquals(FightHistory.LONG, v.ticksSinceHurt);
        v = view(4, 0, 0, true, true);
        v.health = 0.8F;
        v.loaded = 27;
        h.update(v);
        assertTrue(v.hurt);
        assertTrue(v.fired);
        v = view(24, 0, 0, true, true);
        v.health = 0.8F;
        v.loaded = 30;
        h.update(v);
        assertFalse(v.fired, "a reload is no shot");
        assertEquals(20, v.ticksSinceHurt);
    }

    @Test
    void hiddenNeedsCoverAndNoEyes() {
        assertTrue(view(0, 0, 2, false, false).hidden());
        assertFalse(view(0, 0, 0, false, false).hidden(), "no cover: in the open");
        assertFalse(view(0, 0, 2, true, true).hidden(), "an enemy's eyes on it");
        FightView alone = new FightView();
        alone.coverHere = 2;
        assertFalse(alone.hidden(), "nobody to hide from");
    }

    @Test
    void theRowIsInRange() {
        FightView v = view(0, 0, 1, true, true);
        v.loaded = 10;
        v.capacity = 30;
        v.friendsNear = 9;
        double[] f = FightFeatures.of(v);
        assertEquals(FightFeatures.SIZE, f.length);
        assertEquals(FightFeatures.NAMES.size(), f.length);
        for (double x : f) assertTrue(x >= 0 && x <= 1, "about 0 to 1: " + x);
        assertEquals(1, f[0]);
        assertEquals(1 / 3.0, f[2], 1e-9);
        assertEquals(1, f[15], "low cover");
        assertEquals(1, f[17], "friends capped at 4");
        assertEquals(30 / 64.0, f[8], 1e-9);
    }

    @Test
    void aSpotTowardTheThreatAndAside() {
        // self at 0,0, threat 20 blocks +x; the spot 4 ahead and 3 aside
        double[] s = FightFeatures.spot(1, 5, 10, 2, true, false, 0, 0, 4, 3, 20, 0);
        assertEquals(FightFeatures.SPOT_SIZE, s.length);
        assertEquals(0.5, s[1], 1e-9);
        assertEquals(5 / 16.0, s[2], 1e-9);
        assertEquals(1, s[3]);
        assertEquals(4 / 16.0, s[6], 1e-9);
        assertEquals(3 / 16.0, s[7], 1e-9);
        double back = FightFeatures.spot(0, 5, 5, 1, false, true, 0, 0, -4, 0, 20, 0)[6];
        assertTrue(back < 0, "away from it");
    }
}
