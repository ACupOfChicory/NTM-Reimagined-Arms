package acup.gunfeel.smoke;

import static acup.gunfeel.smoke.SmokeGrid.OX;
import static acup.gunfeel.smoke.SmokeGrid.OY;
import static acup.gunfeel.smoke.SmokeGrid.OZ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/** A blast's hole in a smoke cloud: how deep, how fast it closes, that nothing is lost and that the copies agree. */
class SmokeHoleTest {
    private static final SmokeGrid.Mask FIELD = (x, y, z) -> y < OY;
    private static final BlockPos ORIGIN = new BlockPos(OX, OY, OZ);
    // a GL-54 HE grenade's: its reach 3
    private static final double REACH = Math.min(SmokeHole.REACH_MAX, acup.gunfeel.gl54.Gl54Grenade.RADIUS * SmokeHole.REACH);

    private static SmokeClouds.Cloud cloud(SmokeClouds all) {
        return all.create(1, ORIGIN, SmokeKind.SMOKE, 77L, 1F, 0F, 1F, 0L);
    }

    private static SmokeClouds.Stir blast(int age) {
        return new SmokeClouds.Stir(age, true, OX + 0.5, OY + 0.5, OZ + 0.5, REACH, 0, 0, SmokeHole.MOST, 1F);
    }

    private static void steps(SmokeClouds all, SmokeClouds.Cloud c, long to) {
        all.step(c, to, Integer.MAX_VALUE, null);
    }

    @Test
    void theHoleClosesFromTheEdgeIn2To3Seconds() {
        SmokeClouds all = new SmokeClouds(c -> FIELD), none = new SmokeClouds(c -> FIELD);
        SmokeClouds.Cloud c = cloud(all), n = cloud(none);
        steps(all, c, 80);
        steps(none, n, 80);
        float before = c.grid.density(OX, OY, OZ), total = c.grid.total();
        c.stir(blast(c.grid.age()));
        float after = c.grid.density(OX, OY, OZ), edge = c.grid.density(OX + 4, OY, OZ);
        assertTrue(after <= before * 0.06F, "the middle is cleared: " + after + " of " + before);
        assertEquals(total, c.grid.total() + c.grid.heldTotal(), total * 1e-4F, "nothing is gone");
        StringBuilder line = new StringBuilder("smoke hole, middle " + before + " -> " + after);
        float[] at = new float[6];
        for (int s = 1; s <= 5; s++) {
            steps(all, c, 80 + s * 20);
            steps(none, n, 80 + s * 20);
            at[s] = c.grid.density(OX, OY, OZ) / n.grid.density(OX, OY, OZ);
            line.append(", ").append(s).append(" s ").append(at[s]);
        }
        System.out.println(line + "; edge right after " + edge);
        assertTrue(at[1] < 0.3F, "1 s: still a hole " + at[1]);
        assertTrue(at[2] > 0.5F, "2 s: mostly back " + at[2]);
        assertTrue(at[3] > 0.9F, "3 s: closed " + at[3]);
        assertTrue(c.grid.heldTotal() < 0.01F * total, "nothing left held at 5 s");
        // its edge came back before its middle
        SmokeClouds again = new SmokeClouds(c2 -> FIELD);
        SmokeClouds.Cloud a = cloud(again);
        steps(again, a, 80);
        a.stir(blast(a.grid.age()));
        steps(again, a, 90);
        assertTrue(a.grid.density(OX + 3, OY, OZ) > a.grid.density(OX, OY, OZ) * 2F, "half a second on the edge fills first");
    }

    @Test
    void aClientCopyAndAGridSentOverAgree() throws java.io.IOException {
        SmokeClouds server = new SmokeClouds(c -> FIELD), client = new SmokeClouds(c -> FIELD), fresh = new SmokeClouds(c -> FIELD);
        SmokeClouds.Cloud s = cloud(server), k = cloud(client), f = cloud(fresh);
        steps(server, s, 80);
        steps(client, k, 78);
        SmokeClouds.Stir st = blast(s.grid.age() + SmokeClouds.STIR_AHEAD);
        s.stir(st);
        k.stir(st);
        steps(server, s, 95);
        steps(client, k, 95);
        assertEquals(s.grid.hash(), k.grid.hash(), "the one behind took it at the same age");
        // a client that went apart gets the grid with what is held in it
        steps(fresh, f, 10);
        SmokeNet.decode(f.grid, SmokeNet.encode(s.grid));
        assertEquals(s.grid.hash(), f.grid.hash());
        steps(server, s, 160);
        steps(client, k, 160);
        steps(fresh, f, 160);
        assertEquals(s.grid.hash(), k.grid.hash());
        assertEquals(s.grid.hash(), f.grid.hash(), "and it closes the same");
    }

    @Test
    void anEmptyGridStillGoesOver() throws java.io.IOException {
        SmokeGrid g = new SmokeGrid(SmokeKind.SMOKE, 1L, 1F, 0F, 1F), h = new SmokeGrid(SmokeKind.SMOKE, 1L, 1F, 0F, 1F);
        SmokeNet.decode(h, SmokeNet.encode(g));
        assertEquals(g.hash(), h.hash());
    }
}
