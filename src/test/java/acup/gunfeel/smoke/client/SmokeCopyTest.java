package acup.gunfeel.smoke.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The smoke's copies of the frame and its depth start at the bottom left corner: the OpenGL backend takes a copy's
 * width and height as its far corner, so one from an offset left the cloud's part of the screen as an earlier frame
 * had it (X1699 to X1711: a frozen picture where the cloud was).
 */
class SmokeCopyTest {
    @Test
    void theCopyReachesOverTheWholePart() {
        assertArrayEquals(new int[] {1920, 1080}, SmokeRender.copyCorner(null, 1920, 1080));
        // a part far from the corner, wider than its offset and narrower: covered from 0 to its far corner
        assertArrayEquals(new int[] {1500, 900}, SmokeRender.copyCorner(new int[] {1200, 700, 300, 200}, 1920, 1080));
        assertArrayEquals(new int[] {400, 300}, SmokeRender.copyCorner(new int[] {100, 50, 300, 250}, 1920, 1080));
        assertArrayEquals(new int[] {1920, 1080}, SmokeRender.copyCorner(new int[] {1900, 1000, 40, 100}, 1920, 1080));
    }

    @Test
    void everySmokeCopyStartsAtTheCorner() throws Exception {
        String src = Files.readString(Path.of("src/main/java/acup/gunfeel/smoke/client/SmokeRender.java"));
        Matcher m = Pattern.compile("copyTextureToTexture\\(([^;]*)\\);").matcher(src);
        int n = 0;
        while (m.find()) {
            n++;
            String[] a = m.group(1).split(",");
            for (int i = 2; i <= 6; i++) assertTrue(a[i].trim().equals("0"), "a copy from an offset: " + m.group(0));
        }
        assertTrue(n >= 2, "no copies found");
    }
}
