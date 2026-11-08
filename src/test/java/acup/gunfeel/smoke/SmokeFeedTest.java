package acup.gunfeel.smoke;

import static acup.gunfeel.smoke.SmokeGrid.OX;
import static acup.gunfeel.smoke.SmokeGrid.OY;
import static acup.gunfeel.smoke.SmokeGrid.OZ;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import acup.gunfeel.gas.GasKind;
import net.minecraft.core.BlockPos;

/** A grenade that bursts in a cloud of its kind feeds that cloud: one smoke that grows, not two stacked. */
class SmokeFeedTest {
    private static final SmokeGrid.Mask FIELD = (x, y, z) -> y < OY;
    private static final BlockPos ORIGIN = new BlockPos(OX, OY, OZ);
    private static final SmokeKind SMOKE = SmokeKind.SMOKE;

    private static SmokeClouds.Cloud cloud(SmokeClouds all, BlockPos at, SmokeKind kind) {
        SmokeClouds.Cloud c = all.create(all.nextId(), at, kind, 77L, 1F, 0F, 1F, 0L);
        all.insert(c, null);
        return c;
    }

    private static void steps(SmokeClouds all, SmokeClouds.Cloud c, long to) {
        all.step(c, to, Integer.MAX_VALUE, null);
    }

    private static float most(SmokeGrid g) {
        float m = 0F;
        for (int i = 0; i < SmokeGrid.CELLS; i++) m = Math.max(m, g.density(i));
        return m;
    }

    private static int filled(SmokeGrid g, float least) {
        int n = 0;
        for (int i = 0; i < SmokeGrid.CELLS; i++) if (g.density(i) >= least) n++;
        return n;
    }

    // a second grenade 3 blocks off the first's burst, 60 ticks after it, at the cloud's age 60
    private static SmokeClouds.Feed second(int age) {
        return new SmokeClouds.Feed(age, OX + 3, OY, OZ, 1F, 0F, 1F);
    }

    @Test
    void aSecondGrenadeInTheCloudGrowsItOutwardNotTwiceAsDense() {
        SmokeClouds all = new SmokeClouds(c -> FIELD);
        SmokeClouds.Cloud one = cloud(all, ORIGIN, SMOKE);
        steps(all, one, 60);
        SmokeClouds other = new SmokeClouds(c -> FIELD);
        SmokeClouds.Cloud fed = cloud(other, ORIGIN, SMOKE);
        steps(other, fed, 60);
        assertTrue(fed.stir(second(fed.grid.age())));
        // 12 s after the second burst; two clouds stacked would be twice as dense (1.4) all the while
        steps(all, one, 300);
        steps(other, fed, 300);
        float fill = SMOKE.fill();
        // twice the smoke, and it went out: over half again the filled cells, the densest no more than a tenth over the one's
        assertEquals(2F, fed.grid.total() / one.grid.total(), 0.25F, "the smoke of two");
        assertTrue(filled(fed.grid, fill * 0.5F) > filled(one.grid, fill * 0.5F) * 1.5F,
                "filled cells: " + filled(fed.grid, fill * 0.5F) + " against " + filled(one.grid, fill * 0.5F));
        assertTrue(most(fed.grid) < most(one.grid) * 1.1F, "densest " + most(fed.grid) + " against " + most(one.grid));
        assertEquals(1, fed.grid.feeds());
    }

    @Test
    void aFedCloudThinsLaterAndLivesLonger() {
        SmokeClouds all = new SmokeClouds(c -> FIELD);
        SmokeClouds.Cloud c = cloud(all, ORIGIN, SMOKE);
        steps(all, c, 300);
        int before = c.grid.maxAge();
        c.stir(second(c.grid.age()));
        steps(all, c, 301);
        assertEquals(before + 300, c.grid.maxAge());
        // where a lone cloud would thin (release + hold) the fed one still holds what it has
        steps(all, c, SMOKE.release() + SMOKE.hold() + 40);
        float held = c.grid.total();
        steps(all, c, SMOKE.release() + SMOKE.hold() + 80);
        assertTrue(c.grid.total() > held * 0.97F, "not thinning yet: " + held + " -> " + c.grid.total());
    }

