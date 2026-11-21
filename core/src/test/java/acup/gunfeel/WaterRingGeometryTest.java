package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import acup.gunfeel.host.render.*;

class WaterRingGeometryTest {
    record Surface(double y, double fx, double fz) implements WaterRingGeometry.Surface {}
    static class Host implements WaterRingGeometry.Host {
        final List<String> calls = new ArrayList<>();
        public Surface surface(double x, double y, double z) {
            calls.add("surface"); return new Surface(0, 1, 0);
        }
        public void connected(int cx, int cz, double y, double[][] allow, int span) {
            calls.add("connected");
            Arrays.fill(allow, null);
            int side = 2 * span + 1;
            allow[span * side + span] = new double[] {0, 0, 0, 0, 0};
        }
        public int light(int x, int y, int z) { calls.add("light"); return 0xF000F0; }
    }

    @Test void delayFlowConnectedRefreshExpiryAndPoolReuse() {
        var geometry = new WaterRingGeometry();
        var host = new Host();
        geometry.add(-.01, 0, -.01, .2, .3, .1, 1, 1, true);
        geometry.update(host, .1, 1.6);
        assertEquals(List.of("surface", "surface", "connected"), host.calls);
        assertEquals(.07, geometry.rings[0].x, 1e-15);
        host.calls.clear();
        geometry.update(host, .1, 1.6);
        assertEquals(List.of("surface", "surface"), host.calls);
        geometry.update(host, .3, 1.6);
        assertEquals(0, geometry.alive());
        for (int i = 0; i < 161; i++) geometry.add(0, 0, 0, 0, 1, .1, 50, 1, true);
        assertEquals(160, geometry.alive());
        assertEquals(12, geometry.widest);
        var allow = geometry.rings[0].allow;
        geometry.clear();
        geometry.add(0, 0, 0, 0, 1, .1, 1, 1, false);
        assertSame(allow, geometry.rings[0].allow);
        assertEquals(0, geometry.rings[0].fm);
    }

    @Test void twoFacesLiftTwistUvsAndRepeatedVertex() {
        var geometry = new WaterRingGeometry();
        var host = new Host();
        geometry.add(.5, 0, .5, 0, 1, .5, .5, 1, false);
        geometry.update(host, 0, 1.6);
        var ring = geometry.rings[0];
        int middle = ring.span * (2 * ring.span + 1) + ring.span;
        ring.allow[middle] = new double[] {0, 0, .4, 0, 0};
        var port = new RecordingRenderPort();
        var renderer = new WaterRingRenderer(port, geometry);
        var batch = renderer.begin();
        batch.bind(host, 0, 0, 0, .125F, .25F, .625F, .75F);
        batch.draw(port);
        assertEquals(16, port.vertices.size());
        assertEquals(port.vertices.get(2), port.vertices.get(3));
        assertEquals(port.vertices.get(6), port.vertices.get(7));
        var below = port.vertices.get(0);
        var above = port.vertices.get(8);
        assertEquals(Float.floatToRawIntBits(.225F - .13F), below.y());
        assertEquals(Float.floatToRawIntBits(.225F + .13F), above.y());
        assertEquals(0xFFFFFFFF, below.argb());
        assertEquals(0xF000F0, below.light());
        assertEquals(Float.floatToRawIntBits(.25F - .125F / 32), below.u());
        assertEquals(Float.floatToRawIntBits(.75F - .125F / 32), below.v());
        ring.t = 1;
        batch = renderer.begin();
        batch.bind(host, 0, 0, 0, 0, 1, 0, 1);
        port.vertices.clear(); batch.draw(port);
        assertTrue(port.vertices.isEmpty());
    }

