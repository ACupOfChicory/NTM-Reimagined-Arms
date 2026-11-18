package acup.gunfeel.crosshair;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

// The crosshair in the game is the one the owner picked on the gallery page (А): src/test/crosshair/page.txt is the
// page's own output (its JAR block run under Deno), each style's lines at every gap and a run of frames' gaps
class DynamicCrosshairTest {
    @Test
    void sameAsThePage() throws Exception {
        List<String> lines = Files.readAllLines(Path.of("src/test/crosshair/page.txt"));
        int rects = 0, frames = 0;
        for (String line : lines) {
            if (line.startsWith("rects ")) {
                String[] w = line.split(" ", 4);
                CrosshairStyle s = CrosshairStyle.valueOf(w[1]);
                String now = s.rects(0, 0, Integer.parseInt(w[2])).stream()
                        .map(r -> r[0] + "," + r[1] + "," + r[2] + "," + r[3]).collect(Collectors.joining(" "));
                assertEquals(w[3], now, s + " gap " + w[2]);
                rects++;
            } else if (line.startsWith("run ")) {
                DynamicCrosshair.Gap gap = new DynamicCrosshair.Gap();
                for (String f : line.substring(4).split(" ")) {
                    String[] v = f.split(",");
                    float kick = Float.parseFloat(v[2]);
                    if (kick != 0F) gap.shot(kick);
                    assertEquals(Integer.parseInt(v[3]), gap.step(9, Double.parseDouble(v[0]), Double.parseDouble(v[1])), "frame " + frames);
                    frames++;
                }
            }
        }
        assertEquals(150, rects, "every style at every gap");
        assertEquals(240, frames, "every frame of the run");
    }

    @Test
    void sameAtAnyFrameRate() {
        // a kick's jump seen at 60, 20 and 10 frames a second: the gap at the same moments is the same (to the jump's
        // last 0.05, cut off at whichever frame it falls under), its peak the jump over e (20 → 7.4) on top
        for (double t : new double[] {0.1, 0.2, 0.5}) {
            double g60 = after(60, t), g20 = after(20, t), g10 = after(10, t);
            assertEquals(g60, g20, 0.02, "20 fps at " + t + " s");
            assertEquals(g60, g10, 0.02, "10 fps at " + t + " s");
        }
        assertEquals(9 + 4 + 20 / Math.E, after(60, 0.05), 1e-6, "the peak at 60 fps");
        assertEquals(9 + 4 + 20 / Math.E, after(20, 0.05), 1e-6, "the peak at 20 fps");
    }

    // the gap t seconds after a kick of 4° (a jump of 20) at base 9 and inaccuracy 5, at `fps` frames a second
    private static double after(int fps, double t) {
        DynamicCrosshair.Gap g = new DynamicCrosshair.Gap();
        g.step(9, 5, 0);
        g.shot(4F);
        for (int f = 0, n = (int) Math.round(t * fps); f < n; f++) g.step(9, 5, 1.0 / fps);
        return g.gap;
    }

    @Test
    void bases() {
        // the gap a style keeps standing still with no spread: TaCZ's own bases
        assertEquals(4, CrosshairStyle.CROSS.base);
        assertEquals(4, CrosshairStyle.CORNER.base);
        assertEquals(6, CrosshairStyle.BRACKETS.base);
        assertEquals(9, CrosshairStyle.SQUARE.base);
        assertEquals(3, CrosshairStyle.RULER.base);
    }

    @Test
    void spreadAsInaccuracy() {
        // the same scatter: a TaCZ inaccuracy of 1 is a spread of 0.0172275/√6 ≈ 0.00703
        assertEquals(1.0, DynamicCrosshair.inaccuracy((float) DynamicCrosshair.INACCURACY_SPREAD), 1e-6);
        // the G3 from the hip (1.43°) is about 3.5, TaCZ's rifles standing 5
        assertEquals(3.55, DynamicCrosshair.inaccuracy((float) Math.tan(Math.toRadians(1.43))), 0.01);
    }
}
