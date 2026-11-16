package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// Where the staff's way crosses water: the place on the near bank, how, where to gather past the far bank (the crossing's doctrine).
class StaffCrossTest {
    static final double C = HqGrid.CELL;

    /** a river along cell row 16, deep but at cell column ford (-1: none) where it is shallow */
    static HqGrid river(int ford) {
        HqGrid g = HqGridTest.field();
        for (int i = 0; i < HqGrid.N; i++) g.set(i, 16, 63, 0, 1, i == ford ? 0 : 1);
        return g;
    }

    @Test
    void byTheFordNearByRatherThanSwimming() {
        HqGrid g = river(22);
        double x = HqGridTest.mid(16);
        HqGrid.Route r = g.route(x, HqGridTest.mid(10), x, HqGridTest.mid(22));
        StaffCross.Site s = StaffCross.site(g, r);
        assertNotNull(s);
        assertEquals(AiCrossing.Kind.FORD, s.kind());
        // at the ford's cell: on the near bank before it, gathering past the far bank
        assertEquals(22, g.cellX(s.x()), 1);
        assertTrue(s.z() < 16 * C && s.farZ() > 17 * C, "near " + s.z() + ", far " + s.farZ());
    }

    @Test
    void swimmingWhereThereIsNoFord() {
        HqGrid g = river(-1);
        double x = HqGridTest.mid(16);
        StaffCross.Site s = StaffCross.site(g, g.route(x, HqGridTest.mid(10), x, HqGridTest.mid(22)));
        assertNotNull(s);
        assertEquals(AiCrossing.Kind.SWIM, s.kind());
        assertTrue(s.z() < 16 * C && s.farZ() > 17 * C, "near " + s.z() + ", far " + s.farZ());
    }

    @Test
    void noCrossingOnDryGround() {
        HqGrid g = HqGridTest.field();
        double x = HqGridTest.mid(16);
        assertNull(StaffCross.site(g, g.route(x, HqGridTest.mid(10), x, HqGridTest.mid(22))));
    }

    @Test
    void pastTheFarBankOnly() {
        // from (0, 0) to gather at (0, 10)
        assertTrue(!AiCommander.past(0, -5, 0, 0, 0, 10));
        assertTrue(!AiCommander.past(3, 6, 0, 0, 0, 10));
        assertTrue(AiCommander.past(-2, 10, 0, 0, 0, 10));
        assertTrue(AiCommander.past(0, 14, 0, 0, 0, 10));
    }
}
