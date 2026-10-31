package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

// The bullet mark textures reach the port's jar (shared.txt [resources]) where Tracers looks for them, and each is
// a grid of GRID x GRID cells that 1.7.10's texture manager can load. 26.2's DecalsTest reads them by a path from the
// repository root and checks the bullet rules too, so it is not shared; its style checks run here on the shared Decals.
class DecalsPortTest {
    @Test
    void everyMarkTextureIsInTheJar() throws IOException {
        for (int k = 0; k < Decals.COUNT; k++) {
            String path = "/assets/ntmgunfeel/textures/effect/" + Decals.file(k) + ".png";
            try (InputStream in = DecalsPortTest.class.getResourceAsStream(path)) {
                assertNotNull(in, "missing " + path);
                BufferedImage img = ImageIO.read(in);
                assertNotNull(img, path);
                assertEquals(img.getWidth(), img.getHeight(), path);
                assertEquals(0, img.getWidth() % Decals.GRID, path);
                assertEquals(0, img.getWidth() & (img.getWidth() - 1), path + ": not a power of two");
            }
        }
    }

    @Test
    void stylesFollowTheMaterial() {
        assertEquals(new Decals.Set("spall", "gouge", "crater"), Decals.Set.of(Materials.Material.STONE));
        assertEquals("shatter", Decals.Set.of(Materials.Material.GLASS).hole());
        assertEquals(Decals.Set.DEFAULT, Decals.Set.of(Materials.Material.NONE));
        for (Materials.Material m : Materials.Material.values()) {
            Decals.Set s = Decals.Set.of(m);
            assertTrue(Decals.HOLES.contains(s.hole()) && Decals.SCRATCHES.contains(s.scratch()) && Decals.SCORCHES.contains(s.scorch()), m.name());
        }
        int scorch = Decals.scorch("star");
        assertTrue(Decals.isScorch(scorch));
        assertTrue(Decals.isScratch(Decals.scratch("nonsense")));
        assertEquals(Decals.hole(Decals.HOLE), Decals.hole("nonsense"));
    }
}
