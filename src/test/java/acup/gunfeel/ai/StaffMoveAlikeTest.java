package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class StaffMoveAlikeTest {
    @Test
    void pureQueriesCompareArrayContentsAndBoundaryAnswers() {
        StaffMoveAlike saved = StaffMove.alike;
        StaffMoveAlike alike = new StaffMoveAlike();
        try {
            StaffMove.alike = alike;
            for (double foe : new double[] {0, 59.999, 60, 100, 100.001, Double.MAX_VALUE, Double.NaN})
                for (double left : new double[] {24.999, 25, 25.001})
                    for (boolean seen : new boolean[] {false, true})
                        for (boolean advance : new boolean[] {false, true})
                            StaffMove.mode(foe, seen, advance, left);
            StaffMove.halves(List.of(AiRole.RIFLE, AiRole.BASE, AiRole.MARKSMAN));
            StaffMove.halves(List.of());
            for (int n = 0; n < 10; n++) StaffMove.rushing(n);
            HqGrid.Route r = new HqGrid.Route(0, 0, 10, 20, new double[] {0, 0, 10, 0, 10, 20}, 30, 30, 0, 0);
            for (double s : new double[] {-1, 0, 10, 20, 30, 31}) {
                StaffMove.at(r, s);
                StaffMove.along(r, s, 3);
            }
            assertEquals(108, alike.asked);
            assertEquals(0, alike.differ, alike.info());
            StaffMove.alike = null;
            StaffMove.mode(1, true, true, 1);
            assertEquals(108, alike.asked);
        } finally {
            StaffMove.alike = saved;
        }
    }

    @Test
    void comparisonCountsBitDifferencesAndReturnsTheCoreAnswer() {
        StaffMoveAlike alike = new StaffMoveAlike();
        assertEquals(-0.0, alike.answer("speed", -0.0, 0.0));
        alike.answer("halves", new int[] {0, 1}, new int[] {0, 1});
        alike.answer("at", new double[] {1, 2}, new double[] {2, 1});
        assertEquals(3, alike.asked);
        assertEquals(2, alike.differ);
    }
}