    @Test
    void aClientCopyAndAGridSentOverAgreeWithTheServer() throws Exception {
        SmokeClouds server = new SmokeClouds(c -> FIELD), client = new SmokeClouds(c -> FIELD);
        SmokeClouds.Cloud s = cloud(server, ORIGIN, SMOKE), k = cloud(client, ORIGIN, SMOKE);
        steps(server, s, 50);
        steps(client, k, 48);
        SmokeClouds.Feed f = second(s.grid.age() + SmokeClouds.STIR_AHEAD);
        s.stir(f);
        k.stir(f);
        steps(server, s, 120);
        steps(client, k, 120);
        assertEquals(s.grid.hash(), k.grid.hash(), "the client took it at the same age");
        // a client that went apart gets the grid: the feed comes with it and the copy goes on the same
        SmokeClouds late = new SmokeClouds(c -> FIELD);
        SmokeClouds.Cloud l = cloud(late, ORIGIN, SMOKE);
        steps(late, l, 120);
        SmokeNet.decode(l.grid, SmokeNet.encode(s.grid));
        l.stepped();
        assertEquals(1, l.grid.feeds());
        steps(server, s, 400);
        steps(late, l, 400);
        assertEquals(s.grid.hash(), l.grid.hash());
    }

    @Test
    void anEmptyGridWithAFeedStillGoesOver() throws Exception {
        SmokeGrid g = new SmokeGrid(SMOKE, 5L, 0F, 1F, 1F);
        g.scan(FIELD);
        assertTrue(g.feed(OX, OY, OZ, 0F, 1F, 1F));
        SmokeGrid h = new SmokeGrid(SMOKE, 5L, 0F, 1F, 1F);
        SmokeNet.decode(h, SmokeNet.encode(g));
        assertEquals(g.hash(), h.hash());
    }

    @Test
    void feedsStopAtTheMost() {
        SmokeGrid g = new SmokeGrid(SMOKE, 5L, 0F, 1F, 1F);
        g.scan(FIELD);
        g.place();
        for (int i = 0; i < SmokeGrid.FEEDS_MAX; i++) assertTrue(g.feed(OX, OY, OZ, 0F, 1F, 1F));
        assertFalse(g.feed(OX, OY, OZ, 0F, 1F, 1F));
        assertFalse(new SmokeGrid(SMOKE, 5L, 0F, 1F, 1F).feed(-1, OY, OZ, 0F, 1F, 1F), "outside the grid");
    }

    @Test
    void theHostIsACloudOfTheKindWithSmokeThereAndNoOther() {
        SmokeClouds all = new SmokeClouds(c -> FIELD);
        SmokeClouds.Cloud smoke = cloud(all, ORIGIN, SMOKE);
        SmokeClouds.Cloud gas = cloud(all, ORIGIN, GasKind.CHLORINE.smoke());
        steps(all, smoke, 60);
        steps(all, gas, 60);
        assertSame(smoke, all.host(ORIGIN.east(2), SMOKE));
        // a gas grenade in a gas cloud is more gas in that air: a cloud of its own, the densities add up
        assertNull(all.host(ORIGIN.east(2), GasKind.CHLORINE.smoke()));
        assertNull(all.host(ORIGIN.east(SmokeClouds.FEED_REACH + 1), SMOKE), "too far from its burst");
        assertNull(all.host(ORIGIN.above(SmokeGrid.SY), SMOKE), "over the grid");
        // a fresh cloud with no smoke out yet is no host
        SmokeClouds other = new SmokeClouds(c -> FIELD);
        cloud(other, ORIGIN, SMOKE);
        assertNull(other.host(ORIGIN, SMOKE));
        // a host with all its feeds taken is none
        for (int i = 0; i < SmokeGrid.FEEDS_MAX; i++) smoke.stir(new SmokeClouds.Feed(smoke.grid.age() + 1, OX, OY, OZ, 0F, 1F, 1F));
        assertNull(all.host(ORIGIN, SMOKE));
    }

    @Test
    void aFeedFromTheNetworkIsChecked() {
        assertTrue(SmokeClouds.sane(second(3)));
        assertFalse(SmokeClouds.sane(new SmokeClouds.Feed(3, 0, 0, 0, 0F, 1F, 0F)));
        assertFalse(SmokeClouds.sane(new SmokeClouds.Feed(3, 0, 0, 0, 2F, 1F, 1F)));
        assertFalse(SmokeClouds.sane(new SmokeClouds.Feed(-1, 0, 0, 0, 0F, 1F, 1F)));
        assertFalse(SmokeClouds.sane(new SmokeClouds.Feed(3, 0, 0, 0, Float.NaN, 1F, 1F)));
    }
}
