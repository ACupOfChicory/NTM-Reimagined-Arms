package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SuppressionScaleTest {
    @Test
    void forces() {
        // a bullet (10 blocks a tick) at 2 blocks: 18 × 0.875 × 1.8
        assertEquals(28.35F, SuppressionScale.pass(2, 10, 3.5), 1e-3F);
        assertEquals(0F, SuppressionScale.pass(4, 10, 3.5));
        assertTrue(SuppressionScale.pass(3.5, 10, 3.5) >= 25F);
        // a hit right by the eyes at bullet speed: 24 × 1 × 2.5; at the range's end a fifth
        assertEquals(60F, SuppressionScale.impact(0, 10, 8), 1e-3F);
        assertEquals(12F, SuppressionScale.impact(8, 10, 8), 1e-3F);
        assertEquals(0F, SuppressionScale.impact(8.1, 10, 8));
        // NTM's hand grenade (size 7.5) at 4, 10, 14 blocks: less and less, none at the range
        float b4 = SuppressionScale.blast(4, 7.5F, 14), b10 = SuppressionScale.blast(10, 7.5F, 14), b14 = SuppressionScale.blast(14, 7.5F, 14);
        assertTrue(b4 > b10 && b10 > 0F && b14 == 0F, b4 + " " + b10 + " " + b14);
        assertTrue(SuppressionScale.blast(0, 16F, 14) <= 58F);
        // a big blast reaches farther, at most three times
        assertEquals(42.0, SuppressionScale.blastRange(80F, 14), 1e-9);
        assertTrue(SuppressionScale.blast(20, 24F, 14) > 0F);
    }

    @Test
    void climbsFastFallsInFiveSeconds() {
        SuppressionScale s = new SuppressionScale();
        s.add(100F, false, 0);
        int t = 0;
        while (s.level() < s.target() - 1e-3F && t < 40) {
            s.tick(1F);
            t++;
        }
        assertTrue(t <= 15, "climbed in " + t + " ticks");
        float top = s.level();
        int down = 0;
        while (s.level() > 0F && down < 400) {
            s.tick(1F);
            down++;
        }
        assertTrue(down >= 70 && down <= 105, "from " + top + " to none in " + down + " ticks");
    }

    @Test
    void burstCountsMore() {
        SuppressionScale s = new SuppressionScale();
        assertEquals(10F, s.add(10F, true, 0), 1e-4F);
        assertEquals(10F, s.add(10F, true, 5), 1e-4F);
        assertEquals(13.5F, s.add(10F, true, 10), 1e-4F);
        for (int i = 0; i < 6; i++) s.add(1F, true, 12 + i);
        // at most 1.35^4
        assertEquals(10F * (float) Math.pow(1.35, 4), s.add(10F, true, 20), 1e-3F);
        // a blast is not a burst's, and a gap over 2 s ends it
        assertEquals(10F, s.add(10F, false, 21), 1e-4F);
        assertEquals(10F, s.add(10F, true, 100), 1e-4F);
        assertEquals(100F, s.target(), 1e-4F);
    }

    @Test
    void neverDarkerThanForty() {
        float worst = 0F;
        for (float r = 0F; r <= 1.0001F; r += 0.01F) {
            int n = SuppressionScale.rings(r);
            for (int i = 0; i < n; i++) {
                float fill = SuppressionScale.fill(r, 1F), a = SuppressionScale.ringAlpha(i, n, r, 1F);
                float dark = 1F - (1F - fill) * (1F - a);
                worst = Math.max(worst, dark);
            }
        }
        assertTrue(worst <= 0.4001F, "darkest " + worst);
        assertTrue(worst >= 0.38F, "darkest " + worst);
        assertEquals(0.22F, SuppressionScale.fill(1F, 1F), 1e-5F);
        // the player's half takes half
        assertTrue(SuppressionScale.ringDark(0, 30, 1F, 0.5F) <= 0.2001F);
        assertEquals(0F, SuppressionScale.fill(0.005F, 1F));
    }

    @Test
    void muffles() {
        assertEquals(1F, SuppressionScale.muffle(0F, 1F, false));
        assertEquals(0.4F, SuppressionScale.muffle(1F, 1F, false), 1e-5F);
        assertEquals(0.79F, SuppressionScale.muffle(1F, 1F, true), 1e-5F);
        assertEquals(1F, SuppressionScale.muffle(1F, 0F, false));
    }

    @Test
    void flinch() {
        assertEquals(0.11F, SuppressionScale.flinch(0F, 0.22F), 1e-5F);
        assertEquals(0.66F, SuppressionScale.flinch(150F, 0.22F), 1e-5F);
    }
}
