package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

// The port's F3+B colors (ModelHitDebug.green, split): green is the head zone's own cubes cut by its planes, the orange
// outline has nothing under the green.
class OutlineColorsTest {
    private static ModelBox box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return new ModelBox("part", x0, y0, z0, x1 - x0, 0, 0, 0, y1 - y0, 0, 0, 0, z1 - z0);
    }

    private static boolean on(Vec3 p, List<Vec3[]> segments) {
        for (var s : segments) {
            Vec3 d = s[1].subtract(s[0]);
            double t = Math.max(0, Math.min(1, p.subtract(s[0]).dot(d) / d.lengthSqr()));
            if (s[0].add(d.scale(t)).distanceToSqr(p) < 1e-12) return true;
        }
        return false;
    }

    private static List<Vec3[]> edges(ModelBox b) {
        var out = new java.util.ArrayList<Vec3[]>();
        for (int corner = 0; corner < 8; corner++)
            for (int axis = 1; axis <= 4; axis <<= 1)
                if ((corner & axis) == 0) {
                    int other = corner | axis;
                    out.add(new Vec3[] {ModelContours.point(b, corner & 1, corner >> 1 & 1, corner >> 2 & 1),
                            ModelContours.point(b, other & 1, other >> 1 & 1, other >> 2 & 1)});
                }
        return out;
    }

    private static ModelHitDebug.Colored colors(List<ModelBox> boxes, List<HeadZone> zones) {
        return ModelHitDebug.split(ModelContours.edges(boxes), ModelHitDebug.green(zones));
    }

    @Test
    void aHeadCubesEdgesAreGreenWithNoOrangeUnderThem() {
        var body = box(0, 0, 0, 1, 1, 1);
        var head = box(.1, 1, .1, .9, 1.5, .9);
        var c = colors(List.of(body, head), List.of(new HeadZone(head, List.of())));
        for (var e : edges(head))
            for (double f : new double[] {.2, .5, .8}) {
                Vec3 p = e[0].lerp(e[1], f);
                assertTrue(on(p, c.green()), "head edge green at " + p);
                assertFalse(on(p, c.orange()), "no orange under the green at " + p);
            }
        // the body's own edges stay orange
        for (var p : List.of(new Vec3(0, .5, 0), new Vec3(1, .5, 1), new Vec3(.5, 0, 0)))
            { assertTrue(on(p, c.orange()), "body orange at " + p); assertFalse(on(p, c.green()), "body not green at " + p); }
    }

    @Test
    void aCutHeadIsGreenWhereItsPlanesKeepItWithTheCutAcrossItsFaces() {
        var body = box(0, 0, 0, 1, 1, 1);
        var head = box(0, 1, 0, 1, 1.5, 1);
        // the zone keeps x >= .5 of the head
        var zone = new HeadZone(head, List.of(new HeadZone.Plane(1, 0, 0, -.5)));
        var c = colors(List.of(body, head), List.of(zone));
        var front = new Vec3(.75, 1.5, 0); var back = new Vec3(.25, 1.5, 0);
        assertTrue(on(front, c.green()) && !on(front, c.orange()), "the kept half green");
        assertTrue(on(back, c.orange()) && !on(back, c.green()), "the cut-off half orange");
        for (var cut : List.of(new Vec3(.5, 1.5, .5), new Vec3(.5, 1.25, 0), new Vec3(.5, 1.25, 1)))
            assertTrue(on(cut, c.green()), "the plane's cut across the head's faces at " + cut);
    }

    @Test
    void aUnitedHeadIsItsCubesNotTheBoxAroundThem() {
        // without Flywheel a head of several cubes is matched as the box around them (HeadProfiles.unite); its zone is
        // the cubes (HeadZone.pieces), as the server counts it
        var skull = box(0, 1, 0, 1, 1.5, 1);
        var nose = box(.25, 1.1, 1, .75, 1.3, 1.5);
        var united = box(0, 1, 0, 1, 1.5, 1.5);
        var body = box(0, 0, 0, 1, 1, 1.5);
        var c = colors(List.of(body, skull, nose), List.of(new HeadZone(united, List.of(), List.of(skull, nose))));
        for (var cube : List.of(skull, nose))
            for (var e : edges(cube)) {
                Vec3 p = e[0].lerp(e[1], .5);
                assertTrue(on(p, c.green()), "a head cube's edge green at " + p);
                assertFalse(on(p, c.orange()), "no orange under it at " + p);
            }
        // the united box's own edges off the cubes: not green (the strip in front of the body under the nose)
        for (var p : List.of(new Vec3(.5, 1, 1.5), new Vec3(0, 1.25, 1.5), new Vec3(1, 1.5, 1.25)))
            assertFalse(on(p, c.green()), "no green off the head's cubes at " + p);
    }

    @Test
    void theGridFindsTheGreenUnderAnOrangeAcrossCellsAndBelowZero() {
        // a head at negative coordinates, its edges crossing many of the index's cells, the body's edge running on
        // under the head's and past it
        var body = box(-3.1, -2, -7.3, -1.9, -1, -6.2);
        var head = box(-2.9, -1, -7.1, -2.05, -.4, -6.4);
        var c = colors(List.of(body, head), List.of(new HeadZone(head, List.of())));
        for (var e : edges(head))
            for (double f : new double[] {.05, .37, .5, .93}) {
                Vec3 p = e[0].lerp(e[1], f);
                assertTrue(on(p, c.green()), "head edge green at " + p);
                assertFalse(on(p, c.orange()), "no orange under the green at " + p);
            }
        // the body's own edges stay orange
        for (var p : List.of(new Vec3(-3.1, -1.5, -7.3), new Vec3(-2.5, -2, -6.2)))
            assertTrue(on(p, c.orange()) && !on(p, c.green()), "body orange at " + p);
    }

    @Test
    void anOutlineCachedForANearPoseStaysUnderTheGreen() {
        // ModelContours draws a pose with the outline it cached for one within 1/32768 per number: the head's orange edges
        // may stand off the zone's green by more than ModelHitDebug.ON, still far below a pixel
        var body = box(0, 0, 0, 1, 1, 1);
        var head = box(.1, 1, .1, .9, 1.5, .9);
        var cached = box(.1004, 1.0003, .1, .9004, 1.5003, .9);
        var c = ModelHitDebug.split(ModelContours.edges(List.of(body, cached)), ModelHitDebug.green(List.of(new HeadZone(head, List.of()))));
        // the ends too: what the green leaves past its own end is a sliver of 4e-4 block
        for (var e : edges(cached))
            for (double f : new double[] {.0001, .2, .5, .8, .9999}) {
                Vec3 p = e[0].lerp(e[1], f);
                assertFalse(on(p, c.orange()), "no orange by the green at " + p);
            }
        for (var p : List.of(new Vec3(0, .5, 0), new Vec3(1, .5, 1)))
            assertTrue(on(p, c.orange()), "body orange at " + p);
    }

    @Test
    void aBigTurnedHeadsLongEdgesAreGreenToo() {
        // edges across far more of the index's cells than it files them in (ModelHitDebug.Segments.MAX_CELLS)
        var body = box(-1, -1, -1, 1, 0, 1);
        var head = new ModelBox("part", 0, 0, 0, 3, 3, 3, -1, 1, 0, -1, -1, 2);
        var c = colors(List.of(body, head), List.of(new HeadZone(head, List.of())));
        for (var e : edges(head))
            for (double f : new double[] {.1, .5, .9}) {
                Vec3 p = e[0].lerp(e[1], f);
                assertTrue(on(p, c.green()), "head edge green at " + p);
                assertFalse(on(p, c.orange()), "no orange under the green at " + p);
            }
        assertTrue(on(new Vec3(-1, -.5, -1), c.orange()), "body orange");
    }

    @Test
    void aTinyEdgeByTheGreenIsUnderIt() {
        // a mesh's edge a millionth of a block long 8e-4 off the head's edge, a hundredth off its direction: its line
        // leaves the green's ends by more than UNDER, the edge itself does not
        var green = List.<Vec3[]>of(new Vec3[] {new Vec3(0, 0, 0), new Vec3(1, 0, 0)});
        Vec3 a = new Vec3(.5, 8e-4, 0), b = a.add(new Vec3(1, .01, 0).normalize().scale(1e-6));
        var c = ModelHitDebug.split(List.<Vec3[]>of(new Vec3[] {a, b}), green);
        assertTrue(c.orange().isEmpty(), "no orange left");
        // one off the green by more stays
        Vec3 far = new Vec3(.5, 2e-3, 0);
        assertFalse(ModelHitDebug.split(List.<Vec3[]>of(new Vec3[] {far, far.add(new Vec3(1e-6, 0, 0))}), green).orange().isEmpty(), "orange off the green");
    }
}
