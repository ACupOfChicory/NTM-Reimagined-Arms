package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.host.Body;

class AiCommanderBaseTest {
    private int pairedAgain, keptTime, upAtOnce, otherDied, graced;

    private static final class Job extends AiCommanderBase.Job<AiBotBase, Job> {
        Job(StaffPlan.Kind kind, List<AiBotBase> bots) {
            super(kind, 42, bots, new int[] {7});
        }
    }

    @BeforeEach
    void saveCounters() {
        pairedAgain = AiCommanderBase.pairedAgain;
        keptTime = AiCommanderBase.keptTime;
        upAtOnce = AiCommanderBase.upAtOnce;
        otherDied = AiCommanderBase.otherDied;
        graced = AiCommanderBase.graced;
        AiCommanderBase.pairedAgain = AiCommanderBase.keptTime = AiCommanderBase.upAtOnce = AiCommanderBase.otherDied = AiCommanderBase.graced = 0;
    }

    @AfterEach
    void restoreCounters() {
        AiCommanderBase.pairedAgain = pairedAgain;
        AiCommanderBase.keptTime = keptTime;
        AiCommanderBase.upAtOnce = upAtOnce;
        AiCommanderBase.otherDied = otherDied;
        AiCommanderBase.graced = graced;
    }

    private static Job job() {
        return new Job(StaffPlan.Kind.FLANK, List.of());
    }

    private static HqGrid.Route route(double... points) {
        return new HqGrid.Route(0, 0, points[points.length - 2], points[points.length - 1], points, 30, 30, 0, 0);
    }

    @Test
    void aheadOnSegment() {
        HqGrid.Route r = route(10, 0, 10, 10);
        assertEquals(0, AiCommanderBase.ahead(r, 5, 0));
        assertEquals(1, AiCommanderBase.ahead(r, 10, 5));
    }

    @Test
    void aheadClampsBeforeStart() {
        // The first line's extension is nearer, but its segment is farther.
        assertEquals(1, AiCommanderBase.ahead(route(10, 0, -10, 2), -10, 0));
    }

    @Test
    void aheadClampsAfterEnd() {
        assertEquals(1, AiCommanderBase.ahead(route(10, 0, 20, 2), 20, 0));
    }

    @Test
    void aheadHandlesZeroLength() {
        assertEquals(0, AiCommanderBase.ahead(route(0, 0, 10, 0), 0, 0));
        assertEquals(1, AiCommanderBase.ahead(route(0, 0, 10, 0), 5, 0));
    }

    @Test
    void pastIncludesEndpoint() {
        assertFalse(AiCommanderBase.past(4, 6, 2, 3, 6, 9));
        assertTrue(AiCommanderBase.past(6, 9, 2, 3, 6, 9));
        assertTrue(AiCommanderBase.past(8, 12, 2, 3, 6, 9));
        assertTrue(AiCommanderBase.past(-100, -100, 2, 3, 2, 3));
        assertTrue(AiCommanderBase.past(-100, -100, 0, 0, 1e-5, 1e-5));
    }

    @Test
    void weightByRole() {
        for (AiRole role : AiRole.values()) {
            double expected = switch (role) {
                case RIFLE -> 1;
                case BASE -> 1.5;
                case MARKSMAN, GRENADIER -> 1.2;
                case MEDIC -> 0.7;
                case SUPPLIER -> 0.8;
            };
            assertEquals(expected, AiCommanderBase.weight(role.ordinal()), role.name());
        }
        assertEquals(3, AiCommanderBase.weight(-1));
        assertEquals(3, AiCommanderBase.weight(IntelBook.PLAYER));
        assertEquals(3, AiCommanderBase.weight(AiRole.values().length));
        assertEquals(3, AiCommanderBase.weight(Integer.MAX_VALUE));
    }

    private static void consumed(Job f, Job p) {
        assertEquals(Long.MAX_VALUE, f.dueWas);
        assertEquals(Long.MAX_VALUE, p.dueWas);
        assertFalse(f.otherDead);
        assertFalse(p.otherDead);
    }

    @Test
    void freshTimeWhenNeitherHadAPair() {
        Job f = job(), p = job();
        f.otherDead = true;
        assertEquals(1000, AiCommanderBase.time(f, p, 1000, 100));
        assertEquals(0, AiCommanderBase.pairedAgain);
        assertEquals(0, AiCommanderBase.keptTime);
        assertEquals(0, AiCommanderBase.otherDied);
        consumed(f, p);
    }

    @Test
    void timeKeepsEarlierDeadlineFromEitherJob() {
        for (boolean flank : new boolean[] {true, false}) {
            Job f = job(), p = job();
            f.dueWas = flank ? 500 : 700;
            p.dueWas = flank ? 700 : 500;
            assertEquals(500, AiCommanderBase.time(f, p, 1000, 100));
            consumed(f, p);
        }
        assertEquals(2, AiCommanderBase.pairedAgain);
        assertEquals(2, AiCommanderBase.keptTime);
        assertEquals(0, AiCommanderBase.graced);
    }

