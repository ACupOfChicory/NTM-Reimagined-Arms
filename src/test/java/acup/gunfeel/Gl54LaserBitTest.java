package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import acup.gunfeel.gl54.*;
import acup.gunfeel.host.render.*;

/** c84fd5f's verbatim methods against the real deferred port, including ENTITY's final bytes. */
class Gl54LaserBitTest {
    static final class Rec implements VertexConsumer {
        final List<String> log = new ArrayList<>();
        VertexConsumer f(String name, float... args) {
            StringBuilder s = new StringBuilder(name);
            for (float f : args) s.append(' ').append(Integer.toHexString(Float.floatToRawIntBits(f)));
            log.add(s.toString()); return this;
        }
        VertexConsumer i(String name, int... args) { log.add(name + Arrays.toString(args)); return this; }
        public VertexConsumer addVertex(float x, float y, float z) { return f("position", x, y, z); }
        public VertexConsumer addVertex(Matrix4fc m, float x, float y, float z) {
            f("matrix position", x, y, z); return VertexConsumer.super.addVertex(m, x, y, z);
        }
        public VertexConsumer addVertex(PoseStack.Pose p, float x, float y, float z) {
            f("pose position", x, y, z); return VertexConsumer.super.addVertex(p, x, y, z);
        }
        public void addVertex(float x, float y, float z, int argb, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            f("bulk position", x, y, z); i("bulk color", argb); f("bulk uv", u, v);
            i("bulk overlay/light", overlay, light); f("bulk normal", nx, ny, nz);
            VertexConsumer.super.addVertex(x, y, z, argb, u, v, overlay, light, nx, ny, nz);
        }
        public VertexConsumer setColor(int argb) { return i("argb", argb); }
        public VertexConsumer setColor(int r, int g, int b, int a) { return i("rgba", r, g, b, a); }
        public VertexConsumer setUv(float u, float v) { return f("uv", u, v); }
        public VertexConsumer setUv1(int u, int v) { return i("uv1", u, v); }
        public VertexConsumer setUv2(int u, int v) { return i("uv2", u, v); }
        public VertexConsumer setNormal(float x, float y, float z) { return f("normal", x, y, z); }
        public VertexConsumer setNormal(PoseStack.Pose p, float x, float y, float z) {
            f("pose normal", x, y, z); return VertexConsumer.super.setNormal(p, x, y, z);
        }
        public VertexConsumer setLineWidth(float w) { return f("width", w); }
        public VertexConsumer setOverlay(int overlay) { i("overlay", overlay); return VertexConsumer.super.setOverlay(overlay); }
        public VertexConsumer setLight(int light) { i("light", light); return VertexConsumer.super.setLight(light); }
    }

    static PoseStack pose(Random r) {
        PoseStack pose = new PoseStack();
        pose.translate(r.nextDouble() * 10, r.nextDouble(), r.nextDouble() * 10);
        pose.mulPose(new Quaternionf().rotationXYZ(r.nextFloat() * 6, r.nextFloat() * 6, r.nextFloat() * 6));
        // Uniform, nonuniform and negative scales exercise Pose.transformNormal's branches.
        float s = r.nextFloat() * 3 + .001F;
        if (r.nextBoolean()) pose.scale(s, s, s);
        else pose.scale(-s, r.nextFloat() * 3 + .001F, r.nextFloat() * 3 + .001F);
        return pose;
    }

    static float scale(PoseStack p) { return p.last().pose().transformDirection(new Vector3f(0, 0, 1)).length(); }
    static double[] lens(Random r) { return new double[] {r.nextGaussian() * 10, r.nextGaussian() * 10, r.nextGaussian() * 10}; }
    static OldGl54Laser4.AbstractClientPlayer player(Random r) {
        var p = new OldGl54Laser4.AbstractClientPlayer();
        p.eye = TracersBitTest.vector(r, r.nextBoolean() ? 3e7 : 20);
        p.look = TracersBitTest.vector(r, 1);
        p.yBodyRotO = r.nextFloat() * 720 - 360; p.yBodyRot = r.nextFloat() * 720 - 360;
        return p;
    }

    static void line(Gl54LaserGeometry.Line out, OldGl54Laser4.AbstractClientPlayer p, float partial) {
        Gl54LaserGeometry.line(out, p.eye.x, p.eye.y, p.eye.z, p.look.x, p.look.y, p.look.z,
                net.minecraft.util.Mth.rotLerp(partial, p.yBodyRotO, p.yBodyRot),
                OldGl54Laser4.Gl54LaserWorld.right, OldGl54Laser4.Gl54LaserWorld.down, OldGl54Laser4.Gl54LaserWorld.forward);
        Vec3[] old = OldGl54Laser4.Gl54LaserWorld.line(p, partial);
        TracersBitTest.same(old[0], out.start); TracersBitTest.same(old[1], out.dir);
        TracersBitTest.same(old[2], out.side); TracersBitTest.same(old[3], out.up);
    }

