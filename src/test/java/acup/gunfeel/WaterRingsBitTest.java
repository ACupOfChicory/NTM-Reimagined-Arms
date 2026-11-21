package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.util.*;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import acup.gunfeel.host.render.Intent;

/** The extracted c84fd5f methods against core and the real deferred particle writer of 26.2. */
class WaterRingsBitTest {
    record Surface(double y, double fx, double fz) implements WaterRingGeometry.Surface {}

    static class World implements WaterRingGeometry.Host {
        final List<String> calls = new ArrayList<>();
        final int seed;
        int queries;
        World(int seed) { this.seed = seed; }
        public Surface surface(double x, double y, double z) {
            calls.add("surface " + bits(x) + " " + bits(y) + " " + bits(z));
            int q = queries++;
            if ((seed + q) % 23 == 0) return null;
            double h = y - .4;
            if ((seed + q) % 11 == 0) h += .31;
            return new Surface(h, seed % 3 == 0 ? 0 : .6, seed % 5 == 0 ? 0 : -.8);
        }
        public void connected(int cx, int cz, double y, double[][] allow, int span) {
            calls.add("connected " + cx + " " + cz + " " + bits(y) + " " + span);
            int side = 2 * span + 1;
            Arrays.fill(allow, 0, side * side, null);
            for (int z = -span; z <= span; z++) for (int x = -span; x <= span; x++) {
                if (Math.floorMod(x * 13 + z * 7 + seed, 7) == 0) continue;
                double h = y + x * .007 - z * .013;
                allow[(z + span) * side + x + span] = new double[] {h, h + .02, h + .07, h - .03, Math.floor(y)};
            }
        }
        public int light(int x, int y, int z) {
            calls.add("light " + x + " " + y + " " + z);
            return ((x * 31 + y * 7 + z * 13 + seed) & 15) << 20 | ((x - z + seed) & 15) << 4;
        }
    }

    static long bits(double d) { return Double.doubleToRawLongBits(d); }

    static void state(WaterRingGeometry actual, int count) throws ReflectiveOperationException {
        assertEquals(OldWaterRings4.alive(), actual.alive());
        assertEquals(OldWaterRings4.made, actual.made);
        assertEquals(OldWaterRings4.leftWater, actual.leftWater);
        assertEquals(OldWaterRings4.widest, actual.widest);
        for (int i = 0; i < WaterRingGeometry.MAX; i++) {
            var a = OldWaterRings4.RINGS[i];
            var b = actual.rings[i];
            assertEquals(a.on, b.on, "on " + i);
            if (i >= count) continue;
            for (Field f : a.getClass().getDeclaredFields()) {
                Object expected = f.get(a), value = b.getClass().getField(f.getName()).get(b);
                if (expected instanceof Double d) assertEquals(bits(d), bits((double) value), f.getName());
                else if (expected instanceof Float v)
                    assertEquals(Float.floatToRawIntBits(v), Float.floatToRawIntBits((float) value), f.getName());
                else if (expected instanceof double[][] cells) {
                    var other = (double[][]) value;
                    assertEquals(cells.length, other.length);
                    for (int j = 0; j < cells.length; j++) {
                        if (cells[j] == null) assertNull(other[j]);
                        else {
                            assertNotNull(other[j]);
                            assertEquals(cells[j].length, other[j].length);
                            for (int k = 0; k < cells[j].length; k++) assertEquals(bits(cells[j][k]), bits(other[j][k]));
                        }
                    }
                } else assertEquals(expected, value, f.getName());
            }
        }
    }

    static void reset(WaterRingGeometry geometry) {
        OldWaterRings4.clear();
        geometry.clear();
        for (int i = 0; i < WaterRingGeometry.MAX; i++) {
            OldWaterRings4.RINGS[i] = new OldWaterRings4.Ring();
            geometry.rings[i] = new WaterRingGeometry.Ring();
        }
        OldWaterRings4.made = geometry.made = 0;
        OldWaterRings4.leftWater = geometry.leftWater = 0;
        OldWaterRings4.widest = geometry.widest = 0;
    }