    @Test
    void timeGrantsGraceAfterDeath() {
        for (boolean flank : new boolean[] {true, false}) {
            Job f = job(), p = job();
            f.dueWas = 120;
            f.otherDead = flank;
            p.otherDead = !flank;
            assertEquals(300, AiCommanderBase.time(f, p, 1000, 100));
            consumed(f, p);
        }
        assertEquals(2, AiCommanderBase.pairedAgain);
        assertEquals(2, AiCommanderBase.keptTime);
        assertEquals(2, AiCommanderBase.otherDied);
        assertEquals(2, AiCommanderBase.graced);
        assertEquals(0, AiCommanderBase.upAtOnce);
    }

    @Test
    void timeGraceKeepsLaterDeadline() {
        Job f = job(), p = job();
        p.dueWas = 600;
        p.otherDead = true;
        assertEquals(600, AiCommanderBase.time(f, p, 1000, 100));
        assertEquals(0, AiCommanderBase.graced);
        assertEquals(1, AiCommanderBase.otherDied);
        consumed(f, p);
    }

    @Test
    void timeHasNoGraceWhenNewPartnerIsThere() {
        for (boolean flank : new boolean[] {true, false}) {
            Job f = job(), p = job();
            f.dueWas = 120;
            f.otherDead = flank;
            p.otherDead = !flank;
            p.there = flank;
            f.there = !flank;
            assertEquals(120, AiCommanderBase.time(f, p, 1000, 100));
            consumed(f, p);
        }
        assertEquals(0, AiCommanderBase.graced);
        assertEquals(2, AiCommanderBase.otherDied);
    }

    @Test
    void timeNeverExceedsFresh() {
        for (long was : new long[] {1000, 1100}) {
            Job f = job(), p = job();
            f.dueWas = was;
            assertEquals(1000, AiCommanderBase.time(f, p, 1000, 100));
            consumed(f, p);
        }
        Job f = job(), p = job();
        f.dueWas = 120;
        f.otherDead = true;
        assertEquals(200, AiCommanderBase.time(f, p, 200, 100));
        consumed(f, p);
        assertEquals(3, AiCommanderBase.pairedAgain);
        assertEquals(0, AiCommanderBase.keptTime);
        assertEquals(0, AiCommanderBase.graced);
        assertEquals(1, AiCommanderBase.otherDied);
    }

    @Test
    void timeCountsAlreadyExpiredPairs() {
        for (long was : new long[] {99, 100}) {
            Job f = job(), p = job();
            p.dueWas = was;
            assertEquals(was, AiCommanderBase.time(f, p, 1000, 100));
            consumed(f, p);
        }
        assertEquals(2, AiCommanderBase.upAtOnce);
        assertEquals(2, AiCommanderBase.keptTime);
        assertEquals(0, AiCommanderBase.otherDied);
    }

    private static StaffPlan.Squad squad(int group, long pinned) {
        return new StaffPlan.Squad(0, 0, 1, 1, 1, false, null, 0, group, false, -1, false, false, Long.MAX_VALUE, pinned, false, false);
    }

    @Test
    void helpsPinnedSquadAtSameGroup() {
        List<StaffPlan.Squad> own = List.of(squad(1, Long.MIN_VALUE), squad(7, 100));
        for (StaffPlan.Kind kind : StaffPlan.Kind.values()) {
            StaffPlan.Task[] plan = {new StaffPlan.Task(kind, 7, 0, 0, 1, 0, 0), null};
            assertEquals(kind == StaffPlan.Kind.FLANK || kind == StaffPlan.Kind.PIN, AiCommanderBase.helps(own, plan, 0), kind.name());
        }
        StaffPlan.Task[] plan = {new StaffPlan.Task(StaffPlan.Kind.FLANK, 7, 0, 0, 1, 0, 0), null};
        assertFalse(AiCommanderBase.helps(List.of(squad(7, 100), squad(7, 100)), plan, 0));
        assertFalse(AiCommanderBase.helps(List.of(squad(1, Long.MIN_VALUE), squad(8, 100)), plan, 0));
        assertFalse(AiCommanderBase.helps(List.of(squad(1, Long.MIN_VALUE), squad(7, Long.MIN_VALUE)), plan, 0));
        plan[0] = null;
        assertFalse(AiCommanderBase.helps(own, plan, 0));
        plan[0] = new StaffPlan.Task(StaffPlan.Kind.PIN, -1, 0, 0, 1, 0, 0);
        assertFalse(AiCommanderBase.helps(own, plan, 0));
    }