    static void oldItem(TracersBitTest.Queue q, PoseStack pose, double[] lens) {
        OldGl54Laser4.Gl54Reflex.current = new OldGl54Laser4.Gl54Reflex(true, lens);
        OldGl54Laser4.Gl54Laser.render(q.collector, pose, new OldGl54Laser4.ItemStack(true));
    }
    static void item(Gl54LaserRenderer renderer, TracersBitTest.Queue q, PoseStack pose, double[] lens) {
        if (!Gl54LaserGeometry.visibleScale(scale(pose))) return;
        var b = renderer.begin(); assertNotNull(b);
        b.item(scale(pose), lens);
        RenderPort26.submitAt(q.collector, pose, b);
    }
    static void world(Gl54LaserRenderer renderer, TracersBitTest.Queue q, PoseStack pose,
            OldGl54Laser4.AbstractClientPlayer p, float partial, Vec3 cam) {
        var b = renderer.begin(); assertNotNull(b); line(b.line, p, partial);
        b.world(cam.x, cam.y, cam.z);
        RenderPort26.submitAt(q.event(pose), cam, 0, Intent.QUEUE, b);
    }
    static void compare(TracersBitTest.Queue old, TracersBitTest.Queue now, String context) {
        assertEquals(old.submissions.size(), now.submissions.size(), context);
        Rec expected = new Rec(), actual = new Rec();
        for (int i = 0; i < old.submissions.size(); i++) {
            var a = old.submissions.get(i); var b = now.submissions.get(i);
            assertSame(a.type(), b.type(), context + " material/order " + i);
            a.renderer().render(a.pose(), expected); b.renderer().render(b.pose(), actual);
        }
        assertEquals(expected.log, actual.log, context);
    }

    @Test void twoThousandSeedsCallsRawBitsAndDeferredOrder() {
        var renderer = new Gl54LaserRenderer(RenderPort26.INSTANCE, () -> fail("overflow"));
        for (int seed = 0; seed < 2000; seed++) {
            Random r = new Random(202611204000L + seed);
            var old = new TracersBitTest.Queue(); var now = new TracersBitTest.Queue();
            for (int n = 0; n < 2; n++) {
                PoseStack pose = pose(r); double[] lens = lens(r);
                oldItem(old, pose, lens); item(renderer, now, pose, lens);
                var p = player(r); float partial = r.nextFloat();
                Vec3 cam = p.eye.add(TracersBitTest.vector(r, seed % 2 == 0 ? 10 : 3e7));
                OldGl54Laser4.Gl54LaserWorld.right = r.nextGaussian();
                OldGl54Laser4.Gl54LaserWorld.down = r.nextGaussian();
                OldGl54Laser4.Gl54LaserWorld.forward = r.nextGaussian();
                OldGl54Laser4.world(old.event(pose), p, partial, cam);
                world(renderer, now, pose, p, partial, cam);
                pose.translate(100, 200, 300); // Both collectors retain the submitted pose.
            }
            compare(old, now, "seed " + seed);
        }
    }

    static byte[] bytes(TracersBitTest.Submission submit) {
        int size = 128 * DefaultVertexFormat.ENTITY.getVertexSize();
        try (var storage = new ByteBufferBuilder(size + 64)) {
            // Initialise padding too: compare the entire ENTITY buffer, without masking bytes.
            org.lwjgl.system.MemoryUtil.memSet(storage.reserve(size), 0x5A, size);
            // clear() does nothing without results: vertices would follow the filled bytes
            // into grown, uninitialised memory (CI 2026-11-21).
            storage.build().close();
            var builder = new BufferBuilder(storage, PrimitiveTopology.QUADS, DefaultVertexFormat.ENTITY);
            submit.renderer().render(submit.pose(), builder);
            try (MeshData mesh = builder.build()) {
                assertNotNull(mesh); assertEquals(128, mesh.drawState().vertexCount());
                var data = mesh.vertexBuffer(); byte[] result = new byte[data.remaining()]; data.get(result);
                assertEquals(size, result.length, "ENTITY buffer must contain exactly 128 vertices");
                return result;
            }
        }
    }

