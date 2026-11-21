package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import acup.gunfeel.host.render.*;

class TracerGeometryTest {
    @Test void timePathAndStopBoundaries() {
        assertEquals(.05, TracerGeometry.frameTicks(100, Double.NaN));
        assertEquals(.01, TracerGeometry.frameTicks(0, 10));
        assertEquals(.25, TracerGeometry.frameTicks(10, 0));
        assertEquals(2F, TracerGeometry.pixel(90, 0));
        Vec3d start = new Vec3d(1, 2, 3), v = new Vec3d(2, 0, 0), offset = new Vec3d(0, 2, 0);
        assertEquals(new Vec3d(3, 2.5, 3), TracerGeometry.at(start, v, 1, 2, offset, null, Double.NaN, 1));
        Vec3d stop = new Vec3d(4, 5, 6);
        assertSame(stop, TracerGeometry.at(start, v, 1, 2, offset, stop, 1, 1 - 1e-8));
        assertFalse(TracerGeometry.segment(0, Double.NaN, 4, 1, 2, .05).expired());
        assertTrue(TracerGeometry.segment(5, Double.NaN, 4, 1, 2, .05).expired());
    }

    private static final TracerRibbons.Host HOST = new TracerRibbons.Host() {
        public float width() { return .02F; }
        public long start() { return 0; }
        public void drawn(int count, long started) {}
        public void overflow() { fail("unexpected overflow"); }
    };

    @Test void deferredSlotsNeverOverwriteAndOverflowPreservesPendingData() {
        RecordingRenderPort p = new RecordingRenderPort();
        p.defer = true;
        int[] warnings = {0};
        TracerRibbons ribbons = new TracerRibbons(p, new TracerRibbons.Host() {
            public float width() { return HOST.width(); }
            public long start() { return 0; }
            public void drawn(int count, long started) {}
            public void overflow() { warnings[0]++; }
        });
        RecordingRenderPort expected = new RecordingRenderPort();
        TracerRibbons reference = new TracerRibbons(expected, HOST);
        assertEquals(TracerRibbons.SLOTS, p.parts());
        for (int i = 0; i < TracerRibbons.SLOTS; i++) {
            var b = ribbons.begin();
            b.add(new Vec3d(1, 2, 3), new Vec3d(3, 1, 4), Vec3d.ZERO, i, i, .001F);
            b.draw(p);
            var before = reference.begin();
            before.add(new Vec3d(1, 2, 3), new Vec3d(3, 1, 4), Vec3d.ZERO, i, i, .001F);
            before.draw(expected);
            assertThrows(IllegalStateException.class, () -> b.add(Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO, 0, 0, 0));
        }
        assertNull(ribbons.begin());
        assertNull(ribbons.begin());
        assertEquals(1, warnings[0]);
        assertTrue(p.vertices.isEmpty());
        p.drain();
        assertEquals(TracerRibbons.SLOTS * 8, p.vertices.size());
        assertEquals(expected.vertices, p.vertices);
        for (int i = 0; i < TracerRibbons.SLOTS; i++) {
            assertEquals(0xFF000000 | i, p.vertices.get(i * 8).argb());
            assertEquals(0x30000000 | i, p.vertices.get(i * 8 + 3).argb());
        }
        ribbons.begin().draw(p); // An empty submission releases its slot at once.
        var again = ribbons.begin();
        again.add(new Vec3d(2, 4, 6), new Vec3d(4, 2, 8), Vec3d.ZERO, 0xAABBCC, 0xDDEEFF, .001F);
        again.draw(p);
        p.drain();
        assertEquals(TracerRibbons.SLOTS * 8 + 8, p.vertices.size());
        for (int i = 0; i < TracerRibbons.SLOTS; i++) {
            var b = ribbons.begin();
            b.add(new Vec3d(1, 2, 3), new Vec3d(3, 1, 4), Vec3d.ZERO, i, i, .001F);
            b.draw(p);
        }
        assertNull(ribbons.begin());
        assertEquals(1, warnings[0]);
        p.drain();
        assertEquals(TracerRibbons.SLOTS, p.parts());
    }

    @Test void grownSlotIsRefilledWithoutStaleRibbons() {
        RecordingRenderPort p = new RecordingRenderPort();
        p.defer = true;
        TracerRibbons ribbons = new TracerRibbons(p, HOST);
        for (int count : new int[] {33, 1}) {
            var b = ribbons.begin();
            for (int i = 0; i < count; i++)
                b.add(new Vec3d(1, 2, 3), new Vec3d(3, 1, 4), Vec3d.ZERO, i, i, .001F);
            b.draw(p);
            p.vertices.clear();
            p.drain();
            assertEquals(count * 8, p.vertices.size());
        }
        assertEquals(TracerRibbons.SLOTS, p.parts());
    }
}