    static void add(WaterRingGeometry geometry, double x, double y, double z, double delay, double life,
            double s0, double s1, float a, boolean adv) {
        OldWaterRings4.add(null, x, y, z, delay, life, s0, s1, a, adv);
        geometry.add(x, y, z, delay, life, s0, s1, a, adv);
    }

    @Test void twoThousandSeedsUpdateAndDeferredVertexCalls() throws ReflectiveOperationException {
        WaterRingGeometry geometry = new WaterRingGeometry();
        WaterRingRenderer renderer = new WaterRingRenderer(RenderPort26.INSTANCE, geometry);
        for (int seed = 0; seed < 2000; seed++) {
            reset(geometry);
            Random random = new Random(202611204000L + seed);
            World oldHost = new World(seed), newHost = new World(seed);
            var level = new OldWaterRings4.Level(oldHost);
            int count = 1 + random.nextInt(4);
            for (int i = 0; i < count; i++) {
                add(geometry, random.nextDouble() * 8 - 4, random.nextDouble() * 2 - 1, random.nextDouble() * 8 - 4,
                        random.nextDouble() * .12, .15 + random.nextDouble(), .1 + random.nextDouble() * .3,
                        .5 + random.nextDouble() * 2, random.nextFloat() * 1.5F, random.nextBoolean());
            }
            state(geometry, count);
            for (int step = 0; step < 4; step++) {
                double dt = step == 0 ? 0 : random.nextDouble() * .1;
                OldWaterRings4.update(level, dt);
                geometry.update(newHost, dt, 1.6);
                state(geometry, count);
                assertEquals(oldHost.calls, newHost.calls, "update host seed " + seed);
            }
            // Two contexts wait together, with different light, cameras, poses and atlas coordinates.
            var before = new TracersBitTest.Queue();
            var after = new TracersBitTest.Queue();
            for (int submit = 0; submit < 2; submit++) {
                World oldDraw = new World(seed * 3 + submit), newDraw = new World(seed * 3 + submit);
                PoseStack pose = new PoseStack();
                pose.mulPose(new Quaternionf().rotationXYZ(random.nextFloat(), random.nextFloat(), random.nextFloat()));
                pose.translate(random.nextFloat(), random.nextFloat(), random.nextFloat());
                Vec3 cam = new Vec3(submit == 0 ? 3e7 : -3e7, random.nextDouble() * 100, random.nextDouble() * 100);
                float u0 = random.nextFloat(), u1 = random.nextFloat(), v0 = random.nextFloat(), v1 = random.nextFloat();
                var sprite = new OldWaterRings4.TextureAtlasSprite(u0, u1, v0, v1);
                OldWaterRings4.submit(before.event(pose), new OldWaterRings4.Level(oldDraw), cam, sprite);
                var batch = renderer.begin();
                batch.bind(newDraw, cam.x, cam.y, cam.z, u0, u1, v0, v1);
                RenderPort26.submitAt(after.event(pose), cam, 0, Intent.AFTER_TERRAIN, batch);
                assertTrue(newDraw.calls.isEmpty(), "light must be read when the queue executes");
                // Keep each host to check light calls after both callbacks execute.
                drawHosts.add(oldDraw); drawHosts.add(newDraw);
            }
            assertEquals(before.draw(), after.draw(), "vertex calls/raw bits/order seed " + seed);
            for (int i = 0; i < drawHosts.size(); i += 2)
                assertEquals(drawHosts.get(i).calls, drawHosts.get(i + 1).calls, "draw host seed " + seed);
            drawHosts.clear();
        }
    }

    private final List<World> drawHosts = new ArrayList<>();

    static void copy(OldWaterRings4.Ring from, WaterRingGeometry.Ring to) throws ReflectiveOperationException {
        for (Field f : from.getClass().getDeclaredFields()) to.getClass().getField(f.getName()).set(to, f.get(from));
    }

