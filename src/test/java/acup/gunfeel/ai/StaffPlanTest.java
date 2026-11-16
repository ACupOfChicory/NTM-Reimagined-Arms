package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import acup.gunfeel.ai.StaffPlan.Group;
import acup.gunfeel.ai.StaffPlan.Kind;
import acup.gunfeel.ai.StaffPlan.Squad;
import acup.gunfeel.ai.StaffPlan.Task;

// The staff's tasks (stage 7, C2): who advances, pins, goes round, holds, scouts, falls back or waits.
class StaffPlanTest {
    static final long NOW = 10_000;

    static Squad squad(double x, double z, double strength) {
        return new Squad(x, z, strength, 3, 3, false, null, 0, -1, false);
    }

    static Group group(double x, double z, double strength) {
        return new Group(x, z, 16, strength, 0, AiCommander.PER);
    }

    static Task[] plan(List<Squad> squads, List<Group> groups) {
        return StaffPlan.plan(squads, groups, OPEN, 0, 0, 160, AiCommander.PER, NOW, 0);
    }

    /** the staff's map: open ground all known about (0, 0), none of it seen (no map: no squad sent round) */
    static final HqGrid OPEN = open();

    static HqGrid open() {
        HqGrid g = new HqGrid(-HqGrid.N * HqGrid.CELL / 2, -HqGrid.N * HqGrid.CELL / 2);
        for (int i = 0; i < HqGrid.N; i++)
            for (int k = 0; k < HqGrid.N; k++) g.set(i, k, 64, 0, 0, 0);
        return g;
    }

    @Test
    void noMapNoGoingRound() {
        Task[] t = StaffPlan.plan(List.of(squad(0, 0, 3), squad(30, 0, 3)), List.of(group(0, 100, 3)), null, 0, 0, 160, AiCommander.PER, NOW, 0);
        assertEquals(Kind.PIN, t[0].kind());
        assertEquals(Kind.HOLD, t[1].kind());
    }

    @Test
    void aSquadHalfAsStrongAgainAdvances() {
        Task[] t = plan(List.of(squad(0, 0, 3)), List.of(group(0, 100, 2)));
        assertEquals(Kind.ADVANCE, t[0].kind());
        assertEquals(100, t[0].z(), 1e-9);
    }

    @Test
    void twoAboutAsStrongPinAndGoRound() {
        Task[] t = plan(List.of(squad(0, 0, 3), squad(30, 0, 3)), List.of(group(0, 100, 3)));
        assertEquals(Kind.PIN, t[0].kind());
        assertEquals(Kind.FLANK, t[1].kind());
        // the pin PIN off the group toward the squad; the flank FLANK_D off it, well off the line pin - group
        assertEquals(StaffPlan.PIN, Math.hypot(t[0].x() - 0, t[0].z() - 100), 1e-6);
        assertEquals(StaffPlan.FLANK_D, Math.hypot(t[1].x() - 0, t[1].z() - 100), 1e-6);
        double a = Math.atan2(t[0].z() - 100, t[0].x()), b = Math.atan2(t[1].z() - 100, t[1].x());
        double off = Math.abs(Math.IEEEremainder(a - b, 2 * Math.PI));
        assertTrue(off >= Math.toRadians(60), "off the line by " + Math.toDegrees(off));
        // both face the group
        assertEquals(100, t[0].faceZ(), 1e-9);
    }

    @Test
    void aWeakSquadAloneHolds() {
        Task[] t = plan(List.of(squad(0, 0, 1)), List.of(group(0, 100, 3)));
        assertEquals(Kind.HOLD, t[0].kind());
    }

    @Test
    void aGroupNotSeenLatelyIsScouted() {
        Group g = new Group(0, 100, 30, 1, StaffPlan.STALE + 1, AiCommander.PER);
        Task[] t = plan(List.of(squad(0, 0, 3)), List.of(g));
        assertEquals(Kind.SCOUT, t[0].kind());
    }

    @Test
    void aSquadThatLostMoreThanHalfFallsBackOnTheNearestOwnBehindIt() {
        Squad hurt = new Squad(0, 50, 1, 1, 3, false, Kind.ADVANCE, NOW - 100, 0, false);
        Squad behind = squad(20, 0, 3), ahead = squad(0, 80, 3);
        Task[] t = plan(List.of(hurt, behind, ahead), List.of(group(0, 100, 1)));
        assertEquals(Kind.BACK, t[0].kind());
        assertEquals(20, t[0].x(), 1e-9);
        assertEquals(0, t[0].z(), 1e-9);
        // out of rounds the same
        Squad out = new Squad(0, 50, 3, 3, 3, true, null, 0, -1, false);
        assertEquals(Kind.BACK, plan(List.of(out, behind), List.of(group(0, 100, 1)))[0].kind());
        // none of its own: straight away from the group
        Task alone = plan(List.of(hurt), List.of(group(0, 100, 1)))[0];
        assertEquals(Kind.BACK, alone.kind());
        assertEquals(50 - StaffPlan.BACK, alone.z(), 1e-9);
    }

