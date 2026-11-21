package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import acup.gunfeel.host.render.Intent;

/** Real 26.2 Shot.at/ribbon, RenderPort26 and its deferred callback against c84fd5f. No client or GPU. */
class TracersAdapterTest {
    private static final TracerRibbons.Host HOST = new TracerRibbons.Host() {
        public float width() { return OldTracers4.Config.tracerWidth; }
        public long start() { return 0; }
        public void drawn(int count, long started) {}
        public void overflow() { fail("unexpected overflow"); }
    };

    private static OldTracers4.Shot shot(Random r, Vec3 cam) {
        var s = TracersBitTest.shot(r, cam);
        // ShotPayload transports velocity as floats, before either implementation receives it.
        s.v = new Vec3((float) s.v.x, (float) s.v.y, (float) s.v.z);
        s.speed = Math.max(s.v.length(), 1e-3);
        return s;
    }

    private static Tracers.Shot adapter(OldTracers4.Shot s) {
        var packet = new ShotPayload(0, -1, s.start.x, s.start.y, s.start.z,
                (float) s.v.x, (float) s.v.y, (float) s.v.z, s.g, (int) s.lifeTicks, 1,
                Double.NaN, Double.NaN, Double.NaN, 1);
        var result = new Tracers.Shot(packet, null, new int[] {s.dark, s.light}, 0);
        result.muzzleOffset = s.muzzleOffset;
        result.stopPoint = s.stopPoint;
        result.confirmedEnd = s.confirmedEnd;
        result.end = s.end;
        return result;
    }

    private static void same(Vec3 expected, Vec3 actual, String context) {
        assertEquals(TracersBitTest.bits(expected.x), TracersBitTest.bits(actual.x), context + " x");
        assertEquals(TracersBitTest.bits(expected.y), TracersBitTest.bits(actual.y), context + " y");
        assertEquals(TracersBitTest.bits(expected.z), TracersBitTest.bits(actual.z), context + " z");
    }

    @Test void realShotAtAndRibbonsMatchOldAdapterBits() {
        TracerRibbons ribbons = new TracerRibbons(RenderPort26.INSTANCE, HOST);
        for (int seed = 0; seed < 2000; seed++) {
            Random r = new Random(202611200100L + seed);
            Vec3 cam = TracersBitTest.vector(r, seed % 2 == 0 ? 3e7 : 10);
            OldTracers4.Config.tracerWidth = seed % 7 == 0 ? 0 : r.nextFloat() * .05F;
            OldTracers4.Config.tracerLength = r.nextFloat() * 12;
            double[] frame = OldTracers4.frame(r.nextDouble() * 10, seed % 11 == 0 ? Double.NaN : r.nextDouble() * 10,
                    r.nextInt(161) + 10, r.nextInt(3000) - 1);
            var old = new TracersBitTest.Queue();
            var actual = new TracersBitTest.Queue();
            PoseStack pose = new PoseStack();
            pose.mulPose(new Quaternionf().rotationXYZ(r.nextFloat(), r.nextFloat(), r.nextFloat()));
            pose.translate(r.nextFloat(), r.nextFloat(), r.nextFloat());
            ribbons.beginFrame(seed);
            for (int submit = 0; submit < 3; submit++) {
                var batch = ribbons.begin();
                List<float[]> before = new ArrayList<>();
                for (int j = 0; j < 12; j++) {
                    var s = shot(r, cam);
                    // Exercise both the identity-return stop and the pre-stop gravity/muzzle path.
                    if (j % 2 == 0) { s.stopPoint = cam.add(1, 2, 3); s.confirmedEnd = 2; }
                    var now = adapter(s);
                    for (double t : new double[] {0, r.nextDouble() * 20, .001, 2 - 1e-8,
                            Math.nextDown(2 - 1e-8), 2, Math.nextUp(2), s.lifeTicks}) {
                        same(s.at(t), now.at(t), "seed " + seed + " t " + t);
                        if (s.stopPoint != null && t >= s.confirmedEnd - 1e-8) assertSame(s.stopPoint, now.at(t));
                    }
                    double age = r.nextDouble() * 20;
                    var segment = OldTracers4.segment(s, age, frame[0]);
                    if (segment.expired() || !segment.visible()) continue;
                    before.add(OldTracers4.ribbon(s, segment.head(), segment.tail(), cam, (float) frame[1]));
                    now.ribbon(batch, segment.head(), segment.tail(), cam, (float) frame[1]);
                }
                OldTracers4.submit(old.event(pose), before);
                RenderPort26.submitAt(actual.event(pose), cam, 0, Intent.QUEUE, batch);
            }
            assertEquals(old.draw(), actual.draw(), "adapter calls/bits/order seed " + seed);
        }
    }