    static List<String> compareDraw(WaterRingGeometry geometry, OldWaterRings4.Ring ring, Vec3 cam,
            OldWaterRings4.TextureAtlasSprite sprite) throws ReflectiveOperationException {
        var now = geometry.rings[0];
        copy(ring, now);
        World beforeHost = new World(1), afterHost = new World(1);
        PoseStack pose = new PoseStack();
        pose.mulPose(new Quaternionf().rotationXYZ(.2F, -.7F, 1.2F));
        TracersBitTest.Rec before = new TracersBitTest.Rec(), after = new TracersBitTest.Rec();
        OldWaterRings4.draw(new OldWaterRings4.Level(beforeHost), pose.last(), before, ring, sprite, cam);
        RenderPort26.Writer writer = new RenderPort26.Writer();
        writer.pose = pose.last(); writer.vc = after;
        geometry.draw(afterHost, writer, now, sprite.getU0(), sprite.getU1(), sprite.getV0(), sprite.getV1(), cam.x, cam.y, cam.z);
        assertEquals(before.log, after.log);
        assertEquals(beforeHost.calls, afterHost.calls);
        return after.log;
    }

    @Test void clippingAgeFlowThresholdLiftAndAtlasBoundaries() throws ReflectiveOperationException {
        var geometry = new WaterRingGeometry();
        var ring = new OldWaterRings4.Ring();
        ring.life = 1; ring.s0 = ring.s1 = 1; ring.a = .7F; ring.span = 1;
        ring.x = -.5; ring.z = -.5; ring.cx = ring.cz = -1;
        ring.allow = new double[9][];
        ring.allow[4] = new double[] {-.1, .2, .4, -.2, -1};
        var sprite = new OldWaterRings4.TextureAtlasSprite(.125F, .25F, .625F, .75F);
        for (double age : new double[] {0, Math.nextUp(0.0), .5, Math.nextDown(1.0), 1, Math.nextUp(1.0)})
            for (double fm : new double[] {0, Math.nextDown(.01), .01, Math.nextUp(.01), 1}) {
                ring.t = age; ring.fm = fm; ring.fx = fm * .6; ring.fz = fm * .8;
                var log = compareDraw(geometry, ring, new Vec3(3e7, 80, -3e7), sprite);
                if (age >= 1) assertTrue(log.isEmpty());
            }
        ring.t = .2; ring.fm = 1; ring.fx = ring.fz = Math.sqrt(.5);
        // A rotated square cuts all four corners off a cell: n=8 and 48 emitted vertices.
        ring.s0 = ring.s1 = 1.2;
        var log = compareDraw(geometry, ring, Vec3.ZERO, sprite);
        assertEquals(48, log.stream().filter(s -> s.startsWith("pose position")).count());
        // One corner enters the cell: n=3, then no intersection: n=0.
        ring.x = -1.3; ring.z = -.5; ring.s0 = ring.s1 = .6;
        log = compareDraw(geometry, ring, Vec3.ZERO, sprite);
        assertEquals(8, log.stream().filter(s -> s.startsWith("pose position")).count());
        ring.x = -3;
        assertTrue(compareDraw(geometry, ring, Vec3.ZERO, sprite).isEmpty());
        for (double u : new double[] {0, 1, -.1, .33}) for (double v : new double[] {0, 1, .81})
            assertEquals(bits(OldWaterRings4.WaterSurface.height(ring.allow[4], u, v)),
                    bits(acup.gunfeel.water.WaterSurface.height(ring.allow[4], u, v)));
    }

