package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

// The areas of the glass list (feature/machine-glass 72831aa): "texture x0,y0,x1,y1 # why" empties only the clear
// pixels in that rectangle of a texture, read under 1.7.10's names.
class MachineGlassPortTest {
    @AfterEach
    void back() {
        MachineGlass.use(null);
    }

    @Test
    void lineWithAnAreaNamesItsRectangle() {
        assertArrayEquals(new int[] {3, 4, 10, 12}, MachineGlass.area("hbm:textures/a.png 3,4,10,12  # стенд стекла: пустота, область 7.2"));
        assertEquals("hbm:textures/a.png", MachineGlass.parse("hbm:textures/a.png 3,4,10,12 # why"));
        assertNull(MachineGlass.area("hbm:textures/a.png # a grate"));
        assertNull(MachineGlass.area("hbm:textures/a.png 3,4,10"));
        assertNull(MachineGlass.area("hbm:textures/a.png x,4,10,12"));
    }

    @Test
    void wholeTextureAndAreasAreEmptyWhereListedUnder1710Names() {
        MachineGlass.use(Arrays.asList("# head", "hbm:textures/block/models/whole.png  # all", "hbm:textures/block/models/part.png 0,0,3,3  # a",
                "hbm:textures/block/models/part.png 10,10,10,12"));
        String whole = "hbm:textures/models/whole.png", part = "hbm:textures/models/part.png";
        assertTrue(MachineGlass.empty(whole));
        assertFalse(MachineGlass.empty(part));
        assertTrue(MachineGlass.hasAreas(part));
        assertFalse(MachineGlass.hasAreas(whole));
        assertTrue(MachineGlass.emptyAt(whole, 50, 50));
        assertTrue(MachineGlass.emptyAt(part, 3, 3));
        assertTrue(MachineGlass.emptyAt(part, 10, 12));
        assertFalse(MachineGlass.emptyAt(part, 4, 3));
        assertFalse(MachineGlass.emptyAt(part, 10, 13));
        assertFalse(MachineGlass.emptyAt("hbm:textures/models/other.png", 0, 0));
        // at texture coordinates on a 16 x 16 texture: pixel (3, 3) is u, v in [3/16, 4/16)
        assertTrue(MachineGlass.emptyAt(part, 3.5 / 16, 3.5 / 16, 16, 16));
        assertFalse(MachineGlass.emptyAt(part, 4.5 / 16, 3.5 / 16, 16, 16));
    }

    // the jar's list on NTM 1.7.10's own textures: hephaestus keeps its window 34.1 as glass and the slots beside it
    // empty; the fusion torus' areas are empty, the rest of its clear pixels glass
    @Test
    void jarListLeavesGlassAndEmptyOnNtmTextures() throws Exception {
        for (String name : new String[] {"machines/hephaestus", "fusion/torus", "machines/icf", "machines/orbus"}) {
            String texture = "hbm:textures/models/" + name + ".png";
            assertTrue(MachineGlass.hasAreas(texture), texture);
            assertFalse(MachineGlass.empty(texture), texture);
            BufferedImage img = image("assets/hbm/textures/models/" + name + ".png");
            int glass = 0, empty = 0;
            for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) > 25) continue;
                if (MachineGlass.emptyAt(texture, x, y)) empty++;
                else glass++;
            }
            assertTrue(empty > 0, texture + ": no clear pixel in its areas");
            assertTrue(glass > 0, texture + ": no clear pixel left as glass");
        }
    }

    private static BufferedImage image(String entry) throws Exception {
        Path jar = null;
        try (java.util.stream.Stream<Path> l = Files.list(Paths.get("libs"))) {
            for (Path p : (Iterable<Path>) l::iterator) if (p.getFileName().toString().startsWith("NTM-CE-") && p.toString().endsWith(".jar")) jar = p;
        }
        assertNotNull(jar, "no NTM release jar in libs/");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            java.util.zip.ZipEntry e = zip.getEntry(entry);
            assertNotNull(e, entry);
            return javax.imageio.ImageIO.read(zip.getInputStream(e));
        }
    }
}
