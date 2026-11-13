package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class WornGearTest {
    private static final double PX = 1 / 16.0;
    private static final WornGear.Worn HELMET = new WornGear.Worn(WornGear.Head.ARMOR, false, false, false);

    // a part of w x h x d pixels at scale (a block = 16 pixels), its lower corner at (x, y, z) pixels
    private static ModelBox part(String name, double x, double y, double z, double w, double h, double d, double scale) {
        double s = PX * scale;
        return new ModelBox(name, x * s, y * s, z * s, w * s, 0, 0, 0, h * s, 0, 0, 0, d * s);
    }

    private static List<ModelBox> zombie(double scale) {
        return List.of(part("root/head", -4, 24, -4, 8, 8, 8, scale), part("root/head/hat", -4.5, 23.5, -4.5, 9, 9, 9, scale),
                part("root/body", -4, 12, -2, 8, 12, 4, scale), part("root/right_arm", -8, 12, -2, 4, 12, 4, scale),
                part("root/left_arm", 4, 12, -2, 4, 12, 4, scale), part("root/right_leg", -4, 0, -2, 4, 12, 4, scale),
                part("root/left_leg", 0, 0, -2, 4, 12, 4, scale));
    }

    private static ModelBox find(List<ModelBox> boxes, String part) {
        for (ModelBox b : boxes) if (b.part().equals(part)) return b;
        throw new AssertionError(part);
    }

    // the box's corner and edge lengths, in pixels at scale
    private static void assertBox(ModelBox b, double scale, double x, double y, double z, double w, double h, double d) {
        double s = PX * scale;
        assertEquals(x, b.x() / s, 1e-9, "x");
        assertEquals(y, b.y() / s, 1e-9, "y");
        assertEquals(z, b.z() / s, 1e-9, "z");
        assertEquals(w, b.ux() / s, 1e-9, "w");
        assertEquals(h, b.vy() / s, 1e-9, "h");
        assertEquals(d, b.wz() / s, 1e-9, "d");
    }

    @Test
    void nothingWornLeavesTheSameBoxes() {
        var boxes = zombie(1);
        assertSame(boxes, WornGear.boxes(boxes, WornGear.Worn.NOTHING));
    }

    @Test
    void aHelmetIsTheHeadOnePixelLargerEachSideAndCoversTheHat() {
        var grown = WornGear.boxes(zombie(1), HELMET);
        assertBox(find(grown, "root/head"), 1, -5, 23, -5, 10, 10, 10);
        assertBox(find(grown, "root/head/hat"), 1, -5, 23, -5, 10, 10, 10);
        assertBox(find(grown, "root/body"), 1, -4, 12, -2, 8, 12, 4);
    }

    @Test
    void aBabyGetsItsOwnPixel() {
        assertBox(find(WornGear.boxes(zombie(0.5), HELMET), "root/head"), 0.5, -5, 23, -5, 10, 10, 10);
    }

    @Test
    void leggingsHalfAPixelBootsAndChestplateOne() {
        var legs = WornGear.boxes(zombie(1), new WornGear.Worn(WornGear.Head.NONE, false, true, false));
        assertBox(find(legs, "root/right_leg"), 1, -4.5, -0.5, -2.5, 5, 13, 5);
        assertBox(find(legs, "root/body"), 1, -4.5, 11.5, -2.5, 9, 13, 5);
        assertBox(find(legs, "root/left_arm"), 1, 4, 12, -2, 4, 12, 4);
        var all = WornGear.boxes(zombie(1), new WornGear.Worn(WornGear.Head.NONE, true, true, true));
        assertBox(find(all, "root/right_leg"), 1, -5, -1, -3, 6, 14, 6);
        assertBox(find(all, "root/body"), 1, -5, 11, -3, 10, 14, 6);
        assertBox(find(all, "root/left_arm"), 1, 3, 11, -3, 6, 14, 6);
    }

    @Test
    void aSkeletonsThinLimbsGrowByTheSideAndItsRibsAreSolidUnderIt() {
        var ribs = part("root/body", -4, 12, -2, 8, 12, 4, 1).withMesh(ModelMeshTest.tetrahedron().id());
        var skeleton = List.of(part("root/head", -4, 24, -4, 8, 8, 8, 1), ribs, part("root/right_arm", -6, 12, -1, 2, 12, 2, 1),
                part("root/left_arm", 4, 12, -1, 2, 12, 2, 1), part("root/right_leg", -3, 0, -1, 2, 12, 2, 1),
                part("root/left_leg", 1, 0, -1, 2, 12, 2, 1));
        var grown = WornGear.boxes(skeleton, new WornGear.Worn(WornGear.Head.NONE, true, false, true));
        // the drawn armor is caught as its layer's own geometry; the thin limb under it only gets its side
        assertBox(find(grown, "root/right_arm"), 1, -7, 11, -2, 4, 14, 4);
        assertBox(find(grown, "root/left_leg"), 1, 0, -1, -2, 4, 14, 4);
        ModelBox body = find(grown, "root/body");
        assertBox(body, 1, -5, 11, -3, 10, 14, 6);
        assertEquals(0, body.mesh());
        assertSame(ribs, find(WornGear.boxes(skeleton, HELMET), "root/body"));
    }

    @Test
    void skullAndBlockGrowTheHeadToTheirSizeOnAnyModel() {
        var villager = List.of(part("root/head", -4, 24, -4, 8, 10, 8, 1), part("root/body", -4, 12, -3, 8, 12, 6, 1));
        assertBox(find(WornGear.boxes(villager, HELMET), "root/head"), 1, -4, 24, -4, 8, 10, 8);
        var skull = WornGear.boxes(villager, new WornGear.Worn(WornGear.Head.SKULL, false, false, false));
        assertBox(find(skull, "root/head"), 1, -4.75, 24, -4.75, 9.5, 10, 9.5);
        var pumpkin = WornGear.boxes(zombie(1), new WornGear.Worn(WornGear.Head.BLOCK, false, false, false));
        assertBox(find(pumpkin, "root/head"), 1, -5, 23, -5, 10, 10, 10);
        assertBox(find(pumpkin, "root/right_leg"), 1, -4, 0, -2, 4, 12, 4);
    }

    @Test
    void aSkullStandsOnTheBaseOfTheHeadAndADragonsHeadIsLarger() {
        var skull = WornGear.boxes(zombie(1), new WornGear.Worn(WornGear.Head.SKULL, false, false, false));
        assertBox(find(skull, "root/head"), 1, -4.75, 24, -4.75, 9.5, 9.5, 9.5);
        var dragon = WornGear.boxes(zombie(1), new WornGear.Worn(WornGear.Head.DRAGON, false, false, false));
        assertBox(find(dragon, "root/head"), 1, -7.125, 24, -7.125, 14.25, 14.25, 14.25);
        var old = WornGear.boxes(zombie(1), new WornGear.Worn(WornGear.Head.OLD_SKULL, false, false, false));
        assertBox(find(old, "root/head"), 1, -4.25, 24, -4.25, 8.5, 8.5, 8.5);
        // a head whose up edge points down (its corner on top) keeps the same lower face
        var hanging = List.of(new ModelBox("root/head", -4 * PX, 32 * PX, -4 * PX, 8 * PX, 0, 0, 0, -8 * PX, 0, 0, 0, 8 * PX));
        ModelBox h = find(WornGear.boxes(hanging, new WornGear.Worn(WornGear.Head.SKULL, false, false, false)), "root/head");
        assertEquals(33.5, h.y() / PX, 1e-9);
        assertEquals(-9.5, h.vy() / PX, 1e-9);
    }

    @Test
    void aSuitThatHidesTheLegsStillGrowsTheRest() {
        var legless = zombie(1).stream().filter(b -> !b.part().endsWith("_leg")).toList();
        assertTrue(WornGear.humanoid(legless));
        assertBox(find(WornGear.boxes(legless, HELMET), "root/head"), 1, -5, 23, -5, 10, 10, 10);
        assertFalse(WornGear.humanoid(List.of(part("root/head", -4, 24, -4, 8, 8, 8, 1), part("root/body", -4, 12, -2, 8, 12, 4, 1))));
    }

    @Test
    void theArmorLayersOwnDrawIsLeftAsItIs() {
        var helmet = part("mesh/root/armor/slots/0/draws/0/tree/head", -5, 23, -5, 10, 10, 10, 1);
        var boxes = new java.util.ArrayList<>(zombie(1));
        boxes.add(helmet);
        var grown = WornGear.boxes(boxes, HELMET);
        assertSame(helmet, find(grown, "mesh/root/armor/slots/0/draws/0/tree/head"));
        assertBox(find(grown, "root/head"), 1, -5, 23, -5, 10, 10, 10);
    }

    @Test
    void aZoneGrowsWithItsPartAndKeepsItsCut() {
        ModelBox head = part("root/head", -4, 24, -4, 8, 8, 8, 1);
        // the upper half of the head: y >= 28 pixels
        var zone = new HeadZone(head, List.of(new HeadZone.Plane(0, 1, 0, -28 * PX)));
        var grown = WornGear.zones(List.of(zone), HELMET, true).get(0);
        Vec3 aboveTheBareHead = new Vec3(0, 32.5 * PX, 0), sideOfIt = new Vec3(4.5 * PX, 30 * PX, 0), neck = new Vec3(0, 26 * PX, 0);
        assertFalse(zone.contains(aboveTheBareHead));
        assertTrue(grown.contains(aboveTheBareHead));
        assertTrue(grown.contains(sideOfIt));
        assertFalse(grown.contains(neck));
        assertSame(zone, WornGear.zones(List.of(zone), HELMET, false).get(0));
    }

    // 26.2 (ModelHits.wornBoxes): worn armor is in the hitbox as it is drawn, caught as its layer's geometry; the
    // parts under it do not grow. A skull or a block on the head grows it unless Flywheel caught it as a layer.
    @Test
    void theHitboxTakesTheDrawnArmorAndGrowsNoPartUnderIt() {
        var boxes = zombie(1);
        var all = new WornGear.Worn(WornGear.Head.ARMOR, true, true, true);
        assertSame(WornGear.Worn.NOTHING, ModelHits.drawn(all, boxes));
        assertSame(boxes, WornGear.boxes(boxes, ModelHits.drawn(all, boxes)));
        var skull = new WornGear.Worn(WornGear.Head.SKULL, true, false, true);
        assertEquals(new WornGear.Worn(WornGear.Head.SKULL, false, false, false), ModelHits.drawn(skull, boxes));
        var caught = new java.util.ArrayList<>(boxes);
        caught.add(part("mesh/root/wornHead/draws/0/tree/head", -4.75, 24, -4.75, 9.5, 9.5, 9.5, 1));
        assertSame(WornGear.Worn.NOTHING, ModelHits.drawn(skull, caught));
        assertSame(WornGear.Worn.NOTHING, ModelHits.drawn(WornGear.Worn.NOTHING, boxes));
    }

    // the mob AI's bots (BotModel): each arm and leg in two parts, the lower one with 2 px of overlap into the upper
    private static List<ModelBox> bot() {
        return List.of(part("root/head", -4, 24, -4, 8, 8, 8, 1), part("root/body", -4, 12, -2, 8, 12, 4, 1),
                part("root/right_arm", -8, 18, -2, 4, 6, 4, 1), part("root/right_arm/forearm", -8, 12, -2, 4, 8, 4, 1),
                part("root/left_arm", 4, 18, -2, 4, 6, 4, 1), part("root/left_arm/forearm", 4, 12, -2, 4, 8, 4, 1),
                part("root/right_leg", -4, 6, -2, 4, 6, 4, 1), part("root/right_leg/shin", -4, 0, -2, 4, 8, 4, 1),
                part("root/left_leg", 0, 6, -2, 4, 6, 4, 1), part("root/left_leg/shin", 0, 0, -2, 4, 8, 4, 1));
    }

    private static ModelBox zone(List<ModelBox> boxes, String part, WornGear.Worn worn) {
        var zone = new HeadZone(find(boxes, part), List.of());
        return WornGear.zones(List.of(zone), worn, WornGear.humanoid(boxes), WornGear.split(boxes)).get(0).box();
    }

    @Test
    void aBotsLimbsInTwoPartsGrowByTheirThicknessAsTheWholeLimbDoes() {
        var bot = bot();
        assertTrue(WornGear.split(bot));
        assertFalse(WornGear.split(zombie(1)));
        var chest = new WornGear.Worn(WornGear.Head.NONE, true, false, false);
        // a sleeve is the arm's box 1 px larger on each side: its upper half and its forearm alike (not by half a
        // pixel, as the upper arm's 6 px length taken for the whole arm's 12 would give)
        assertBox(zone(bot, "root/right_arm", chest), 1, -9.1, 16.9, -3.1, 6.2, 8.2, 6.2);
        assertBox(zone(bot, "root/right_arm/forearm", chest), 1, -9.1, 10.9, -3.1, 6.2, 10.2, 6.2);
        var legs = new WornGear.Worn(WornGear.Head.NONE, false, true, false);
        assertBox(zone(bot, "root/left_leg", legs), 1, -0.6, 5.4, -2.6, 5.2, 7.2, 5.2);
        assertBox(zone(bot, "root/left_leg/shin", legs), 1, -0.6, -0.6, -2.6, 5.2, 9.2, 5.2);
        var boots = new WornGear.Worn(WornGear.Head.NONE, false, true, true);
        assertBox(zone(bot, "root/right_leg/shin", boots), 1, -5.1, -1.1, -3.1, 6.2, 10.2, 6.2);
        // the helmet's hat, 1.5 px out of the head (SECURITY's 11 px on the stand), in the head zone too; every zone
        // a tenth of a pixel past the armor drawn
        assertBox(zone(bot, "root/head", HELMET), 1, -5.6, 22.4, -5.6, 11.2, 11.2, 11.2);
    }

    // the owner, 2026-11-10: a shot on an FBI agent's helmet was not a headshot. The helmet is drawn 1 px out of the
    // 8 px head, and the bot's head zone was the bare head's
    @Test
    void aShotOnABotsHelmetIsInItsHead() {
        var bot = bot();
        var zone = new HeadZone(find(bot, "root/head"), List.of());
        var grown = WornGear.zones(List.of(zone), ModelHits.armour(HELMET), true, true);
        // on the helmet's face, its top and its side: half a pixel past the head, the helmet's face, its hat's
        for (Vec3 p : new Vec3[] { new Vec3(0, 28 * PX, -4.5 * PX), new Vec3(0, 33 * PX, 0), new Vec3(5 * PX, 28 * PX, 0),
                new Vec3(0, 28 * PX, -5.5 * PX), new Vec3(5.5 * PX, 33.5 * PX, 0) }) {
            assertFalse(zone.contains(p), "bare " + p);
            assertTrue(HeadZone.contains(grown, p), "helmet " + p);
        }
        // two pixels past the head is past the helmet
        assertFalse(HeadZone.contains(grown, new Vec3(0, 28 * PX, -6 * PX)));
    }

    // a glass block on a bot's head (the GLASS+PAA loadout) is no armour: it grows neither its boxes nor its zones
    @Test
    void aBotsZonesGrowWithItsArmourAlone() {
        assertSame(HELMET, ModelHits.armour(HELMET));
        var glass = new WornGear.Worn(WornGear.Head.BLOCK, true, true, true);
        assertEquals(new WornGear.Worn(WornGear.Head.NONE, true, true, true), ModelHits.armour(glass));
        assertSame(WornGear.Worn.NOTHING, ModelHits.armour(WornGear.Worn.NOTHING));
        assertEquals(new WornGear.Worn(WornGear.Head.NONE, false, false, false),
                ModelHits.armour(new WornGear.Worn(WornGear.Head.SKULL, false, false, false)));
    }

    // a husk or a player in a layer's armour (SECURITY, CMB, Schrabidium; Flywheel's ".../armor/slots/<n>/draws/..."):
    // the chestplate's collar stands a pixel over the neck, inside the head's zone grown under the helmet. Drawn, it is
    // the chestplate: the piece hit decides (ModelHits.layered), the zones stay bare, and a skull still grows the head
    @Test
    void aChestplatesCollarOnAHuskIsTheBody() {
        var boxes = new java.util.ArrayList<>(zombie(1));
        boxes.add(part("mesh/root/armor/slots/0/draws/0/tree/head", -5, 23, -5, 10, 10, 10, 1));
        boxes.add(part("mesh/root/armor/slots/1/draws/0/tree/body", -5, 11, -3, 10, 14, 6, 1));
        Vec3 collar = new Vec3(0, 24.5 * PX, -3 * PX);
        var grown = WornGear.zones(List.of(new HeadZone(find(boxes, "root/head"), List.of())), HELMET, true, false);
        assertTrue(HeadZone.contains(grown, collar), "the grown head zone takes the collar");
        assertEquals(ModelHits.Zone.BODY, ModelHits.armourZone(boxes, collar));
        assertEquals(ModelHits.Zone.HEAD, ModelHits.armourZone(boxes, new Vec3(0, 28 * PX, -5 * PX)));
        assertEquals(WornGear.Worn.NOTHING, ModelHits.unarmoured(new WornGear.Worn(WornGear.Head.ARMOR, true, true, true), boxes));
        assertEquals(new WornGear.Worn(WornGear.Head.SKULL, false, false, false),
                ModelHits.unarmoured(new WornGear.Worn(WornGear.Head.SKULL, true, false, true), boxes));
        // a helmet not caught as a layer (an NTM suit's own, drawn otherwise) still grows the head; the chest alone
        // caught leaves the head's growth
        var chestOnly = new java.util.ArrayList<>(zombie(1));
        chestOnly.add(find(boxes, "mesh/root/armor/slots/1/draws/0/tree/body"));
        assertEquals(HELMET, ModelHits.unarmoured(new WornGear.Worn(WornGear.Head.ARMOR, true, true, false), chestOnly));
        assertSame(HELMET, ModelHits.unarmoured(HELMET, zombie(1)));
    }

    // the bots' armour caught as its layer's draws (NtmMeshHits: "<part>/armor/draws/0"): a shot on a piece is in the
    // part under it, told by the face it lies on, where grown zones would overlap (a chestplate's lower edge and the
    // thigh's zone grown up a pixel)
    @Test
    void aShotOnABotsArmourIsInThePartUnderThePiece() {
        var boxes = new java.util.ArrayList<>(bot());
        boxes.add(part("root/head/armor/draws/0", -5, 23, -5, 10, 10, 10, 1));
        boxes.add(part("root/body/armor/draws/0", -5, 11, -3, 10, 14, 6, 1));
        boxes.add(part("root/right_leg/armor/draws/0", -4.5, 6, -2.5, 5, 6.5, 5, 1));
        boxes.add(part("root/right_arm/armor/draws/0", -9, 18, -3, 6, 7, 6, 1));
        assertEquals(ModelHits.Zone.HEAD, ModelHits.armourZone(boxes, new Vec3(0, 28 * PX, -5 * PX)));
        assertEquals(ModelHits.Zone.HEAD, ModelHits.armourZone(boxes, new Vec3(5 * PX, 32 * PX, 2 * PX)));
        // through the helmet's open face onto the head: the head's own zone decides
        assertNull(ModelHits.armourZone(boxes, new Vec3(0, 28 * PX, -4 * PX)));
        // the chestplate's lower edge over the thigh, and its side by the shoulder
        assertEquals(ModelHits.Zone.BODY, ModelHits.armourZone(boxes, new Vec3(-2 * PX, 11.5 * PX, -3 * PX)));
        assertEquals(ModelHits.Zone.BODY, ModelHits.armourZone(boxes, new Vec3(-5 * PX, 20 * PX, 2.5 * PX)));
        assertEquals(ModelHits.Zone.LIMB, ModelHits.armourZone(boxes, new Vec3(-9 * PX, 21 * PX, 0)));
        assertEquals(ModelHits.Zone.LIMB, ModelHits.armourZone(boxes, new Vec3(-2 * PX, 9 * PX, -2.5 * PX)));
        assertNull(ModelHits.armourZone(boxes, new Vec3(1, 1, 1)));
        // Flywheel's draws name the part after "tree"
        assertEquals(ModelHits.Zone.LIMB, ModelHits.armourPartZone("mesh/root/armor/slots/1/draws/0/tree/right_arm/forearm"));
        assertEquals(ModelHits.Zone.HEAD, ModelHits.armourPartZone("mesh/root/armor/slots/0/draws/0/tree/head/hat"));
        assertEquals(ModelHits.Zone.BODY, ModelHits.armourPartZone("mesh/root/armor/slots/1/draws/0/tree/body"));
        assertFalse(ModelHits.armourPiece("root/head/hat"));
    }
}