    @Test void updateExactPointThreeDriftDeathAndFullPool() throws ReflectiveOperationException {
        for (double jump : new double[] {Math.nextDown(.3), .3, Math.nextUp(.3)}) {
            var geometry = new WaterRingGeometry();
            reset(geometry);
            add(geometry, -.01, 0, -.01, .1, 1, .2, 1, 1, true);
            World a = boundaryWorld(jump), b = boundaryWorld(jump);
            OldWaterRings4.update(new OldWaterRings4.Level(a), .1);
            geometry.update(b, .1, 1.6);
            state(geometry, 1);
            assertEquals(a.calls, b.calls);
            assertEquals(jump <= .3, geometry.rings[0].on);
            assertEquals(jump < .3 ? .07 : -.01, geometry.rings[0].x, 1e-15);
        }
        var geometry = new WaterRingGeometry();
        reset(geometry);
        for (int i = 0; i < 161; i++) add(geometry, i, 0, -i, 0, .1, .1, 40, 1, true);
        assertEquals(160, geometry.alive()); assertEquals(160, geometry.made);
        assertEquals(12, geometry.widest);
        state(geometry, 160);
        reset(geometry);
        assertEquals(0, geometry.alive());
    }

    @Test void clipEmptyTriangleOctagonAndPointsExactlyOnEdges() throws ReflectiveOperationException {
        var geometry = new WaterRingGeometry();
        var oldClip = OldWaterRings4.class.getDeclaredMethod("clip", int.class, double.class, int.class, boolean.class);
        var newClip = WaterRingGeometry.class.getDeclaredMethod("clip", int.class, double.class, int.class, boolean.class);
        newClip.setAccessible(true);
        double[][] polygons = {
            {-3, -3, -2, -3, -2, -2, -3, -2},
            {-1, -1, 0, -1, 0, 0, -1, 0},
            {-1.3, -.9242640687119285, -.8757359312880715, -.5, -1.3, -.0757359312880715, -1.7242640687119284, -.5},
            {-.5, -1.348528137423857, .348528137423857, -.5, -.5, .348528137423857, -1.348528137423857, -.5}
        };
        int[] expected = {0, 4, 3, 8};
        for (int example = 0; example < polygons.length; example++) {
            double[] oldX = scratch(OldWaterRings4.class, null, "PX"), oldZ = scratch(OldWaterRings4.class, null, "PZ");
            double[] newX = scratch(WaterRingGeometry.class, geometry, "PX"), newZ = scratch(WaterRingGeometry.class, geometry, "PZ");
            for (int i = 0; i < 4; i++) {
                oldX[i] = newX[i] = polygons[example][i * 2];
                oldZ[i] = newZ[i] = polygons[example][i * 2 + 1];
            }
            int n = 4, m = 4;
            for (int side = 0; side < 4; side++) {
                double edge = side % 2 == 0 ? -1 : 0;
                int sign = side % 2 == 0 ? 1 : -1;
                n = (int) oldClip.invoke(null, n, edge, sign, side < 2);
                m = (int) newClip.invoke(geometry, m, edge, sign, side < 2);
                assertEquals(n, m);
                for (int i = 0; i < n; i++) {
                    assertEquals(bits(oldX[i]), bits(newX[i]));
                    assertEquals(bits(oldZ[i]), bits(newZ[i]));
                }
            }
            assertEquals(expected[example], n);
        }
    }

