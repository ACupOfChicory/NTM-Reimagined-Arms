package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// The shapes' list: a quad with no colour at its corners adds nothing, the others four corners in the winding the GUI
// shows, the box round them; colours by alpha and mix.
class ShapeMeshTest {
    @Test
    void quadsKeepOneWindingAndTheBox() {
        ShapeMesh m = new ShapeMesh();
        m.quad(0, 0, 0x00FFFFFF, 1, 0, 0x00FFFFFF, 1, 1, 0, 0, 1, 0);
        assertEquals(0, m.n, "nothing seen");
        m.line(0, 0, 10, 0, 2, 0xFF000000);
        assertEquals(4, m.n);
        m.quad(0, 0, -1, 0, 1, -1, 1, 1, -1, 1, 0, -1); // the other way round: turned
        m.quad(0, 0, -1, 1, 0, -1, 1, 1, -1, 0, 1, -1);
        assertEquals(12, m.n);
        assertEquals(m.xy[4 * 2], m.xy[8 * 2 + 6]);
        assertEquals(m.xy[4 * 2 + 1], m.xy[8 * 2 + 7]);
        assertEquals(-1F, m.minY);
        assertEquals(10F, m.maxX);
    }

    @Test
    void colours() {
        assertEquals(0x80123456, ShapeMesh.argb(0xAB123456, 0.5F));
        assertEquals(0xFF123456, ShapeMesh.argb(0x123456, 2));
        assertEquals(0x40FFFFFF, ShapeMesh.alpha(0x80FFFFFF, 0.5F));
        assertEquals(0x808080, ShapeMesh.mix(0x000000, 0xFFFFFF, 0.5F));
    }

    @Test
    void aRingIsClosed() {
        ShapeMesh m = new ShapeMesh();
        m.feather = 0;
        m.ring(0, 0, 10, 20, -1, -1);
        assertEquals(0, m.n % 4);
        assertEquals(-20F, m.minX, 0.05F); // the nearest corner to straight left
        assertEquals(20F, m.maxY, 0.05F);
    }
}
