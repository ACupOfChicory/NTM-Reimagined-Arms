package acup.gunfeel.smoke;

import static acup.gunfeel.smoke.SmokeGrid.OX;
import static acup.gunfeel.smoke.SmokeGrid.OY;
import static acup.gunfeel.smoke.SmokeGrid.OZ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import acup.gunfeel.gas.GasKind;

// A level's clouds as core keeps them, with no game: a cloud of core's own over a field (open air above y = OY).
// The version's tests run the same through its own cloud (26.2: SmokeCloudsTest).
class SmokeCloudSetTest {
    private static final SmokeGrid.Mask FIELD = (x, y, z) -> y < OY;

    // the cell (0, 0, 0) of every cloud here is the world's block (0, 0, 0) while its box has not moved
    static final class Cloud extends SmokeCloud {
        Cloud(int id, SmokeKind kind, long seed, long born) {
            super(id, new SmokeGrid(kind, seed, 1F, 0.2F, 1F, 0F, 0F), OX, OY, OZ, born, 1F, 0.2F, 1F);
        }
    }

    static final class Set extends SmokeCloudSet<Cloud> {
        Set() {
            super(c -> FIELD);
        }

        Cloud born(int id, SmokeKind kind, long born) {
            Cloud c = new Cloud(id, kind, 7L * id, born);
            c.grid.scan(masks.apply(c));
            c.grid.place();
            insert(c, null);
            return c;
        }
    }

    @Test
    void pastTheLimitTheOldestGo() {
        Set all = new Set();
        List<Cloud> gone = new ArrayList<>();
        for (int id = 1; id <= SmokeCloudSet.LIMIT; id++) all.born(id, SmokeKind.SMOKE, 0);
        Cloud last = new Cloud(SmokeCloudSet.LIMIT + 1, SmokeKind.SMOKE, 1, 0);
        all.insert(last, gone::add);
        assertEquals(SmokeCloudSet.LIMIT, all.clouds().size());
        assertEquals(1, gone.size());
        assertEquals(1, gone.getFirst().id);
        assertSame(last, all.cloud(last.id));
        assertNull(all.cloud(1));
    }

    @Test
    void aGrenadeFeedsSmokeButNotAGas() {
        Set all = new Set();
        Cloud smoke = all.born(1, SmokeKind.SMOKE, 0);
        Cloud gas = all.born(2, GasKind.CHLORINE.smoke(), 0);
        all.tick(60, 60);
        assertTrue(SmokeCloudSet.feeds(SmokeKind.SMOKE));
        assertFalse(SmokeCloudSet.feeds(GasKind.CHLORINE.smoke()));
        assertFalse(SmokeCloudSet.feeds(SmokeKind.WP));
        assertSame(smoke, all.host(OX, OY, OZ, SmokeKind.SMOKE));
        assertNull(all.host(OX, OY, OZ, GasKind.CHLORINE.smoke()));
        assertNull(all.host(OX + 40, OY, OZ, SmokeKind.SMOKE), "too far from the burst");
        assertEquals(60, gas.grid.age());
    }

    @Test
    void theSmokeStopsTheSightAlongTheLineThroughIt() {
        Set all = new Set();
        Cloud c = all.born(1, SmokeKind.SMOKE, 0);
        all.tick(80, 80);
        float through = all.opticalDepth(OX - 20, OY + 1.5, OZ + 0.5, OX + 20, OY + 1.5, OZ + 0.5);
        float above = all.opticalDepth(OX - 20, OY + 60, OZ + 0.5, OX + 20, OY + 60, OZ + 0.5);
        assertTrue(through > 3F, "dense smoke across: " + through);
        assertEquals(0F, above);
        assertEquals(through, SmokeKind.SMOKE.extinction()
                * SmokeCloudSet.walk(c, OX - 20, OY + 1.5, OZ + 0.5, OX + 20, OY + 1.5, OZ + 0.5), 1e-6F);
    }

    @Test
    void aStirWaitsForItsAge() {
        Set all = new Set();
        Cloud c = all.born(1, SmokeKind.SMOKE, 0);
        all.tick(40, 40);
        float before = c.density(OX + 0.5, OY + 1.5, OZ + 0.5);
        record Line(int age, boolean blast, double x0, double y0, double z0, double x1, double y1, double z1, float a, float b)
                implements SmokeCloud.StirEvent {
        }
        assertTrue(c.stir(new Line(c.grid.age() + SmokeCloudSet.STIR_AHEAD, false, OX - 10, OY + 1.5, OZ + 0.5,
                OX + 10, OY + 1.5, OZ + 0.5, 0F, 0.5F)));
        assertEquals(before, c.density(OX + 0.5, OY + 1.5, OZ + 0.5), "not before its age");
        all.tick(40 + SmokeCloudSet.STIR_AHEAD + 1, 10);
        assertTrue(c.density(OX + 0.5, OY + 1.5, OZ + 0.5) < before * 0.5F, "the path cleared at its age");
    }
}
