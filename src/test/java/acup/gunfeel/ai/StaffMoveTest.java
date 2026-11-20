package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import acup.gunfeel.ai.StaffMoveBase.Mode;

// How a squad goes along its staff's way (stage 7, C3): its mode, a bound's halves, places along the way.
class StaffMoveTest {
    @Test
    void modeByTheEnemy() {
        // far and unseen: a march; nearer, or seen: bounds; seen and near: creeping
        assertEquals(Mode.MARCH, StaffMove.mode(150, false, true, 150));
        assertEquals(Mode.BOUND, StaffMove.mode(90, false, true, 90));
        assertEquals(Mode.BOUND, StaffMove.mode(150, true, true, 150));
        assertEquals(Mode.CREEP, StaffMove.mode(50, true, true, 50));
        // unseen and near: still bounds, not creeping
        assertEquals(Mode.BOUND, StaffMove.mode(30, false, true, 30));
        // the last of an advance: the assault, seen or not; a place to hold: no assault
        assertEquals(Mode.ASSAULT, StaffMove.mode(20, true, true, 20));
        assertEquals(Mode.CREEP, StaffMove.mode(20, true, false, 20));
    }

    @Test
    void aMarchIsAJogAndFallingBackARun() {
        assertEquals(AiSoldier.MARCH, StaffMove.march(StaffPlan.Kind.ADVANCE));
        assertEquals(AiSoldier.MARCH, StaffMove.march(StaffPlan.Kind.FLANK));
        assertEquals(AiSoldier.RUN, StaffMove.march(StaffPlan.Kind.BACK));
    }

    @Test
    void halvesCoverWithTheBaseOfFireFirst() {
        // the base of fire and marksman cover first, half of the squad (the larger half when odd)
        assertArrayEquals(new int[] {1, 0, 0}, StaffMove.halves(List.of(AiRole.RIFLE, AiRole.BASE, AiRole.MARKSMAN)));
        assertArrayEquals(new int[] {0, 0, 1}, StaffMove.halves(List.of(AiRole.RIFLE, AiRole.RIFLE, AiRole.RIFLE)));
        assertArrayEquals(new int[] {0, 1, 0, 1}, StaffMove.halves(List.of(AiRole.RIFLE, AiRole.RIFLE, AiRole.BASE, AiRole.RIFLE)));
        assertArrayEquals(new int[] {0}, StaffMove.halves(List.of(AiRole.RIFLE)));
    }

    @Test
    void rushingOneOrTwo() {
        assertEquals(1, StaffMove.rushing(1));
        assertEquals(1, StaffMove.rushing(3));
        assertEquals(2, StaffMove.rushing(4));
        assertEquals(2, StaffMove.rushing(8));
    }

    @Test
    void placesAlongTheWay() {
        // from (0, 0) east 10, then north 20
        HqGrid.Route r = new HqGrid.Route(0, 0, 10, 20, new double[] {10, 0, 10, 20}, 30, Math.hypot(10, 20), 0, 0);
        assertEquals(5, StaffMove.along(r, 5, 3), 1e-9);
        assertEquals(20, StaffMove.along(r, 12, 10), 1e-9);
        assertEquals(30, StaffMove.along(r, 10, 40), 1e-9);
        assertArrayEquals(new double[] {4, 0}, StaffMove.at(r, 4), 1e-9);
        assertArrayEquals(new double[] {10, 5}, StaffMove.at(r, 15), 1e-9);
        assertArrayEquals(new double[] {10, 20}, StaffMove.at(r, 99), 1e-9);
        assertArrayEquals(new double[] {0, 0}, StaffMove.at(r, -3), 1e-9);
    }
}