    @Test void twoSubmitsInOneRenderedFrameKeepBothCallbacksAlive() {
        var ribbons = new TracerRibbons(RenderPort26.INSTANCE, HOST);
        var firstQueue = new TracersBitTest.Queue();
        var secondQueue = new TracersBitTest.Queue();
        PoseStack pose = new PoseStack();
        var bus = BusBuilder.builder().build();
        bus.register(RenderFrames.class);
        long previousFrame = RenderPort26.frameNumber();
        bus.post(new RenderFrameEvent.Pre(null));
        long frame = RenderPort26.frameNumber();
        assertEquals(previousFrame + 1, frame);
        ribbons.beginFrame(frame);
        var first = ribbons.begin();
        first.add(1, 2, 3, 3, 1, 4, 0, 0, 0, 0x112233, 0x445566, .001F);
        RenderPort26.submitAt(firstQueue.event(pose), Vec3.ZERO, 0, Intent.QUEUE, first);

        assertEquals(frame, RenderPort26.frameNumber());
        ribbons.beginFrame(RenderPort26.frameNumber());
        var second = ribbons.begin();
        assertNotSame(first, second);
        second.add(2, 4, 6, 4, 2, 8, 0, 0, 0, 0x778899, 0xAABBCC, .001F);
        RenderPort26.submitAt(secondQueue.event(pose), Vec3.ZERO, 0, Intent.QUEUE, second);
        var firstDraw = firstQueue.draw();
        var secondDraw = secondQueue.draw();
        assertEquals(8, firstDraw.stream().filter(s -> s.startsWith("argb")).count());
        assertEquals(8, secondDraw.stream().filter(s -> s.startsWith("argb")).count());
        assertNotEquals(firstDraw, secondDraw);

        // An accepted callback discarded by the collector returns its slot only in the next rendered frame.
        var discarded = ribbons.begin();
        discarded.add(1, 2, 3, 3, 1, 4, 0, 0, 0, 1, 2, .001F);
        var discardedQueue = new TracersBitTest.Queue();
        RenderPort26.submitAt(discardedQueue.event(pose), Vec3.ZERO, 0, Intent.QUEUE, discarded);
        assertEquals(frame, RenderPort26.frameNumber());
        ribbons.beginFrame(RenderPort26.frameNumber());
        var held = ribbons.begin();
        assertNotSame(discarded, held);
        bus.post(new RenderFrameEvent.Pre(null));
        assertEquals(frame + 1, RenderPort26.frameNumber());
        ribbons.beginFrame(RenderPort26.frameNumber());
        assertSame(discarded, ribbons.begin());
    }

    @Test void releasedCallbackStillFailsIfItArrivesInALaterFrame() {
        var ribbons = new TracerRibbons(RenderPort26.INSTANCE, HOST);
        var queue = new TracersBitTest.Queue();
        ribbons.beginFrame(1);
        var batch = ribbons.begin();
        batch.add(1, 2, 3, 3, 1, 4, 0, 0, 0, 1, 2, .001F);
        RenderPort26.submitAt(queue.event(new PoseStack()), Vec3.ZERO, 0, Intent.QUEUE, batch);
        ribbons.beginFrame(2);
        assertEquals("tracer part without submission", assertThrows(IllegalStateException.class, queue::draw).getMessage());
    }

    @Test void acceptedButDiscardedSubmissionsReturnAtTheNextFrame() {
        var ribbons = new TracerRibbons(RenderPort26.INSTANCE, HOST);
        var queue = new TracersBitTest.Queue();
        var event = queue.event(new PoseStack());
        TracerRibbons.Batch first = null;
        for (int frame = 0; frame < 300; frame++) {
            ribbons.beginFrame(frame);
            // All slots were accepted by the real collector but no callback was executed.
            for (int i = 0; i < TracerRibbons.SLOTS; i++) {
                var batch = ribbons.begin();
                assertNotNull(batch, "frame " + frame + " submission " + i);
                if (i == 0) {
                    if (first == null) first = batch;
                    else assertSame(first, batch);
                }
                batch.add(1, 2, 3, 3, 1, 4, 0, 0, 0, i, i, .001F);
                RenderPort26.submitAt(event, Vec3.ZERO, 0, Intent.QUEUE, batch);
            }
            assertEquals(TracerRibbons.SLOTS, queue.submissions.size());
            queue.submissions.clear();
        }
        ribbons.beginFrame(300);
        var batch = ribbons.begin();
        batch.add(1, 2, 3, 3, 1, 4, 0, 0, 0, 1, 2, .001F);
        RenderPort26.submitAt(event, Vec3.ZERO, 0, Intent.QUEUE, batch);
        assertFalse(queue.draw().isEmpty());
    }

