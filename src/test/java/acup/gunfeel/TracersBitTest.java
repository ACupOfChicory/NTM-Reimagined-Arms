package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import acup.gunfeel.host.render.Intent;

/** c84fd5f against core and 26.2's real writer and queue: calls, raw bits and submission order. */
class TracersBitTest {
    record Submission(PoseStack.Pose pose, RenderType type, SubmitNodeCollector.CustomGeometryRenderer renderer) {}
    static final class Queue {
        final List<Submission> submissions = new ArrayList<>();
        final SubmitNodeCollector collector = (SubmitNodeCollector) Proxy.newProxyInstance(SubmitNodeCollector.class.getClassLoader(),
                new Class<?>[] {SubmitNodeCollector.class}, (proxy, method, args) -> {
                    if (method.getName().equals("submitCustomGeometry")) {
                        submissions.add(new Submission(((PoseStack) args[0]).last().copy(), (RenderType) args[1],
                                (SubmitNodeCollector.CustomGeometryRenderer) args[2]));
                        return null;
                    }
                    throw new AssertionError("unexpected collector call: " + method);
                });
        SubmitCustomGeometryEvent event(PoseStack pose) {
            return new SubmitCustomGeometryEvent(null, collector, pose, List.of());
        }
        List<String> draw() {
            Rec rec = new Rec();
            for (Submission s : submissions) {
                rec.log.add("submit " + s.type.toString());
                s.renderer.render(s.pose, rec);
            }
            return rec.log;
        }
    }

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
            f("matrix position", x, y, z);
            return VertexConsumer.super.addVertex(m, x, y, z);
        }
        public VertexConsumer addVertex(PoseStack.Pose p, float x, float y, float z) {
            f("pose position", x, y, z); return VertexConsumer.super.addVertex(p, x, y, z);
        }
        public VertexConsumer setColor(int argb) { return i("argb", argb); }
        public VertexConsumer setColor(int r, int g, int b, int a) { return i("rgba", r, g, b, a); }
        public VertexConsumer setUv(float u, float v) { return f("uv", u, v); }
        public VertexConsumer setUv1(int u, int v) { return i("uv1", u, v); }
        public VertexConsumer setUv2(int u, int v) { return i("uv2", u, v); }
        public VertexConsumer setNormal(float x, float y, float z) { return f("normal", x, y, z); }
        public VertexConsumer setLineWidth(float w) { return f("width", w); }
        public VertexConsumer setLight(int light) { i("light", light); return VertexConsumer.super.setLight(light); }
    }

    static Vec3 vector(Random r, double scale) {
        return new Vec3(r.nextGaussian() * scale, r.nextGaussian() * scale, r.nextGaussian() * scale);
    }
    static long bits(double d) { return Double.doubleToRawLongBits(d); }
    static void same(Vec3 a, Vec3d b) {
        assertEquals(bits(a.x), bits(b.x())); assertEquals(bits(a.y), bits(b.y())); assertEquals(bits(a.z), bits(b.z()));
    }
    static Vec3d at(OldTracers4.Shot s, double t) {
        return TracerGeometry.at(Vecs.of(s.start), Vecs.of(s.v), s.g, s.speed, Vecs.of(s.muzzleOffset),
                s.stopPoint == null ? null : Vecs.of(s.stopPoint), s.confirmedEnd, t);
    }
    static OldTracers4.Shot shot(Random r, Vec3 cam) {
        var s = new OldTracers4.Shot();
        s.start = cam.add(vector(r, 200)); s.v = vector(r, 10); s.g = r.nextFloat();
        s.speed = Math.max(s.v.length(), 1e-3); s.muzzleOffset = vector(r, .4);
        s.confirmedEnd = r.nextBoolean() ? Double.NaN : r.nextDouble() * 5;
        s.stopPoint = r.nextBoolean() ? null : s.start.add(vector(r, 30));
        s.end = r.nextBoolean() ? Double.NaN : r.nextDouble() * 5;
        s.lifeTicks = r.nextInt(20); s.light = r.nextInt(); s.dark = r.nextInt();
        return s;
    }

    @Test void twoThousandSeedsThroughDeferredGameQueue() {
        float[] width = {.02F};
        TracerRibbons ribbons = new TracerRibbons(RenderPort26.INSTANCE, new TracerRibbons.Host() {
            public float width() { return width[0]; }
            public long start() { return 0; }
            public void drawn(int count, long started) {}
            public void overflow() { fail("unexpected overflow"); }
        });
        for (int seed = 0; seed < 2000; seed++) {
            Random r = new Random(202611200000L + seed);
            Queue old = new Queue(), actual = new Queue();
            PoseStack pose = new PoseStack();
            pose.mulPose(new Quaternionf().rotationXYZ(r.nextFloat(), r.nextFloat(), r.nextFloat()));
            pose.translate(r.nextFloat(), r.nextFloat(), r.nextFloat());
            width[0] = seed % 7 == 0 ? 0 : r.nextFloat() * .05F;
            OldTracers4.Config.tracerWidth = width[0];
            OldTracers4.Config.tracerLength = r.nextFloat() * 12;
            double previous = seed % 11 == 0 ? Double.NaN : r.nextDouble() * 10;
            double tickNow = r.nextDouble() * 10;
            int fov = r.nextInt(161) + 10, height = r.nextInt(3000) - 1;
            double[] frame = OldTracers4.frame(tickNow, previous, fov, height);
            assertEquals(bits(frame[0]), bits(TracerGeometry.frameTicks(tickNow, previous)), "frame " + seed);
            float pixel = TracerGeometry.pixel(fov, height);
            assertEquals(Float.floatToRawIntBits((float) frame[1]), Float.floatToRawIntBits(pixel));
            Vec3 cam = vector(r, seed % 2 == 0 ? 3e7 : 10);
            // Several submissions before execution: the next player cannot overwrite the previous one.
            for (int submit = 0; submit < 3; submit++) {
                var batch = ribbons.begin();
                List<float[]> oldRibbons = new ArrayList<>();
                for (int j = 0, n = r.nextInt(12); j < n; j++) {
                    var s = shot(r, cam);
                    double age = r.nextDouble() * 20;
                    var was = OldTracers4.segment(s, age, frame[0]);
                    var now = TracerGeometry.segment(age, s.end, s.lifeTicks, OldTracers4.Config.tracerLength, s.speed, frame[0]);
                    assertEquals(bits(was.head()), bits(now.head())); assertEquals(bits(was.tail()), bits(now.tail()));
                    assertEquals(was.expired(), now.expired()); assertEquals(was.visible(), now.visible());
                    same(s.at(age), at(s, age));
                    if (was.expired() || !was.visible()) continue;
                    float[] before = OldTracers4.ribbon(s, was.head(), was.tail(), cam, pixel);
                    float[] after = new float[9];
                    TracerGeometry.ribbon(after, at(s, now.head()), at(s, now.tail()), Vecs.of(cam), s.light, s.dark, pixel);
                    for (int k = 0; k < 9; k++) assertEquals(Float.floatToRawIntBits(before[k]), Float.floatToRawIntBits(after[k]));
                    oldRibbons.add(before);
                    batch.add(at(s, now.head()), at(s, now.tail()), Vecs.of(cam), s.light, s.dark, pixel);
                }
                OldTracers4.submit(old.event(pose), oldRibbons);
                RenderPort26.submitAt(actual.event(pose), cam, 0, Intent.QUEUE, batch);
            }
            assertEquals(old.submissions.size(), actual.submissions.size(), "submissions, seed " + seed);
            for (int i = 0; i < old.submissions.size(); i++) assertSame(old.submissions.get(i).type, actual.submissions.get(i).type);
            assertEquals(old.draw(), actual.draw(), "calls, bits and order, seed " + seed);
        }
    }

    @Test void axesZeroMinimumWidthRawColorsStopAndBothWindings() {
        float[][] cases = {
            {0,0,0, 0,0,0}, {1,0,0, 2,0,0}, {1,1e-8F,0, 2,0,0},
            {1,2,3, 1,2,3 + 1e-5F}, {1,2,3, 4,2,1}, {0,2,0, 0,1,0},
            {-1,-2,-3, -4,1,-8}, {1,2,3, 1,2,3 + 1e-4F}
        };
        for (float[] c : cases) for (float width : new float[] {0, .00001F, .03F}) for (int color : new int[] {0, -1, 0x7FC12345, 0xFFA54321}) {
            float[] k = Arrays.copyOf(c, 9);
            k[6] = Float.intBitsToFloat(color); k[7] = Float.intBitsToFloat(color); k[8] = .001F;
            OldTracers4.Config.tracerWidth = width;
            PoseStack pose = new PoseStack();
            Rec old = new Rec(), now = new Rec();
            OldTracers4.streak(pose.last().pose(), old, k);
            RenderPort26.Writer writer = new RenderPort26.Writer(); writer.pose = pose.last(); writer.vc = now;
            TracerGeometry.streak(writer, k, width);
            assertEquals(old.log, now.log);
        }
        var s = shot(new Random(1), new Vec3(3e7, 64, -3e7));
        OldTracers4.Config.tracerLength = 1;
        s.confirmedEnd = 2; s.stopPoint = new Vec3(1,2,3);
        for (double t : new double[] {0, 2-1e-8, Math.nextDown(2-1e-8), 2, Math.nextUp(2), s.lifeTicks}) same(s.at(t), at(s,t));
        for (double end : new double[] {Double.NaN, 0, 1, 2}) for (double age : new double[] {0, 1e-12, 1, 2, 3}) {
            s.end = end;
            assertEquals(OldTracers4.segment(s, age, .05), TracerGeometry.segment(age,end,s.lifeTicks,1,s.speed,.05));
        }
    }
}
