package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// The staff's map (stage 7, C1): what the enemy sees, and ways that keep out of its sight, out of deep water and off
// cliffs, not much longer than the straight one.
class HqGridTest {
    static final int C = HqGrid.CELL;

    /** a flat field at height 64, all known, nothing seen */
    static HqGrid field() {
        HqGrid g = new HqGrid(0, 0);
        for (int i = 0; i < HqGrid.N; i++)
            for (int k = 0; k < HqGrid.N; k++) g.set(i, k, 64, 0, 0, 0);
        return g;
    }

    static double mid(int cell) {
        return (cell + 0.5) * C;
    }

    /** the route goes through this cell */
    static boolean through(HqGrid g, HqGrid.Route r, double fx, double fz, int ci, int ck) {
        double px = fx, pz = fz;
        for (int j = 0; j < r.size(); j++) {
            double len = Math.hypot(r.x(j) - px, r.z(j) - pz);
            for (int s = 0; s <= Math.ceil(len); s++) {
                double t = len == 0 ? 0 : s / Math.ceil(len);
                if (g.cellX(px + (r.x(j) - px) * t) == ci && g.cellZ(pz + (r.z(j) - pz) * t) == ck) return true;
            }
            px = r.x(j);
            pz = r.z(j);
        }
        return false;
    }

    /** all the way (its end too), the route keeps out of the enemy's sight much more than the straight line */
    static void assertHidden(HqGrid g, HqGrid.Route r) {
        double way = g.seenPart(r.fromX(), r.fromZ(), r.points(), 0), straight = g.seenPart(r.fromX(), r.fromZ(), new double[] {r.toX(), r.toZ()}, 0);
        assertTrue(way < straight - 0.2, way + " seen against " + straight + " straight");
    }

    @Test
    void onAnOpenFieldItGoesAboutStraight() {
        HqGrid g = field();
        g.sight(mid(32), mid(5));
        HqGrid.Route r = g.route(mid(10), mid(60), mid(40), mid(12));
        assertNotNull(r);
        assertTrue(r.length() <= r.straight() * 1.1, r.length() + " against " + r.straight());
        // points at most LEG apart, the last where it was going
        assertEquals(mid(40), r.x(r.size() - 1), 1e-9);
        assertEquals(mid(12), r.z(r.size() - 1), 1e-9);
        // points at most LEG apart, not many more than the length asks for
        double px = mid(10), pz = mid(60);
        for (int j = 0; j < r.size(); j++) {
            assertTrue(Math.hypot(r.x(j) - px, r.z(j) - pz) <= HqGrid.LEG + 1e-9);
            px = r.x(j);
            pz = r.z(j);
        }
        assertTrue(r.size() <= r.length() / (HqGrid.LEG / 2) + 2, r.size() + " points for " + r.length());
    }

    @Test
    void theEnemySeesTheOpenNotBehindAHillNorPastItsReach() {
        HqGrid g = field();
        // a hill ten high across, cells 20..44 at row 20 (slopes on rows 19 and 21)
        for (int i = 20; i <= 44; i++) {
            g.set(i, 19, 69, 0, 0, 0);
            g.set(i, 20, 74, 0, 0, 0);
            g.set(i, 21, 69, 0, 0, 0);
        }
        g.sight(mid(32), mid(10));
        assertTrue(g.seen(32, 14) > 0.9);
        // just behind the hill: hidden
        assertTrue(g.seen(32, 23) < 0.1, "behind the hill " + g.seen(32, 23));
        // out to the side of it: seen
        assertTrue(g.seen(20, 14) > 0.9);
        // past its reach (128 blocks, 16 cells): not
        assertTrue(g.seen(32, 30) < 0.1 && g.seen(60, 60) == 0);
    }

    @Test
    void underwoodHidesAFewCellsIn() {
        // a wood with underwood round its trees (a fifth of the columns in leaves at a look's height): its edge seen,
        // a few cells in not
        HqGrid g = field();
        for (int i = 0; i < HqGrid.N; i++)
            for (int k = 20; k <= 30; k++) g.set(i, k, 64, 0.22, 0, 0);
        g.sight(mid(32), mid(12));
        assertTrue(g.seen(32, 19) > 0.9);
        assertTrue(g.seen(32, 20) > 0.5 && g.seen(32, 20) < 0.95, "the wood's edge " + g.seen(32, 20));
        assertTrue(g.seen(32, 21) < g.seen(32, 20), "a cell in " + g.seen(32, 21));
        assertTrue(g.seen(32, 24) == 0, "deep in the wood " + g.seen(32, 24));
        // leaves all through at a look's height: hardly past the edge
        HqGrid w = field();
        for (int i = 0; i < HqGrid.N; i++)
            for (int k = 20; k <= 30; k++) w.set(i, k, 64, 1, 0, 0);
        w.sight(mid(32), mid(12));
        assertTrue(w.seen(32, 20) < 0.5 && w.seen(32, 21) == 0, "a wall of leaves " + w.seen(32, 20) + ", " + w.seen(32, 21));
    }

    @Test
    void itKeepsBehindTheHillWhereItCan() {
        HqGrid g = field();
        // a round hill twelve high, four cells across its foot, the enemy north of it: the way along behind it dips
        // into its shadow (and out of the enemy's reach), not straight across where it sees round the hill's sides
        for (int i = 0; i < HqGrid.N; i++)
            for (int k = 0; k < HqGrid.N; k++) {
                double r = Math.hypot(i - 32, k - 21);
                if (r < 4) g.set(i, k, 64 + 6 * (1 + Math.cos(Math.PI * r / 4)), 0, 0, 0);
            }
        g.sight(mid(32), mid(12));
        HqGrid.Route r = g.route(mid(22), mid(24), mid(42), mid(24));
        assertNotNull(r);
        assertHidden(g, r);
        assertTrue(r.length() <= r.straight() * HqGrid.DETOUR, r.length() + " against " + r.straight());
    }