    static double[] scratch(Class<?> owner, Object instance, String name) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (double[]) field.get(instance);
    }

    static World boundaryWorld(double jump) {
        return new World(1) {
            @Override public Surface surface(double x, double y, double z) {
                calls.add("surface " + bits(x) + " " + bits(y) + " " + bits(z));
                return new Surface(queries++ == 0 ? jump : 0, 1, 0);
            }
        };
    }

    @Test void realMaterialOrderAndFallbackMatchExtractedSubmission() {
        var geometry = new WaterRingGeometry();
        var renderer = new WaterRingRenderer(RenderPort26.INSTANCE, geometry);
        var pose = new PoseStack();
        var oldStorage = new SubmitNodeStorage();
        var newStorage = new SubmitNodeStorage();
        var world = new World(1);
        OldWaterRings4.clear();
        OldWaterRings4.submit(new SubmitCustomGeometryEvent(null, oldStorage, pose, List.of()),
                new OldWaterRings4.Level(world), Vec3.ZERO, new OldWaterRings4.TextureAtlasSprite(0, 1, 0, 1));
        var batch = renderer.begin();
        batch.bind(world, 0, 0, 0, 0, 1, 0, 1);
        RenderPort26.submitAt(new SubmitCustomGeometryEvent(null, newStorage, pose, List.of()), Vec3.ZERO, 0,
                Intent.AFTER_TERRAIN, batch);
        assertEquals(Set.of(0), oldStorage.getSubmitsPerOrder().keySet());
        assertEquals(oldStorage.getSubmitsPerOrder().keySet(), newStorage.getSubmitsPerOrder().keySet());
        assertFalse(oldStorage.order(0).afterTerrain.isEmpty());
        assertFalse(newStorage.order(0).afterTerrain.isEmpty());
        assertTrue(newStorage.order(0).translucentCustomGeometry.isEmpty());
        var oldType = OldWaterRings4.type();
        var newType = RenderPort26.renderType(WaterRingRenderer.MATERIAL);
        assertTrue(oldType.pipeline().getDepthStencilState().writeDepth());
        assertSame(oldType.pipeline(), newType.pipeline());
        assertEquals(oldType.pipeline().getDepthStencilState(), newType.pipeline().getDepthStencilState());
        assertSame(oldType.format(), newType.format());
        assertEquals(oldType.sortOnUpload(), newType.sortOnUpload());
        assertEquals("OutputTarget[ntmgunfeel_particles_target]", oldType.outputTarget().toString());
        assertEquals(oldType.outputTarget().toString(), newType.outputTarget().toString());
        var fallback = new TracersBitTest.Queue();
        var fallbackBatch = renderer.begin();
        fallbackBatch.bind(world, 0, 0, 0, 0, 1, 0, 1);
        RenderPort26.submitAt(fallback.event(pose), Vec3.ZERO, 0, Intent.AFTER_TERRAIN, fallbackBatch);
        assertEquals(1, fallback.submissions.size());
        fallback.draw();
    }

    @Test void overflowLogsOnceThroughGameAdapterEvenAfterSlotsAreReused() throws ReflectiveOperationException {
        var field = Class.forName("acup.gunfeel.water.WaterRings$Drawing").getDeclaredField("RENDERER");
        field.setAccessible(true);
        var renderer = (WaterRingRenderer) field.get(null);
        // SLF4J keeps its delegate across LogManager context changes in other tests.
        var delegate = GunFeel.LOGGER.getClass().getDeclaredField("logger");
        delegate.setAccessible(true);
        var logger = (org.apache.logging.log4j.core.Logger) delegate.get(GunFeel.LOGGER);
        var level = logger.getLevel();
        DebugLogTest.Seen seen = new DebugLogTest.Seen();
        seen.start();
        logger.addAppender(seen);
        logger.setLevel(org.apache.logging.log4j.Level.WARN);
        var held = new WaterRingRenderer.Batch[WaterRingRenderer.SLOTS];
        var queue = new TracersBitTest.Queue();
        var event = queue.event(new PoseStack());
        try {
            for (int i = 0; i < held.length; i++) {
                held[i] = renderer.begin();
                assertNotNull(held[i]);
                held[i].bind(null, i, 0, 0, 0, 1, 0, 1);
                RenderPort26.submitAt(event, Vec3.ZERO, 0, Intent.AFTER_TERRAIN, held[i]);
            }
            assertNull(renderer.begin());
            assertNull(renderer.begin());
            assertEquals(WaterRingRenderer.SLOTS, queue.submissions.size());
            queue.draw();
            queue.submissions.clear();
            for (int i = 0; i < held.length; i++) {
                assertSame(held[i], renderer.begin());
                held[i].bind(null, i, 0, 0, 0, 1, 0, 1);
                RenderPort26.submitAt(event, Vec3.ZERO, 0, Intent.AFTER_TERRAIN, held[i]);
            }
            assertNull(renderer.begin());
            assertEquals(List.of("WARN All 256 water ring submission slots of this frame are busy; skipping water ring submission"), seen.lines);
        } finally {
            queue.draw();
            logger.removeAppender(seen);
            logger.setLevel(level);
            seen.stop();
        }
    }

    @Test void adapterKeepsTheHostBoundaryAndTheOriginalSubmitPlace() throws java.io.IOException {
        var water = bytecode("acup/gunfeel/water/WaterRings");
        var submit = water.methods.stream().filter(m -> m.name.equals("submit")).findFirst().orElseThrow();
        var calls = new ArrayList<String>();
        for (var insn : submit.instructions) {
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                if (Set.of("update", "sprite", "begin", "bind", "submitAt").contains(call.name)) calls.add(call.name);
                if (call.name.equals("submitAt")) {
                    var previous = insn.getPrevious().getPrevious();
                    assertEquals(org.objectweb.asm.Opcodes.ICONST_5, previous.getOpcode(), "AFTER_TERRAIN");
                }
            }
            assertFalse(insn instanceof org.objectweb.asm.tree.TypeInsnNode type && type.getOpcode() == org.objectweb.asm.Opcodes.NEW,
                    "no per-frame adapter objects");
        }
        assertEquals(List.of("update", "begin", "sprite", "bind", "submitAt"), calls);
        var begin = Arrays.stream(submit.instructions.toArray())
                .filter(insn -> insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("begin"))
                .findFirst().orElseThrow();
        var following = new ArrayList<org.objectweb.asm.tree.AbstractInsnNode>();
        for (var insn = begin.getNext(); insn != null && following.size() < 4; insn = insn.getNext())
            if (insn.getOpcode() >= 0) following.add(insn);
        var guard = following.get(2);
        assertEquals(org.objectweb.asm.Opcodes.IFNONNULL, guard.getOpcode());
        assertEquals(org.objectweb.asm.Opcodes.RETURN, following.get(3).getOpcode(), "skip before binding or submitting");
        var host = bytecode("acup/gunfeel/water/WaterRings$SurfaceHost");
        var surface = host.methods.stream().filter(m -> m.name.equals("surface") && !m.desc.endsWith("WaterRingGeometry$Surface;"))
                .findFirst().orElseThrow();
        int count = 0;
        for (var insn : surface.instructions)
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                assertEquals("acup/gunfeel/water/WaterSurface", call.owner);
                assertEquals("at", call.name);
                assertEquals("(Lnet/minecraft/world/level/BlockGetter;DDDZ)Lacup/gunfeel/water/WaterSurface$Info;", call.desc);
                assertEquals(org.objectweb.asm.Opcodes.ICONST_0, insn.getPrevious().getOpcode(), "depth=false");
                count++;
            }
        assertEquals(1, count);
        var drawing = bytecode("acup/gunfeel/water/WaterRings$Drawing");
        var cleanup = drawing.methods.stream().filter(m -> m.name.startsWith("lambda$") && m.desc.equals("(I)V"))
                .findFirst().orElseThrow();
        int clears = 0;
        for (var insn : cleanup.instructions)
            if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field && field.name.equals("level")) {
                assertEquals(org.objectweb.asm.Opcodes.PUTFIELD, field.getOpcode());
                assertEquals(org.objectweb.asm.Opcodes.ACONST_NULL, insn.getPrevious().getOpcode());
                clears++;
            }
        assertEquals(1, clears, "release the slot's world after drawing");
    }

    static org.objectweb.asm.tree.ClassNode bytecode(String name) throws java.io.IOException {
        try (var in = WaterRingsBitTest.class.getResourceAsStream("/" + name + ".class")) {
            var node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(Objects.requireNonNull(in)).accept(node, org.objectweb.asm.ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