    @Test
    void withNoGroupKnownTheSquadsScoutTheRingInTurn() {
        Task[] t = StaffPlan.plan(List.of(squad(0, 0, 3), squad(5, 0, 3)), List.of(), null, 0, 0, 160, AiCommander.PER, NOW, 2);
        for (Task x : t) {
            assertEquals(Kind.SCOUT, x.kind());
            assertEquals(StaffPlan.RING, Math.hypot(x.x(), x.z()), 1e-6);
        }
        assertTrue(Math.hypot(t[0].x() - t[1].x(), t[0].z() - t[1].z()) > 1, "two sectors");
    }

    @Test
    void noMoreThanPerToAGroupTheRestWait() {
        Task[] t = plan(List.of(squad(0, 0, 3), squad(10, 0, 3), squad(20, 0, 3)), List.of(group(0, 100, 1)));
        int sent = 0;
        for (Task x : t) if (x.group() == 0) sent++;
        assertEquals(AiCommander.PER, sent);
        assertEquals(Kind.RESERVE, t[2].kind());
        // where the side loses soldiers (room for one more): the reserve goes too
        Group losing = new Group(0, 100, 16, 1, 0, AiCommander.PER + 1);
        Task[] h = plan(List.of(squad(0, 0, 3), squad(10, 0, 3), squad(20, 0, 3)), List.of(losing));
        assertEquals(Kind.ADVANCE, h[2].kind());
    }

    @Test
    void aSquadOutOfReachWaits() {
        Task[] t = plan(List.of(squad(0, 0, 3)), List.of(group(0, 400, 1)));
        assertEquals(Kind.RESERVE, t[0].kind());
    }

    @Test
    void aSquadAsleepIsSentAsFarAsTheStaffKnows() {
        // out of an awake squad's reach, within the staff's knowledge
        Squad asleep = new Squad(0, 0, 3, 3, 3, false, null, 0, -1, false, -1, true);
        assertEquals(Kind.ADVANCE, plan(List.of(asleep), List.of(group(0, 220, 1)))[0].kind());
        assertEquals(Kind.RESERVE, plan(List.of(asleep), List.of(group(0, StaffPlan.FAR + 10, 1)))[0].kind());
        // with no group known it waits where it is, not scouting
        assertEquals(Kind.RESERVE, StaffPlan.plan(List.of(asleep), List.of(), null, 0, 0, 160, AiCommander.PER, NOW, 0)[0].kind());
    }

    @Test
    void aTaskIsKeptAWhileThenLookedAtAgain() {
        // holding, weak, the group now much weaker: kept within KEEP, advances after
        Squad holding = new Squad(0, 0, 1, 3, 3, false, Kind.HOLD, NOW - 100, 0, false);
        assertEquals(Kind.HOLD, plan(List.of(holding), List.of(group(0, 100, 0.5)))[0].kind());
        Squad old = new Squad(0, 0, 1, 3, 3, false, Kind.HOLD, NOW - StaffPlan.KEEP, 0, false);
        assertEquals(Kind.ADVANCE, plan(List.of(old), List.of(group(0, 100, 0.5)))[0].kind());
        // one that went round and got there advances
        Squad round = new Squad(40, 100, 3, 3, 3, false, Kind.FLANK, NOW - 100, 0, true);
        Squad pinning = new Squad(0, 40, 3, 3, 3, false, Kind.PIN, NOW - 100, 0, false);
        Task[] t = plan(List.of(pinning, round), List.of(group(0, 100, 3)));
        assertEquals(Kind.PIN, t[0].kind());
        assertEquals(Kind.ADVANCE, t[1].kind());
    }

    @Test
    void onTheMapThePinTakesTheHigherPlaceThatSeesTheGroup() {
        HqGrid g = HqGridTest.field();
        // a knoll four high a cell off the pinning place
        double gx = HqGridTest.mid(32), gz = HqGridTest.mid(20);
        int ki = 33, kk = g.cellZ(gz + StaffPlan.PIN);
        g.set(ki, kk, 68, 0, 0, 0);
        g.sight(gx, gz);
        Group grp = new Group(gx, gz, 16, 3, 0, AiCommander.PER);
        Task p = StaffPlan.pin(g, grp, 0, gx, gz + 200);
        assertEquals(g.midX(ki), p.x(), 1e-9);
        assertEquals(g.midZ(kk), p.z(), 1e-9);
    }