    @Test
    void itGoesThroughTheWoodRatherThanTheOpen() {
        HqGrid g = field();
        // a wood west of the way, from the start most of the way to the enemy
        for (int i = 18; i <= 27; i++)
            for (int k = 14; k <= 40; k++) g.set(i, k, 64, 1, 0, 0);
        g.sight(mid(32), mid(8));
        HqGrid.Route r = g.route(mid(28), mid(42), mid(28), mid(16));
        assertNotNull(r);
        assertHidden(g, r);
        assertTrue(r.length() <= r.straight() * HqGrid.DETOUR);
    }

    @Test
    void itWadesTheFordRatherThanSwims() {
        HqGrid g = field();
        // a river right across, deep, a ford four cells aside
        for (int i = 0; i < HqGrid.N; i++) g.set(i, 30, 63, 0, 1, i == 24 ? 0 : 1);
        HqGrid.Route r = g.route(mid(20), mid(45), mid(20), mid(15));
        assertNotNull(r);
        assertTrue(through(g, r, mid(20), mid(45), 24, 30), "by the ford");
        for (int i = 0; i < HqGrid.N; i++) if (i != 24) assertTrue(!through(g, r, mid(20), mid(45), i, 30), "swam at " + i);
    }

    @Test
    void noWayUpACliffButThroughItsGap() {
        HqGrid g = field();
        // a wall twenty high right across, a gap at cell 50
        for (int i = 0; i < HqGrid.N; i++) if (i != 50) g.set(i, 30, 84, 0, 0, 0);
        HqGrid.Route r = g.route(mid(40), mid(45), mid(40), mid(15));
        assertNotNull(r);
        assertTrue(through(g, r, mid(40), mid(45), 50, 30), "through the gap");
        // walled all across: none
        g.set(50, 30, 84, 0, 0, 0);
        assertNull(g.route(mid(40), mid(45), mid(40), mid(15)));
    }

    @Test
    void aPlaceOffTheMapIsTakenAtItsEdge() {
        HqGrid g = HqGrid.around(1000, 1000);
        for (int i = 0; i < HqGrid.N; i++)
            for (int k = 0; k < HqGrid.N; k++) g.set(i, k, 64, 0, 0, 0);
        HqGrid.Route r = g.route(1000, 1000, 1000, 2000);
        assertNotNull(r);
        double z = r.z(r.size() - 1);
        assertTrue(z > g.z0 + HqGrid.N * C - C && z < g.z0 + HqGrid.N * C, "at the edge: " + z);
    }

    @Test
    void aWayOffTheMapIsPartAndKnowsWhereItWasAsked() {
        HqGrid g = HqGrid.around(1000, 1000);
        for (int i = 0; i < HqGrid.N; i++)
            for (int k = 0; k < HqGrid.N; k++) g.set(i, k, 64, 0, 0, 0);
        HqGrid.Route far = g.route(1000, 1000, 1000, 2000), near = g.route(1000, 1000, 1000, 1100);
        assertTrue(far.partial() && !near.partial());
        assertEquals(2000, far.toZ(), 1e-9);
        assertEquals(1000, far.fromX(), 1e-9);
    }

    @Test
    void noWayAslantBetweenTwoCliffs() {
        HqGrid g = field();
        // a wall twenty high right across with its two halves meeting only at a corner: (30, 30) and (31, 31) high,
        // (31, 30) and (30, 31) low would let a step aslant from (30, 31) to (31, 30) past the corners; walled
        for (int i = 0; i < HqGrid.N; i++) g.set(i, 30, i <= 30 ? 84 : 64, 0, 0, 0);
        for (int i = 0; i < HqGrid.N; i++) g.set(i, 31, i >= 31 ? 84 : 64, 0, 0, 0);
        assertNull(g.route(mid(40), mid(45), mid(40), mid(15)));
    }

    @Test
    void theLastBlocksOfAWayAreNotCountedInItsPartSeen() {
        HqGrid g = field();
        // seen only round the end: cells 30..34 of row 10
        for (int i = 30; i <= 34; i++) g.seen[HqGrid.index(i, 10)] = 1;
        double[] to = {mid(32), mid(10)};
        // from 160 blocks south of it: the seen part lies wholly in its last END blocks
        assertEquals(0, g.seenPart(mid(32), mid(30), to, HqGrid.END), 1e-9);
        assertTrue(g.seenPart(mid(32), mid(30), to, 0) > 0);
        // a way no longer than END: all of it counted
        assertEquals(1, g.seenPart(mid(32) + 4, mid(10), to, HqGrid.END), 1e-9);
    }

    @Test
    void anEnemyAnywhereInItsCircleSeesACellAsMuchAsThePartOfItThatSeesIt() {
        HqGrid g = field();
        // a wall eight high on row 36 from the west edge to cell 31; an enemy about (32, 40), its circle two cells round
        for (int i = 0; i <= 31; i++) g.set(i, 36, 72, 0, 0, 0);
        g.sight(mid(32), mid(40), 2 * C);
        // open to the east of the wall: seen from all of the circle
        assertTrue(g.seen(40, 32) > 0.99, "open " + g.seen(40, 32));
        // well behind it: from none
        assertTrue(g.seen(20, 32) < 0.01, "behind the wall " + g.seen(20, 32));
        // just past the wall's end: from the circle's east side, not from its middle nor its west
        float part = g.seen(31, 33);
        assertTrue(part > 0.05 && part < 0.95, "partly " + part);
    }
}
