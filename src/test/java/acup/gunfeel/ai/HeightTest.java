package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import acup.gunfeel.Vec3d;

// Height in the choice of cover: a head against the sky (Skyline) on hand-made land, and the cover query's weights for
// height over the threat and the skyline.
class HeightTest {
    /** a plain at 64; a hill along z = 0 rising to 74, its crest at x 0, falling either way a block in two */
    static int hill(int x, int z) {
        return 64 + Math.max(0, 10 - Math.abs(x) / 2);
    }

    @Test
    void aHeadOnACrestSeenFromBelowIsAgainstTheSkyOnAPlainOrBelowTheCrestNot() {
        Skyline.Land plain = (x, z) -> 64, land = HeightTest::hill;
        // on a plain, a man looked at level: the land right behind him
        assertFalse(Skyline.against(plain, 0.5, 65.6, 0.5, 30.5, 65.6, 0.5));
        // on the crest, from the plain to the west: past him the land falls away
        assertTrue(Skyline.against(land, -40.5, 65.6, 0.5, 0.5, 75.6, 0.5));
        // a little down the slope towards the one who looks: the crest behind him
        assertFalse(Skyline.against(land, -40.5, 65.6, 0.5, -6.5, 72.6, 0.5));
        // looked at from the crest, down on the plain: the ground behind him
        assertFalse(Skyline.against(land, 0.5, 75.6, 0.5, -30.5, 65.6, 0.5));
        // the land behind not known: not said
        assertFalse(Skyline.against((x, z) -> Integer.MIN_VALUE, -40.5, 65.6, 0.5, 0.5, 75.6, 0.5));
        assertFalse(Skyline.against(null, -40.5, 65.6, 0.5, 0.5, 75.6, 0.5));
        // a wood behind the crest: its crowns stand behind him
        Skyline.Land wooded = (x, z) -> hill(x, z) + (x > 2 ? 8 : 0);
        assertFalse(Skyline.against(wooded, -40.5, 65.6, 0.5, 0.5, 75.6, 0.5));
    }

    @Test
    void coverHigherOverTheThreatCountsForMoreAndAHeadAgainstTheSkyCosts() {
        PathSearchTest.Grid g = CoverQueryTest.yard();
        // the threat three blocks under the yard
        Vec3d low = new Vec3d(12.3, -1.4, 2.5);
        CoverQuery.Params flat = new CoverQuery.Params(), up = new CoverQuery.Params();
        up.up = CoverQuery.UP;
        List<CoverQuery.Spot> a = CoverQuery.find(g, CoverQueryTest.rays(g), CoverQueryTest.SELF, List.of(low), flat, k -> false, 6);
        List<CoverQuery.Spot> b = CoverQuery.find(g, CoverQueryTest.rays(g), CoverQueryTest.SELF, List.of(low), up, k -> false, 6);
        assertFalse(a.isEmpty());
        CoverQuery.Spot one = a.get(0), same = b.stream().filter(s -> s.cell() == one.cell()).findFirst().orElseThrow();
        assertEquals(3 * CoverQuery.UP, same.score() - one.score(), 1e-9, "three blocks over the threat");
        // the land falling away behind the yard: from the threat level with it, each head is against the sky
        CoverQuery.Params sky = new CoverQuery.Params();
        sky.land = (x, z) -> -20;
        List<CoverQuery.Spot> c = CoverQuery.find(g, CoverQueryTest.rays(g), CoverQueryTest.SELF, List.of(CoverQueryTest.NORTH), new CoverQuery.Params(), k -> false, 6);
        List<CoverQuery.Spot> d = CoverQuery.find(g, CoverQueryTest.rays(g), CoverQueryTest.SELF, List.of(CoverQueryTest.NORTH), sky, k -> false, 6);
        CoverQuery.Spot best = c.get(0), skylined = d.stream().filter(s -> s.cell() == best.cell()).findFirst().orElseThrow();
        assertEquals(CoverQuery.SKYLINE, best.score() - skylined.score(), 1e-9);
    }
}
