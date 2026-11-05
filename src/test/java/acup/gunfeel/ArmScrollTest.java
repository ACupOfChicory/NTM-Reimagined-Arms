package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

// the lines of a settings page that do not fit on a short screen: a line «Строки … из …» with ▲ ▼, grey at the ends
// with why, each line reached; on a new page from the top
class ArmScrollTest {
    private static boolean has(ArmEditor e, String id) {
        return e.actions().stream().anyMatch(a -> a.id().equals(id));
    }

    private static ArmEditor.Action action(ArmEditor e, String id) {
        return e.actions().stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void linesThatDoNotFitAreScrolledTo() {
        var opened = Grips.snapshot();
        String gun = "gunfeel:key_probe";
        try {
            var e = new ArmEditor(gun, ArmEditorTest.clips(List.of("RELOAD")));
            e.page = ArmEditor.Page.ARM;
            e.hand = 1;
            var all = e.rows();
            int n = all.size();
            assertTrue(n > 5, "the arm page has more lines than five: " + n);
            assertEquals(all, e.shownRows(), "all fit: all shown");
            assertFalse(has(e, "rows.down"), "all fit: nothing to scroll");
            e.room = 5;
            var shown = e.shownRows();
            assertEquals(5, shown.size());
            assertEquals(all.subList(0, 4), shown.subList(0, 4));
            var pager = shown.get(4);
            assertEquals("Строки 1–4 из " + n, pager.text());
            assertEquals(List.of("rows.up", "rows.down"), pager.ids());
            assertEquals("выше строк нет", action(e, "rows.up").why());
            assertNull(action(e, "rows.down").why());
            for (var r : all.subList(4, n)) for (String id : r.ids()) assertTrue(e.hiddenIds().contains(id), id);
            for (var r : shown) for (String id : r.ids()) assertFalse(e.hiddenIds().contains(id), id);
            var before = e.state();
            assertTrue(e.press("rows.down"));
            assertNotEquals(before, e.state(), "▼ changes what is shown");
            assertEquals(all.get(1), e.shownRows().get(0));
            int presses = 1;
            while (action(e, "rows.down").active()) {
                assertTrue(e.press("rows.down"));
                presses++;
            }
            assertEquals(n - 4, presses);
            assertEquals(all.get(n - 1), e.shownRows().get(3));
            assertEquals("ниже строк нет", action(e, "rows.down").why());
            assertEquals("Строки " + (n - 3) + "–" + n + " из " + n, e.shownRows().get(4).text());
            assertTrue(e.press("rows.up"));
            assertEquals(all.get(n - 5), e.shownRows().get(0));
            // another page: from its top
            assertTrue(e.press("page.both"));
            assertEquals(0, e.first);
            assertEquals(e.rows().get(0), e.shownRows().get(0));
            assertEquals(opened, Grips.snapshot(), "scrolling changes no setting");
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }
}
