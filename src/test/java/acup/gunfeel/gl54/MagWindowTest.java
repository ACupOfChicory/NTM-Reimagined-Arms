package acup.gunfeel.gl54;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

// The magazine window's rules (MagWindow): the grenades pressed to the right, last in first out, one grenade per shift-click.
class MagWindowTest {
    @Test
    void fewerGrenadesLeaveEmptyCellsOnTheLeft() {
        List<String> cells = MagWindow.cells(List.of("he", "ap", "inc"));
        assertEquals(List.of("", "", "", "", "", "he", "ap", "inc"), cells);
        assertEquals(5, MagWindow.takeCell(List.of("he", "ap", "inc")), "remaining[0] is in cell 8 - n");
        assertEquals(List.of("", "", "", "", "", "", "", ""), MagWindow.cells(List.of()));
        assertEquals(-1, MagWindow.takeCell(List.of()));
    }

    @Test
    void aGrenadePutInSlidesRightAndBecomesTheFirstToFire() {
        List<String> m = List.of("ap", "inc");
        List<String> after = MagWindow.insert(m, "he");
        assertEquals(List.of("he", "ap", "inc"), after);
        assertEquals("he", MagWindow.first(after));
        assertEquals(5, MagWindow.insertCell(m), "the empty cell next to the stack");
        assertEquals(0, MagWindow.insertCell(List.of("a", "b", "c", "d", "e", "f", "g")));
    }

    @Test
    void lastInFirstOut() {
        List<String> m = List.of();
        m = MagWindow.insert(m, "he");
        m = MagWindow.insert(m, "ap");
        m = MagWindow.insert(m, "inc");
        assertEquals(List.of("inc", "ap", "he"), m);
        assertEquals("inc", MagWindow.first(m));
        m = MagWindow.take(m);
        assertEquals("ap", MagWindow.first(m));
        m = MagWindow.take(m);
        m = MagWindow.take(m);
        assertEquals(List.of(), m);
        assertEquals(List.of(), MagWindow.take(m));
    }

    @Test
    void clickingTheCellsLeftToRightEmptiesItInFireOrder() {
        List<String> m = List.of("a", "b", "c", "d");
        StringBuilder out = new StringBuilder();
        while (!m.isEmpty()) {
            out.append(MagWindow.first(m));
            m = MagWindow.take(m);
        }
        assertEquals("abcd", out.toString());
    }

    @Test
    void fullOrNotAGrenadeChangesNothing() {
        List<String> full = List.of("a", "b", "c", "d", "e", "f", "g", "h");
        assertSame(full, MagWindow.insert(full, "he"));
        assertEquals(-1, MagWindow.insertCell(full));
        List<String> m = List.of("he");
        assertSame(m, MagWindow.insert(m, ""));
        assertSame(m, MagWindow.insert(m, null));
    }

    @Test
    void oneClickIsOneGrenadeEvenFromAStackOf64() {
        // a stack of 64 clicked 3 times gives 3 grenades, 61 are left
        int stack = 64;
        List<String> m = List.of();
        for (int click = 0; click < 3; click++) {
            if (MagWindow.canInsert(m, "he")) {
                m = MagWindow.insert(m, "he");
                stack--;
            }
        }
        assertEquals(3, m.size());
        assertEquals(61, stack);
    }

    @Test
    void anyLayoutOfCellsClosesToTheSameOrder() {
        assertEquals(List.of("he", "ap"), MagWindow.compact(List.of("he", "", "", "ap", "", "", "", "")));
        assertEquals(List.of("he", "ap"), MagWindow.compact(MagWindow.cells(List.of("he", "ap"))));
    }
}
