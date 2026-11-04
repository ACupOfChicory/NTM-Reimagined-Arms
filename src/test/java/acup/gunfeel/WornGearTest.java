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
    void aSkeletonWearsThePlayersArmorAndItsRibsAreSolidUnderIt() {
        var ribs = part("root/body", -4, 12, -2, 8, 12, 4, 1).withMesh(ModelMeshTest.tetrahedron().id());
        var skeleton = List.of(part("root/head", -4, 24, -4, 8, 8, 8, 1), ribs, part("root/right_arm", -6, 12, -1, 2, 12, 2, 1),
                part("root/left_arm", 4, 12, -1, 2, 12, 2, 1), part("root/right_leg", -3, 0, -1, 2, 12, 2, 1),
                part("root/left_leg", 1, 0, -1, 2, 12, 2, 1));
        var grown = WornGear.boxes(skeleton, new WornGear.Worn(WornGear.Head.NONE, true, false, true));
        assertBox(find(grown, "root/right_arm"), 1, -8, 11, -3, 6, 14, 6);
        assertBox(find(grown, "root/left_leg"), 1, -1, -1, -3, 6, 14, 6);
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
    void aZoneGrowsWithItsPartAndKeepsItsCut() {
        ModelBox head = part("root/head", -4, 24, -4, 8, 8, 8, 1);
        // the upper half of the head: y >= 28 pixels
        var zone = new HeadZone(head, List.of(new HeadZone.Plane(0, 1, 0, -28 * PX)));
        var grown = WornGear.zones(List.of(zone), HELMET, true).getFirst();
        Vec3 aboveTheBareHead = new Vec3(0, 32.5 * PX, 0), sideOfIt = new Vec3(4.5 * PX, 30 * PX, 0), neck = new Vec3(0, 26 * PX, 0);
        assertFalse(zone.contains(aboveTheBareHead));
        assertTrue(grown.contains(aboveTheBareHead));
        assertTrue(grown.contains(sideOfIt));
        assertFalse(grown.contains(neck));
        assertSame(zone, WornGear.zones(List.of(zone), HELMET, false).getFirst());
    }
}
