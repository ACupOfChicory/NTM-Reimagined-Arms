package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** A shot on a helmet is a shot in the head (ModelHits.partAt, ModelHits.helmet). */
class HelmetHitTest {
    private static final double PX = 1.0 / 16;

    private static ModelBox box(String name, double x, double y, double z, double w, double h, double d) {
        return new ModelBox(name, x * PX, y * PX, z * PX, w * PX, 0, 0, 0, h * PX, 0, 0, 0, d * PX);
    }

    // a head of 8 px, a suit's helmet 11.5 px about it, a chest under them reaching into the helmet's box
    private static final ModelBox HEAD = box("root/head", -4, 24, -4, 8, 8, 8);
    private static final ModelBox HELMET = box("mesh/Helmet", -5.75, 23, -5.75, 11.5, 9.5, 11.5);
    private static final ModelBox CHEST = box("mesh/Chest", -4.8, 11, -3.9, 9.6, 13.5, 7.8);
    private static final List<ModelBox> BOXES = List.of(HEAD, CHEST, HELMET);

    @Test
    void theFaceAShotLiesOnIsItsPart() {
        // the helmet's back, 1.75 px past the head's: the head's zone grown by 1.6 px does not reach it
        assertEquals("mesh/Helmet", ModelHits.partAt(BOXES, new Vec3(0, 28 * PX, -5.75 * PX)).part());
        // the chest's front, inside the helmet's box but on the chest's face
        assertEquals("mesh/Chest", ModelHits.partAt(BOXES, new Vec3(0, 23.5 * PX, 3.9 * PX)).part());
        // the head's face through the helmet's open face
        assertEquals("root/head", ModelHits.partAt(BOXES, new Vec3(0, 28 * PX, 4 * PX)).part());
        // off every face
        assertNull(ModelHits.partAt(BOXES, new Vec3(0, 28 * PX, 9 * PX)));
    }

    @Test
    void helmetsByName() {
        assertTrue(ModelHits.helmet(true, "mesh/Helmet"));
        assertTrue(ModelHits.helmet(true, "mesh/Head"));
        assertTrue(ModelHits.helmet(true, "mesh/Eyes"));
        // NTM's layer armour on a bot (the owner's log) and Flywheel's draw of vanilla's helmet
        assertTrue(ModelHits.helmet(true, "root/head/hat/armor/draws/0"));
        assertTrue(ModelHits.helmet(true, "mesh/root/armor/slots/0/draws/0/tree/head"));
        assertFalse(ModelHits.helmet(true, "mesh/root/armor/slots/1/draws/0/tree/body"));
        assertFalse(ModelHits.helmet(true, "mesh/Chest"));
        // a mob's own part, and a suit's group with nothing on the head
        assertFalse(ModelHits.helmet(true, "mesh/root/head"));
        assertFalse(ModelHits.helmet(false, "mesh/Helmet"));
    }
}