    @Test
    void containsAndShares() {
        assertTrue(AiCommanderBase.contains(new int[] {2, 7, 9}, 7));
        assertFalse(AiCommanderBase.contains(new int[] {2, 7, 9}, 3));
        assertFalse(AiCommanderBase.contains(new int[0], 7));
        assertTrue(AiCommanderBase.shares(new int[] {2, 7}, new int[] {9, 7}));
        assertFalse(AiCommanderBase.shares(new int[] {2, 7}, new int[] {9, 8}));
        assertFalse(AiCommanderBase.shares(new int[0], new int[] {7}));
        assertFalse(AiCommanderBase.shares(new int[] {7}, new int[0]));
    }

    @Test
    void givenSinceIsStrict() {
        AiCommanderBase.Order<AiBotBase, Job> o = new AiCommanderBase.Order<>();
        assertFalse(AiCommanderBase.givenSince(null, 100));
        o.givenAt = 100;
        assertTrue(AiCommanderBase.givenSince(o, 99));
        assertFalse(AiCommanderBase.givenSince(o, 100));
        assertFalse(AiCommanderBase.givenSince(o, 101));
    }

    @Test
    void jobDefaultsAndPlace() {
        List<AiBotBase> bots = List.of();
        for (StaffPlan.Kind kind : StaffPlan.Kind.values()) {
            Job j = new Job(kind, bots);
            assertSame(kind, j.kind);
            assertEquals(42, j.since);
            assertArrayEquals(new int[] {7}, j.ids);
            assertSame(bots, j.bots);
            assertSame(j.bots, j.members);
            assertEquals(kind != StaffPlan.Kind.ADVANCE && kind != StaffPlan.Kind.SCOUT, j.place(), kind.name());
            assertEquals(Long.MIN_VALUE, j.foundAt);
            assertEquals(Long.MAX_VALUE, j.due);
            assertEquals(Long.MIN_VALUE, j.ready);
            assertEquals(Long.MAX_VALUE, j.dueWas);
            assertEquals(Long.MIN_VALUE, j.givenAt);
            assertEquals(Long.MIN_VALUE, j.tailNot);
            assertEquals(Long.MIN_VALUE, j.pinFrom);
            assertEquals(Long.MIN_VALUE, j.pinnedAt);
            assertEquals(Long.MIN_VALUE, j.quietFrom);
            assertEquals(Long.MIN_VALUE, j.noFoeFrom);
            assertEquals(Long.MIN_VALUE, j.move.next);
            assertNull(j.move.mode);
        }
    }

    @Test
    void aliveReadsOnlyTheBodyEachTime() {
        boolean[] living = {true, false, true};
        List<AiBotBase> bots = java.util.stream.IntStream.range(0, living.length).mapToObj(i -> AiBotBaseTest.bot((Body) Proxy.newProxyInstance(
                Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> {
                    if (m.getName().equals("alive")) return living[i];
                    throw new AssertionError("unexpected body call: " + m.getName());
                }))).toList();
        Job j = new Job(StaffPlan.Kind.HOLD, bots);
        assertEquals(2, j.alive());
        living[0] = false;
        assertEquals(1, j.alive());
        assertEquals(0, job().alive());
    }

    @Test
    void orderDefaults() {
        AiCommanderBase.Order<AiBotBase, Job> o = new AiCommanderBase.Order<>();
        assertNull(o.job);
        assertEquals(Long.MIN_VALUE, o.nextLeg);
        assertEquals(Long.MIN_VALUE, o.lastInside);
        assertEquals(Long.MIN_VALUE, o.givenAt);
        assertEquals(Double.MAX_VALUE, o.failedAt);
        assertEquals(Long.MIN_VALUE, o.detourTick);
        assertEquals(Double.MAX_VALUE, o.legD);
        assertEquals(Double.MAX_VALUE, o.bestD);
        assertTrue(Double.isNaN(o.legX));
        assertTrue(Double.isNaN(o.legZ));
        assertEquals(Long.MIN_VALUE, o.lookUntil);
        assertEquals(Long.MIN_VALUE, o.coverCell);
        assertEquals(Long.MIN_VALUE, o.coverSince);
        assertEquals(Long.MIN_VALUE, o.halfSince);
        assertEquals(Long.MIN_VALUE, o.badCell);
        assertEquals(AiRooms.OUTSIDE, o.room);
        assertTrue(Double.isNaN(o.roomX));
        assertTrue(Double.isNaN(o.roomZ));
        assertEquals(Long.MIN_VALUE, o.moveAt);
        assertArrayEquals(new int[0], o.ids);
        assertTrue(o.badPlaces.isEmpty());
        AiCommanderBase.Order<AiBotBase, Job> other = new AiCommanderBase.Order<>();
        assertNotSame(o.ids, other.ids);
        assertNotSame(o.badPlaces, other.badPlaces);
    }

    @Test
    void movementStateDefaults() {
        StaffMoveBase.State state = new StaffMoveBase.State();
        assertEquals(Long.MIN_VALUE, state.next);
        assertNull(state.mode);
    }
}
