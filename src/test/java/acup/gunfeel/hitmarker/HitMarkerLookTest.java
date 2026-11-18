package acup.gunfeel.hitmarker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

// The marker and the indicator in the game are the ones the owner picked on the gallery page: src/test/hitmarker/page.txt
// is the page's own output (its FINAL, written by the page's code under Deno), and every pixel and alpha must match
class HitMarkerLookTest {
    @Test
    void sameAsThePage() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("src/test/hitmarker/page.txt"));
        int checked = 0;
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] w = line.split(" ", 4);
            String what = w[0] + " " + w[1] + " " + w[2];
            double t = Double.parseDouble(w[2]);
            List<int[]> px = new ArrayList<>();
            boolean shown = w[0].equals("marker")
                    ? HitMarkerLook.marker(w[1].equals("head") ? HitMarkerLook.HEAD : HitMarkerLook.BODY, t, (x, y, c) -> px.add(new int[] {x, y, c}))
                    : HitMarkerLook.indicator(Double.parseDouble(w[1]), t, (x, y, c) -> px.add(new int[] {x, y, c}));
            String expected = w[3];
            if (expected.equals("none")) {
                assertFalse(shown && !px.isEmpty(), what + ": gone on the page");
            } else {
                assertTrue(shown, what + ": shown on the page");
                px.sort((a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[0], b[0]));
                StringBuilder s = new StringBuilder();
                for (int[] p : px) {
                    if (!s.isEmpty()) s.append(' ');
                    s.append(p[0]).append(',').append(p[1]).append(',').append(String.format(Locale.ROOT, "%08x", p[2]));
                }
                assertEquals(expected, s.toString(), what);
            }
            checked++;
        }
        assertEquals(112, checked, "all the page's frames checked");
    }

    @Test
    void killHasNoMarkOfItsOwn() {
        // a kill shows the hit's own mark: the looks are the body's and the head's, nothing else
        assertEquals(7, (int) HitMarkerLook.BODY.end());
        assertEquals(9, (int) HitMarkerLook.HEAD.end());
        assertEquals(33, HitMarkerLook.indicatorTicks());
    }

    @Test
    void bearing() {
        // facing south (yaw 0): east is on the left, west on the right, north behind
        assertEquals(-90, HitMarkerLook.bearing(0, 5, 0), 1e-9);
        assertEquals(90, HitMarkerLook.bearing(0, -5, 0), 1e-9);
        assertEquals(180, Math.abs(HitMarkerLook.bearing(0, 0, -5)), 1e-9);
        assertEquals(0, HitMarkerLook.bearing(0, 0, 5), 1e-9);
        // facing east (yaw -90): south on the right
        assertEquals(90, HitMarkerLook.bearing(-90, 0, 5), 1e-9);
    }
}