    /** Fixed-size collector and nonallocating consumer, identical for both measured paths. */
    private static final class FrameQueue implements VertexConsumer {
        final PoseStack pose = new PoseStack();
        SubmitNodeCollector.CustomGeometryRenderer renderer;
        long checksum;
        final SubmitNodeCollector collector = (SubmitNodeCollector) Proxy.newProxyInstance(SubmitNodeCollector.class.getClassLoader(),
                new Class<?>[] {SubmitNodeCollector.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("submitCustomGeometry")) throw new AssertionError(method);
                    renderer = (SubmitNodeCollector.CustomGeometryRenderer) args[2];
                    return null;
                });
        final SubmitCustomGeometryEvent event = new SubmitCustomGeometryEvent(null, collector, pose, List.of());
        void draw() { if (renderer != null) { renderer.render(pose.last(), this); renderer = null; } }
        void point(Vec3 p) { checksum += Double.doubleToRawLongBits(p.x) ^ Double.doubleToRawLongBits(p.y) ^ Double.doubleToRawLongBits(p.z); }
        public VertexConsumer addVertex(float x, float y, float z) {
            checksum += Float.floatToRawIntBits(x) ^ Float.floatToRawIntBits(y) ^ Float.floatToRawIntBits(z); return this;
        }
        public VertexConsumer setColor(int r, int g, int b, int a) { checksum += r + g + b + a; return this; }
        public VertexConsumer setColor(int argb) { checksum += argb; return this; }
        public VertexConsumer setUv(float u, float v) { throw new AssertionError(); }
        public VertexConsumer setUv1(int u, int v) { throw new AssertionError(); }
        public VertexConsumer setUv2(int u, int v) { throw new AssertionError(); }
        public VertexConsumer setNormal(float x, float y, float z) { throw new AssertionError(); }
        public VertexConsumer setLineWidth(float width) { throw new AssertionError(); }
    }

    private static final class Frames {
        final OldTracers4.Shot[] old = new OldTracers4.Shot[96];
        final Tracers.Shot[] actual = new Tracers.Shot[old.length];
        final double[] ages = new double[old.length];
        final FrameQueue before = new FrameQueue(), after = new FrameQueue();
        final TracerRibbons ribbons = new TracerRibbons(RenderPort26.INSTANCE, HOST);
        final Vec3 cam = new Vec3(3e7, 64, -3e7);
        long frame;
        Frames() {
            Random r = new Random(20261120);
            for (int i = 0; i < old.length; i++) {
                var s = shot(r, cam);
                s.lifeTicks = 20;
                s.end = Double.NaN;
                s.confirmedEnd = 2;
                s.stopPoint = i % 2 == 0 ? cam.add(1, 2, 3) : null;
                ages[i] = switch (i % 6) { case 0 -> 0; case 1 -> .001; case 2 -> 1; case 3 -> 2; case 4 -> 4; default -> 25; };
                old[i] = s; actual[i] = adapter(s);
            }
        }
        void before() {
            List<float[]> batch = new ArrayList<>();
            for (int i = 0; i < old.length; i++) {
                before.point(old[i].at(ages[i]));
                var segment = OldTracers4.segment(old[i], ages[i], .05);
                if (!segment.expired() && segment.visible())
                    batch.add(OldTracers4.ribbon(old[i], segment.head(), segment.tail(), cam, .001F));
            }
            OldTracers4.submit(before.event, batch);
            before.draw();
        }
        void after() {
            ribbons.beginFrame(++frame);
            var batch = ribbons.begin();
            for (int i = 0; i < actual.length; i++) {
                after.point(actual[i].at(ages[i]));
                var s = actual[i];
                var segment = TracerGeometry.segment(ages[i], s.end, s.lifeTicks, OldTracers4.Config.tracerLength, s.speed, .05);
                if (!segment.expired() && segment.visible()) s.ribbon(batch, segment.head(), segment.tail(), cam, .001F);
            }
            RenderPort26.submitAt(after.event, cam, 0, Intent.QUEUE, batch);
            after.draw();
        }
    }

    @Test void warmedAdapterAllocatesNoMoreBytesPerFrameThanC84fd5f() {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        assertTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        OldTracers4.Config.tracerWidth = .02F;
        OldTracers4.Config.tracerLength = 4;
        Frames f = new Frames();
        for (int i = 0; i < 12000; i++) { f.before(); f.after(); }
        assertEquals(f.before.checksum, f.after.checksum);
        int frames = 4000;
        long thread = Thread.currentThread().threadId();
        long start = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < frames; i++) f.before();
        long before = bean.getThreadAllocatedBytes(thread) - start;
        start = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < frames; i++) f.after();
        long after = bean.getThreadAllocatedBytes(thread) - start;
        assertEquals(f.before.checksum, f.after.checksum);
        System.out.printf("Tracers: shots/frame=%d frames=%d bytes/frame before=%.2f after=%.2f%n",
                f.old.length, frames, (double) before / frames, (double) after / frames);
        assertTrue(after <= before, "allocated bytes: after=" + after + " before=" + before);
    }
}
