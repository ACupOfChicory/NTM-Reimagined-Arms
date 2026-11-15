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
        return StaffPlan.plan(squads, groups, null, 0, 0, 160, AiCommander.PER, NOW, 0);
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
    void strengthWeighsRoles() {
        assertEquals(1, StaffPlan.weight(AiRole.RIFLE.ordinal()));
        assertEquals(1.5, StaffPlan.weight(AiRole.BASE.ordinal()));
        assertEquals(3, StaffPlan.weight(AiRole.values().length));
    }

    @Test
    void aGroupGivenUpLatelyIsNotGivenAgain() {
        Squad gaveUp = new Squad(0, 0, 3, 3, 3, false, null, 0, -1, false, 0);
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
}