    @Test void twoThousandSeedsFinalEntityBytes() {
        var renderer = new Gl54LaserRenderer(RenderPort26.INSTANCE, null);
        assertEquals(36, DefaultVertexFormat.ENTITY.getVertexSize());
        for (int seed = 0; seed < 2000; seed++) {
            Random r = new Random(202611205000L + seed); PoseStack pose = pose(r); double[] lens = lens(r);
            var old = new TracersBitTest.Queue(); var now = new TracersBitTest.Queue();
            oldItem(old, pose, lens); item(renderer, now, pose, lens);
            assertArrayEquals(bytes(old.submissions.getFirst()), bytes(now.submissions.getFirst()), "ENTITY seed " + seed);
        }
    }

    @Test void scaleBasisYawPitchReachAndEndAlphaBoundaries() {
        var renderer = new Gl54LaserRenderer(RenderPort26.INSTANCE, null);
        for (float s : new float[] {0, Math.nextDown(1e-6F), 1e-6F, Math.nextUp(1e-6F), 1, -1}) {
            PoseStack pose = new PoseStack(); pose.scale(s, s, s);
            var old = new TracersBitTest.Queue(); var now = new TracersBitTest.Queue();
            double[] lens = {0, -0.0, 3}; oldItem(old, pose, lens); item(renderer, now, pose, lens);
            assertEquals(s == 0 || Math.abs(s) <= 1e-6F ? 0 : 1, now.submissions.size());
            compare(old, now, "scale " + s);
            if (Gl54LaserGeometry.visibleScale(scale(pose))) {
                var oldBytes = new TracersBitTest.Queue(); var newBytes = new TracersBitTest.Queue();
                oldItem(oldBytes, pose, lens); item(renderer, newBytes, pose, lens);
                assertArrayEquals(bytes(oldBytes.submissions.getFirst()), bytes(newBytes.submissions.getFirst()), "ENTITY scale " + s);
            }
        }
        double[] hs = {0, Math.nextDown(1e-6), 1e-6, Math.nextUp(1e-6)};
        for (double h : hs) for (float yaw : new float[] {-180.001F, -180, 179.999F, 180, 180.001F})
                for (int pitch : new int[] {-90, 90}) {
            var p = player(new Random(1));
            p.look = new Vec3(h, (pitch < 0 ? 1 : -1) * Math.nextUp(Math.sqrt(1 - h * h)), 0);
            Vec3 normalized = p.look.normalize();
            assertEquals(Double.doubleToRawLongBits(h), Double.doubleToRawLongBits(Math.hypot(normalized.x, normalized.z)));
            p.yBodyRotO = -yaw; p.yBodyRot = yaw;
            for (float partial : new float[] {0, .5F, 1}) {
                var old = new TracersBitTest.Queue(); var now = new TracersBitTest.Queue(); PoseStack pose = new PoseStack();
                OldGl54Laser4.world(old.event(pose), p, partial, p.eye);
                world(renderer, now, pose, p, partial, p.eye);
                compare(old, now, "basis/yaw/pitch " + h + "/" + yaw + "/" + pitch);
            }
        }
        for (double d : new double[] {Math.nextDown(64.0), 64, Math.nextUp(64.0), Double.NaN})
            assertEquals(!(d * d > 64.0 * 64.0), Gl54LaserGeometry.inRange(d * d));
        for (Vec3 look : new Vec3[] {Vec3.ZERO, new Vec3(-0.0, -0.0, -0.0), new Vec3(1e-12, 0, 0),
                new Vec3((double) 1e-5F, 0, 0), new Vec3(Math.nextDown((double) 1e-5F), 0, 0)}) {
            var p = player(new Random(3)); p.eye = new Vec3(-0.0, -0.0, -0.0); p.look = look;
            line(new Gl54LaserGeometry.Line(), p, .5F);
        }
        for (double f : new double[] {-1, -0.0, 0, Math.nextUp(0.0), .0025, Math.nextDown(.0025), .1, 1, Double.NaN})
            assertEquals(OldGl54Laser4.Gl54Laser.color(f), Gl54LaserGeometry.color(f));
        assertEquals(0, Gl54LaserGeometry.color(0) >>> 24);
        assertEquals(200, Gl54LaserGeometry.color(1) >>> 24);
        Rec rec = new Rec(); var writer = new RenderPort26.Writer(true); writer.pose = new PoseStack().last(); writer.vc = rec;
        Gl54LaserGeometry.item(writer, 1, 0, 0, 0);
        assertEquals(128, rec.log.stream().filter(s -> s.startsWith("bulk position")).count());
        assertTrue(rec.log.contains("bulk color[16719380]"));
    }