    @Test
    void theFlankTakesTheSideTheEnemySeesLess() {
        HqGrid g = HqGridTest.field();
        double gx = HqGridTest.mid(32), gz = HqGridTest.mid(32);
        // all seen but a wood to the east of the group
        for (int i = 0; i < HqGrid.N; i++)
            for (int k = 0; k < HqGrid.N; k++) g.seen[HqGrid.index(i, k)] = i > 34 ? 0 : 1;
        Group grp = new Group(gx, gz, 16, 3, 0, AiCommander.PER);
        // pinned from the south
        Task f = StaffPlan.flank(g, grp, 0, gx, gz + 60, gx - 30, gz + 60);
        assertTrue(f.x() > gx, "east, where it is not seen: " + (f.x() - gx));
    }

    @Test
    void aNewPairPinsWithTheOneLessInSightOnItsWay() {
        HqGrid g = HqGridTest.field();
        // a strip the enemy sees straight south of the group (an open slope), the rest hidden
        for (int i = 0; i < HqGrid.N; i++)
            for (int k = 0; k < HqGrid.N; k++) g.seen[HqGrid.index(i, k)] = i == 26 && k >= 27 && k <= 37 ? 1 : 0;
        double gx = HqGridTest.mid(26), gz = HqGridTest.mid(44);
        Group grp = new Group(gx, gz, 16, 3, 0, AiCommander.PER);
        // the south squad the nearer: pinning, it would walk up the strip
        Squad south = squad(HqGridTest.mid(26), HqGridTest.mid(26), 3), east = squad(HqGridTest.mid(34), HqGridTest.mid(27), 3);
        Task[] t = StaffPlan.plan(List.of(south, east), List.of(grp), g, gx, HqGridTest.mid(20), 200, AiCommander.PER, NOW, 0);
        assertEquals(Kind.FLANK, t[0].kind());
        assertEquals(Kind.PIN, t[1].kind());
        Task own = StaffPlan.pin(g, grp, 0, south.x(), south.z());
        assertTrue(StaffPlan.exposed(g, east, t[1]) + StaffPlan.exposed(g, south, t[0]) < StaffPlan.exposed(g, south, own),
                "less in sight than the south one pinning");
        // with no map: the nearer pins
        Task[] u = StaffPlan.plan(List.of(south, east), List.of(grp), null, gx, HqGridTest.mid(20), 200, AiCommander.PER, NOW, 0);
        assertEquals(Kind.PIN, u[0].kind());
    }

    @Test
    void strengthWeighsRoles() {
        assertEquals(1, StaffPlan.weight(AiRole.RIFLE.ordinal()));
        assertEquals(1.5, StaffPlan.weight(AiRole.BASE.ordinal()));
        assertEquals(3, StaffPlan.weight(AiRole.values().length));
    }

    @Test
    void aGroupGivenUpLatelyIsNotGivenAgain() {
        Squad gaveUp = new Squad(0, 0, 3, 3, 3, false, null, 0, -1, false, 0, false);
        Task[] t = plan(List.of(gaveUp), List.of(group(0, 100, 1)));
        assertEquals(Kind.RESERVE, t[0].kind());
        // another group: that one
        Task[] u = plan(List.of(gaveUp), List.of(group(0, 100, 1), group(100, 0, 1)));
        assertEquals(1, u[0].group());
    }

    @Test
    void aNewScoutTakesTheNextSectorNotOneKept() {
        // the first keeps its sector (the commander has its place); the new one takes the side's next one
        Squad keeping = new Squad(0, 0, 3, 3, 3, false, Kind.SCOUT, NOW - 100, -1, false);
        Task[] t = StaffPlan.plan(List.of(keeping, squad(5, 0, 3)), List.of(), null, 0, 0, 160, AiCommander.PER, NOW, 2);
        double a = Math.atan2(t[1].z(), t[1].x());
        assertEquals(Math.floorMod(2, StaffPlan.SECTORS), Math.floorMod(Math.round(a / (2 * Math.PI / StaffPlan.SECTORS)), StaffPlan.SECTORS));
    }

