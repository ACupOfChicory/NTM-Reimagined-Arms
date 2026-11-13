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
        // a suit helmet's lamp (modelHitboxesSuits, on by default)
        assertTrue(ModelHits.helmet(true, "mesh/Lamps"));
    }

    @Test
    void suitGroupsByThePieceThatDrawsThem() {
        // NTM's ArmorWorldRenderer: the helmet, the chest, the arms, the legs, the boots, the extras on the helmet or chest
        assertEquals(ModelHits.Zone.HEAD, ModelHits.suitZone("mesh/Helmet"));
        assertEquals(ModelHits.Zone.HEAD, ModelHits.suitZone("mesh/Lamps"));
        assertEquals(ModelHits.Zone.BODY, ModelHits.suitZone("mesh/Chest"));
        assertEquals(ModelHits.Zone.BODY, ModelHits.suitZone("mesh/Body"));
        assertEquals(ModelHits.Zone.BODY, ModelHits.suitZone("mesh/Jetpack"));
        assertEquals(ModelHits.Zone.LIMB, ModelHits.suitZone("mesh/LeftArm"));
        assertEquals(ModelHits.Zone.LIMB, ModelHits.suitZone("mesh/RightLeg"));
        assertEquals(ModelHits.Zone.LIMB, ModelHits.suitZone("mesh/LeftBoot"));
        assertEquals(ModelHits.Zone.LIMB, ModelHits.suitZone("mesh/RightFoot"));
        assertEquals(1, ModelHits.suitSlot("mesh/RightArm"));
        assertEquals(2, ModelHits.suitSlot("mesh/LeftLeg"));
        assertEquals(3, ModelHits.suitSlot("mesh/LeftFoot"));
        // a mob's own part, a layer's draw, no group of a suit
        assertNull(ModelHits.suitZone("mesh/root/left_arm"));
        assertNull(ModelHits.suitZone("root/head/hat/armor/draws/0"));
        assertNull(ModelHits.suitZone("mesh/Tail"));
        // only under its own piece: the arms are the chestplate's
        boolean[] chestOnly = {false, true, false, false};
        assertEquals(ModelHits.Zone.LIMB, ModelHits.suitPiece(chestOnly, "mesh/LeftArm"));
        assertNull(ModelHits.suitPiece(chestOnly, "mesh/LeftLeg"));
        assertNull(ModelHits.suitPiece(chestOnly, "mesh/Head"));
    }

    @Test
    void aSuitDrawnByItsOwnModelGrowsNoZone() {
        var all = new WornGear.Worn(WornGear.Head.ARMOR, true, true, true);
        boolean[] ntm = {true, true, true, true};
        // the whole suit drawn by its own model: nothing left to grow the zones by
        var suit = List.of(HEAD, HELMET, CHEST, box("mesh/LeftLeg", 0, 0, 0, 4, 12, 4), box("mesh/RightBoot", 0, 0, 0, 4, 4, 4));
        assertFalse(ModelHits.unsuited(all, ntm, suit).any());
        // the chest and the helmet by their model; the legs and boots (not drawn as groups) still grow theirs
        assertEquals(new WornGear.Worn(WornGear.Head.NONE, false, true, true), ModelHits.unsuited(all, ntm, BOXES));
        // a group under no NTM piece (a mob's own "Head"), or no group at all: as worn
        boolean[] notNtm = {false, true, true, true};
        assertEquals(new WornGear.Worn(WornGear.Head.ARMOR, false, true, true), ModelHits.unsuited(all, notNtm, BOXES));
        assertEquals(all, ModelHits.unsuited(all, ntm, List.of(HEAD)));
        // an extra on the chest (a jetpack) over armour drawn as a layer: the chest still grows its zones
        var extra = List.of(HEAD, box("mesh/Jetpack", -4, 14, 2, 8, 8, 4), box("mesh/Lamps", -4, 30, -4, 8, 2, 8));
        assertEquals(all, ModelHits.unsuited(all, ntm, extra));
        // a skull on the head is no suit's helmet
        var skull = new WornGear.Worn(WornGear.Head.SKULL, true, false, false);
        assertEquals(new WornGear.Worn(WornGear.Head.SKULL, false, false, false), ModelHits.unsuited(skull, ntm, BOXES));
    }
}