    @Test void deferredContextsOverflowRefillAndFailureRelease() {
        var geometry = new WaterRingGeometry();
        var host = new Host();
        geometry.add(.5, 0, .5, 0, 1, .5, .5, 1, false);
        geometry.update(host, 0, 1.6);
        var port = new RecordingRenderPort();
        port.defer = true;
        var released = new ArrayList<Integer>();
        int[] warnings = {0};
        var renderer = new WaterRingRenderer(port, geometry, released::add, () -> warnings[0]++);
        var expected = new RecordingRenderPort();
        var reference = new WaterRingRenderer(expected, geometry);
        assertEquals(WaterRingRenderer.SLOTS, port.parts());
        assertTrue(WaterRingRenderer.MATERIAL.depthWrite());
        assertEquals(Layouts.PARTICLE, WaterRingRenderer.MATERIAL.layout());
        for (int i = 0; i < WaterRingRenderer.SLOTS; i++) {
            var batch = renderer.begin();
            batch.bind(host, i, 0, 0, 0, 1, 0, 1);
            batch.draw(port);
            var before = reference.begin();
            before.bind(host, i, 0, 0, 0, 1, 0, 1);
            before.draw(expected);
            assertThrows(IllegalStateException.class, () -> batch.bind(host, 0, 0, 0, 0, 1, 0, 1));
            assertThrows(IllegalStateException.class, () -> batch.draw(port));
        }
        assertNull(renderer.begin());
        assertNull(renderer.begin());
        assertEquals(1, warnings[0]);
        assertTrue(released.isEmpty());
        assertTrue(port.vertices.isEmpty());
        port.drain();
        assertEquals(WaterRingRenderer.SLOTS * 16, port.vertices.size());
        assertEquals(expected.vertices, port.vertices);
        for (int i = 0; i < WaterRingRenderer.SLOTS; i++)
            assertEquals(Float.floatToRawIntBits((float) (.75 - i)), port.vertices.get(i * 16).x());
        assertEquals(WaterRingRenderer.SLOTS, released.size());
        for (int i = 0; i < WaterRingRenderer.SLOTS; i++) assertEquals(i, released.get(i));
        assertEquals(WaterRingRenderer.SLOTS, port.parts());
        for (int i = 0; i < WaterRingRenderer.SLOTS; i++) {
            var batch = renderer.begin();
            batch.bind(host, i, 0, 0, 0, 1, 0, 1);
            batch.draw(port);
        }
        assertNull(renderer.begin());
        assertEquals(1, warnings[0], "overflow warning survives slot reuse");
        port.drain();
        released.clear();
        var first = renderer.begin();
        first.bind(new Host() {
            @Override public int light(int x, int y, int z) { throw new IllegalArgumentException("light"); }
        }, 0, 0, 0, 0, 1, 0, 1);
        first.draw(port);
        assertThrows(IllegalArgumentException.class, port::drain);
        assertEquals(1, released.size());
        assertEquals(0, released.getLast());
        assertSame(first, renderer.begin());
        first.bind(new Host() {
            @Override public int light(int x, int y, int z) { throw new IllegalArgumentException("light"); }
        }, 0, 0, 0, 0, 1, 0, 1);
        port.defer = false;
        assertThrows(IllegalArgumentException.class, () -> first.draw(port));
        assertEquals(2, released.size(), "release once when a synchronous writer throws");
        assertSame(first, renderer.begin());
    }

    @Test void twoSubmissionsInOneRenderedFrameKeepTheirDeferredParts() {
        var geometry = new WaterRingGeometry();
        var host = new Host();
        geometry.add(.5, 0, .5, 0, 1, .5, .5, 1, false);
        geometry.update(host, 0, 1.6);
        var port = new RecordingRenderPort();
        port.defer = true;
        var released = new ArrayList<Integer>();
        var renderer = new WaterRingRenderer(port, geometry, released::add);
        var expected = new RecordingRenderPort();
        var reference = new WaterRingRenderer(expected, geometry);
        for (int view = 0; view < 2; view++) {
            renderer.beginFrame(42);
            assertTrue(released.isEmpty(), "the second submit must keep the first view's slots");
            for (int i = 0; i < WaterRingRenderer.SLOTS / 2; i++) {
                int camera = view * WaterRingRenderer.SLOTS / 2 + i;
                var batch = renderer.begin();
                assertNotNull(batch);
                batch.bind(host, camera, view, 0, .125F * view, 1, 0, 1);
                batch.draw(port);
                var before = reference.begin();
                before.bind(host, camera, view, 0, .125F * view, 1, 0, 1);
                before.draw(expected);
            }
        }
        renderer.beginFrame(42);
        assertTrue(released.isEmpty());
        assertNull(renderer.begin(), "both views still own all slots");
        assertDoesNotThrow(port::drain);
        assertEquals(WaterRingRenderer.SLOTS * 16, port.vertices.size());
        assertEquals(expected.vertices, port.vertices, "both views draw with their original contexts");
        assertEquals(WaterRingRenderer.SLOTS, released.size());
    }

    @Test void acceptedButNeverWrittenSlotsReturnAtTheNextFrame() {
        var geometry = new WaterRingGeometry();
        var host = new Host();
        geometry.add(.5, 0, .5, 0, 1, .5, .5, 1, false);
        geometry.update(host, 0, 1.6);
        var port = new RecordingRenderPort();
        port.defer = true;
        var released = new ArrayList<Integer>();
        int[] warnings = {0};
        var renderer = new WaterRingRenderer(port, geometry, released::add, () -> warnings[0]++);
        WaterRingRenderer.Batch first = null;
        for (int frame = 0; frame < 300; frame++) {
            renderer.beginFrame(frame);
            // every slot accepted by the collector, no callback run
            for (int i = 0; i < WaterRingRenderer.SLOTS; i++) {
                var batch = renderer.begin();
                assertNotNull(batch, "frame " + frame + " submission " + i);
                if (i == 0) {
                    if (first == null) first = batch;
                    else assertSame(first, batch);
                }
                batch.bind(host, i, 0, 0, 0, 1, 0, 1);
                batch.draw(port);
            }
        }
        assertEquals(0, warnings[0]);
        assertEquals(299 * WaterRingRenderer.SLOTS, released.size(), "each discarded slot lets its host go");
        renderer.beginFrame(300);
        assertEquals(300 * WaterRingRenderer.SLOTS, released.size());
        assertThrows(IllegalStateException.class, port::drain, "a callback after release remains an error");
        // a fresh frame draws again
        port.defer = false;
        var batch = renderer.begin();
        batch.bind(host, 0, 0, 0, 0, 1, 0, 1);
        batch.draw(port);
        assertEquals(16, port.vertices.size());
    }
}