    @Test void namedMaterialsKeepQueueDepthAndEntityFormat() {
        var world = RenderPort26.renderType(Gl54LaserRenderer.WORLD);
        var item = RenderPort26.renderType(Gl54LaserRenderer.ITEM);
        assertSame(net.minecraft.client.renderer.rendertype.RenderTypes.debugQuads(), world);
        assertSame(net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(com.hbm.client.render.RenderTextures.WHITE), item);
        assertFalse(world.pipeline().getDepthStencilState().writeDepth());
        assertTrue(item.pipeline().getDepthStencilState().writeDepth());
        assertSame(DefaultVertexFormat.ENTITY, item.format()); assertSame(DefaultVertexFormat.POSITION_COLOR, world.format());
        assertFalse(item.pipeline().isCull()); assertFalse(world.pipeline().isCull());
        assertTrue(world.sortOnUpload()); assertTrue(item.sortOnUpload());
        var storage = new net.minecraft.client.renderer.SubmitNodeStorage();
        var renderer = new Gl54LaserRenderer(RenderPort26.INSTANCE, null); var b = renderer.begin();
        line(b.line, player(new Random(2)), .5F); b.world(0, 0, 0);
        var event = new net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent(null, storage, new PoseStack(), List.of());
        RenderPort26.submitAt(event, Vec3.ZERO, 0, Intent.QUEUE, b);
        assertEquals(Set.of(0), storage.getSubmitsPerOrder().keySet());
        assertTrue(storage.order(0).afterTerrain.isEmpty());
        assertFalse(storage.order(0).translucentCustomGeometry.isEmpty());
    }

    @Test void slotsSkipOnceRetainInputsReuseAndReleaseOnFailure() {
        var port = new RecordingRenderPort(); port.defer = true; int[] warnings = {0};
        var renderer = new Gl54LaserRenderer(port, () -> warnings[0]++);
        for (int i = 0; i < Gl54LaserRenderer.SLOTS; i++) {
            var b = renderer.begin(); assertNotNull(b); b.item(1, new double[] {i, 0, 0}); b.draw(port);
            assertThrows(IllegalStateException.class, () -> b.item(1, new double[3]));
        }
        assertNull(renderer.begin()); assertNull(renderer.begin()); assertEquals(1, warnings[0]);
        port.drain(); assertEquals(Gl54LaserRenderer.SLOTS * 128, port.vertices.size());
        for (int i = 0; i < Gl54LaserRenderer.SLOTS; i++)
            assertEquals(Float.floatToRawIntBits(i - .0075F), port.vertices.get(i * 128).x());
        var b = renderer.begin(); assertNotNull(b); b.item(1, new double[3]);
        assertThrows(NullPointerException.class, () -> b.draw(null));
        assertSame(b, renderer.begin()); b.item(1, new double[] {1, 2, 3}); b.draw(port); port.drain();
        assertEquals(1, warnings[0]);
    }

    @Test void releasedCallbackStillFailsIfItArrivesInALaterFrame() {
        var renderer = new Gl54LaserRenderer(RenderPort26.INSTANCE, null);
        var queue = new TracersBitTest.Queue();
        renderer.beginFrame(1);
        item(renderer, queue, new PoseStack(), new double[] {1, 2, 3});
        renderer.beginFrame(2);
        assertEquals("laser part without submission", assertThrows(IllegalStateException.class, queue::draw).getMessage());
    }

    @Test void bulkWriterExpiresWhenDeferredGeometryThrows() {
        VertexWriter[] escaped = {null}; var port = RenderPort26.INSTANCE;
        int material = port.material(Gl54LaserRenderer.ITEM);
        int part = port.part(w -> {
            escaped[0] = w; w.decal(1, 2, 3, -1, 0, 0, 0xF000F0, 0, 1, 0);
            throw new IllegalArgumentException("test draw failure");
        });
        var q = new TracersBitTest.Queue();
        RenderPort26.submitAt(q.collector, new PoseStack(), sink -> sink.queue(material, part, 1));
        assertThrows(IllegalArgumentException.class, q::draw);
        assertThrows(IllegalStateException.class, () -> escaped[0].decal(0, 0, 0, 0, 0, 0, 0, 0, 1, 0));
    }

    @Test void itemKeepsTheOriginalLensArrayUntilTheDeferredDraw() {
        var renderer = new Gl54LaserRenderer(RenderPort26.INSTANCE, null);
        var old = new TracersBitTest.Queue(); var now = new TracersBitTest.Queue();
        PoseStack pose = new PoseStack(); double[] lens = {1, 2, 3};
        oldItem(old, pose, lens); item(renderer, now, pose, lens);
        lens[0] = -4; lens[1] = 8; lens[2] = -16;
        compare(old, now, "deferred lens reference");
    }
}