    @Test
    void aPairLookedAtAgainKeepsItsParts() {
        // past KEEP the plan is made afresh: the one going round, now the nearer, still goes round
        Squad round = new Squad(10, 60, 3, 3, 3, false, Kind.FLANK, NOW - StaffPlan.KEEP, 0, false);
        Squad pinning = new Squad(0, 30, 3, 3, 3, false, Kind.PIN, NOW - StaffPlan.KEEP, 0, false);
        Task[] t = plan(List.of(round, pinning), List.of(group(0, 100, 3)));
        assertEquals(Kind.FLANK, t[0].kind());
        assertEquals(Kind.PIN, t[1].kind());
    }

    @Test
    void aPairKeepsItsPartsTillItsTimeThenGoesIn() {
        // a pair (C4), pinning and going round, past KEEP but within its time: kept
        long due = NOW + 200;
        Squad pinning = new Squad(0, 40, 2, 3, 3, false, Kind.PIN, NOW - StaffPlan.KEEP - 100, 0, false, -1, false, false, due);
        Squad round = new Squad(60, 80, 2, 3, 3, false, Kind.FLANK, NOW - StaffPlan.KEEP - 100, 0, false, -1, false, false, due);
        Task[] t = plan(List.of(pinning, round), List.of(group(0, 100, 3)));
        assertEquals(Kind.PIN, t[0].kind());
        assertEquals(Kind.FLANK, t[1].kind());
        // the one going round in a fight: it advances, the other keeps pinning
        Squad fighting = new Squad(60, 80, 2, 3, 3, false, Kind.FLANK, NOW - 100, 0, false, -1, false, true, due);
        Squad pressing = new Squad(0, 40, 2, 3, 3, false, Kind.PIN, NOW - 100, 0, false, -1, false, true, due);
        t = plan(List.of(pressing, fighting), List.of(group(0, 100, 3)));
        assertEquals(Kind.PIN, t[0].kind());
        assertEquals(Kind.ADVANCE, t[1].kind());
        // the time up: both advance, not paired again
        Squad latePin = new Squad(0, 40, 2, 3, 3, false, Kind.PIN, NOW - 100, 0, false, -1, false, false, NOW);
        Squad lateRound = new Squad(60, 80, 2, 3, 3, false, Kind.FLANK, NOW - 100, 0, false, -1, false, false, NOW);
        t = plan(List.of(latePin, lateRound), List.of(group(0, 100, 3)));
        assertEquals(Kind.ADVANCE, t[0].kind());
        assertEquals(Kind.ADVANCE, t[1].kind());
    }

    @Test
    void goesRoundOnTheSideOfTheFord() {
        HqGrid g = HqGridTest.field();
        // a deep river between the squad and the group, its ford east: the place to go round to east of the group
        for (int i = 0; i < HqGrid.N; i++) g.set(i, 16, 63, 0, 1, i == 24 ? 0 : 1);
        double gx = HqGridTest.mid(16), gz = HqGridTest.mid(22);
        Group grp = new Group(gx, gz, 16, 3, 0, AiCommander.PER);
        Task f = StaffPlan.flank(g, grp, 0, gx, HqGridTest.mid(12), gx - 8, HqGridTest.mid(10));
        assertTrue(f != null && f.x() > gx, "east of the group, by the ford: " + f);
        // the enemy sees the east side, not the west: still east, the west too far to go round to in time
        g.sight(gx + 40, gz);
        f = StaffPlan.flank(g, grp, 0, gx, HqGridTest.mid(12), gx - 8, HqGridTest.mid(10));
        assertTrue(f != null && f.x() > gx, "east of the group, by the ford: " + f);
    }

    @Test
    void noGoingRoundWhereTheFordIsTooFarAwayButPinningBoth() {
        HqGrid g = HqGridTest.field();
        // the ford far east: neither side got round to in a pair's time
        for (int i = 0; i < HqGrid.N; i++) g.set(i, 16, 63, 0, 1, i == 60 ? 0 : 1);
        double gx = HqGridTest.mid(16), gz = HqGridTest.mid(22);
        Group grp = new Group(gx, gz, 16, 3, 0, AiCommander.PER);
        assertEquals(null, StaffPlan.flank(g, grp, 0, gx, HqGridTest.mid(12), gx - 8, HqGridTest.mid(4)));
        Task[] t = StaffPlan.plan(List.of(squad(gx - 8, HqGridTest.mid(4), 2), squad(gx + 8, HqGridTest.mid(4), 2)), List.of(grp), g, gx, HqGridTest.mid(4), 400, AiCommander.PER, NOW, 0);
        assertEquals(Kind.PIN, t[0].kind());
        assertEquals(Kind.PIN, t[1].kind());
    }
}
