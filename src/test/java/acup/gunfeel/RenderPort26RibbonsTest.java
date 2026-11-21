package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import acup.gunfeel.host.render.*;

class RenderPort26RibbonsTest {
    private static final MaterialSpec PARTICLE = new MaterialSpec("ntmgunfeel_water_ring", "particle", Layouts.PARTICLE,
            Blend.TRANSLUCENT, 0, true, true, 0, 0, true, "minecraft:textures/atlas/particles.png", true, false);

    private static org.objectweb.asm.tree.ClassNode bytecode(String name) throws java.io.IOException {
        try (var in = RenderPort26RibbonsTest.class.getResourceAsStream("/" + name + ".class")) {
            var node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(Objects.requireNonNull(in)).accept(node, org.objectweb.asm.ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    @Test void frameEventsDoNotInitializeRenderers() throws java.io.IOException {
        var frames = bytecode("acup/gunfeel/RenderFrames");
        assertEquals(1, frames.fields.size());
        var field = frames.fields.getFirst();
        assertEquals("frame", field.name);
        assertEquals("J", field.desc);
        assertTrue((field.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0);
        assertFalse(frames.methods.stream().anyMatch(m -> m.name.equals("<clinit>")));
        try (var in = getClass().getResourceAsStream("/acup/gunfeel/RenderFrames.class")) {
            var named = ServerLoadTest.classes(Objects.requireNonNull(in).readAllBytes());
            assertFalse(named.contains("acup/gunfeel/RenderPort26"));
            assertFalse(named.contains("acup/gunfeel/TracerRibbons"));
        }
        var subscriber = RenderFrames.class.getAnnotation(net.neoforged.fml.common.EventBusSubscriber.class);
        assertNotNull(subscriber);
        assertArrayEquals(new net.neoforged.api.distmarker.Dist[]{net.neoforged.api.distmarker.Dist.CLIENT}, subscriber.value());
        assertNull(RenderPort26.class.getAnnotation(net.neoforged.fml.common.EventBusSubscriber.class));

        // Reading the counter uses no instance, factory or method call.
        var read = bytecode("acup/gunfeel/RenderPort26").methods.stream()
                .filter(m -> m.name.equals("frameNumber")).findFirst().orElseThrow();
        var code = Arrays.stream(read.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
        assertEquals(List.of(org.objectweb.asm.Opcodes.GETSTATIC, org.objectweb.asm.Opcodes.LRETURN),
                code.stream().map(i -> i.getOpcode()).toList());
        var counter = (org.objectweb.asm.tree.FieldInsnNode) code.getFirst();
        assertEquals(frames.name, counter.owner);
        assertEquals(field.name, counter.name);
    }

    @Test void frameCleanupSkipsUncreatedRibbonsBeforeEarlyReturns() throws java.io.IOException {
        var tracers = bytecode("acup/gunfeel/Tracers");
        var submit = tracers.methods.stream().filter(m -> m.name.equals("submit")).findFirst().orElseThrow();
        var code = Arrays.stream(submit.instructions.toArray()).filter(i -> i.getOpcode() >= 0).toList();
        // The null branch skips both the frameNumber call and cleanup, without calling the ribbons factory.
        assertEquals(List.of(org.objectweb.asm.Opcodes.GETSTATIC, org.objectweb.asm.Opcodes.IFNULL,
                org.objectweb.asm.Opcodes.GETSTATIC, org.objectweb.asm.Opcodes.INVOKESTATIC,
                org.objectweb.asm.Opcodes.INVOKEVIRTUAL), code.subList(0, 5).stream().map(i -> i.getOpcode()).toList());
        for (int i : new int[]{0, 2}) {
            var held = (org.objectweb.asm.tree.FieldInsnNode) code.get(i);
            assertEquals(tracers.name, held.owner);
            assertEquals("ribbons", held.name);
        }
        var read = (org.objectweb.asm.tree.MethodInsnNode) code.get(3);
        assertEquals("acup/gunfeel/RenderPort26", read.owner);
        assertEquals("frameNumber", read.name);
        var cleanup = (org.objectweb.asm.tree.MethodInsnNode) code.get(4);
        assertEquals("acup/gunfeel/TracerRibbons", cleanup.owner);
        assertEquals("beginFrame", cleanup.name);
        var skip = (org.objectweb.asm.tree.JumpInsnNode) code.get(1);
        assertTrue(submit.instructions.indexOf(skip.label) > submit.instructions.indexOf(cleanup));
        assertTrue(submit.instructions.indexOf(skip.label) < submit.instructions.indexOf(code.get(5)));
    }

    @Test void submissionsKeepTheirPlaceAmongOtherEffects() throws java.io.IOException {
        var tracers = bytecode("acup/gunfeel/Tracers");
        var submit = tracers.methods.stream().filter(m -> m.name.equals("submit")).findFirst().orElseThrow();
        List<String> places = new ArrayList<>();
        for (var instruction : submit.instructions) if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
            if (call.owner.equals("acup/gunfeel/RenderPort26") && call.name.equals("submitAt")) places.add("ribbons");
            if (call.owner.equals(tracers.name) && Set.of("makePending", "holes").contains(call.name)) places.add(call.name);
        }
        assertEquals(List.of("ribbons", "makePending", "holes"), places);
        List<String> lifecycle = new ArrayList<>();
        for (var instruction : submit.instructions) if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
            if (call.owner.equals("acup/gunfeel/TracerRibbons") && Set.of("beginFrame", "begin").contains(call.name))
                lifecycle.add(call.name);
        }
        assertEquals(List.of("beginFrame", "begin"), lifecycle);
        List<List<String>> eventOrders = new ArrayList<>();
        for (var method : bytecode("acup/gunfeel/GunFeel").methods) {
            List<String> order = new ArrayList<>();
            for (var instruction : method.instructions) if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                    && call.name.equals("submit") && Set.of("acup/gunfeel/Tracers", "acup/gunfeel/gl54/frag/FragFx",
                            "acup/gunfeel/water/WaterRings", "acup/gunfeel/water/SunkBullets").contains(call.owner)) order.add(call.owner);
            if (!order.isEmpty()) eventOrders.add(order);
        }
        assertEquals(List.of(List.of("acup/gunfeel/Tracers", "acup/gunfeel/gl54/frag/FragFx",
                "acup/gunfeel/water/WaterRings", "acup/gunfeel/water/SunkBullets")), eventOrders);
    }

    @Test void nativeRenderTypesAndExactLayouts() {
        RenderType color = RenderPort26.renderType(TracerRibbons.MATERIAL);
        assertSame(RenderTypes.debugQuads(), color);
        assertSame(DefaultVertexFormat.POSITION_COLOR, color.format());
        assertTrue(color.sortOnUpload());
        assertFalse(color.pipeline().getDepthStencilState().writeDepth());
        assertFalse(color.pipeline().isCull());
        RenderType particle = RenderPort26.renderType(PARTICLE);
        assertSame(DefaultVertexFormat.PARTICLE, particle.format());
        assertTrue(particle.pipeline().getDepthStencilState().writeDepth());
        assertTrue(particle.pipeline().isCull());
        assertTrue(particle.sortOnUpload());
        assertEquals("OutputTarget[ntmgunfeel_particles_target]", particle.outputTarget().toString());
        MaterialSpec entity = new MaterialSpec("entity_translucent", "entity", Layouts.DECAL, Blend.TRANSLUCENT,
                0.1F, true, true, 0, 0, true, "minecraft:test", true, true);
        assertSame(RenderTypes.entityTranslucent(net.minecraft.resources.Identifier.parse("minecraft:test")), RenderPort26.renderType(entity));
    }

    @Test void afterTerrainOrderZeroAndForeignCollectorFallback() {
        RenderPort26 port = RenderPort26.INSTANCE;
        int material = port.material(PARTICLE);
        int[] writes = {0};
        int part = port.part(w -> { writes[0]++; w.particle(1, 2, 3, 0x80ABCDEF, .25F, .75F, 0xF000F0); });
        SubmitNodeStorage storage = new SubmitNodeStorage();
        PoseStack pose = new PoseStack();
        var event = new SubmitCustomGeometryEvent(null, storage, pose, List.of());
        RenderPort26.submitAt(event, Vec3.ZERO, 0, Intent.AFTER_TERRAIN, s -> s.queue(material, part, 4));
        assertEquals(0, writes[0]);
        assertEquals(Set.of(0), storage.getSubmitsPerOrder().keySet());
        assertFalse(storage.order(0).afterTerrain.isEmpty());
        assertTrue(storage.order(0).translucentCustomGeometry.isEmpty());
        // sortInto loads SharedConstants/FML; read the held Submits without loading the client.
        List<CustomFeatureRenderer.Submit> taken = held(storage.order(0).afterTerrain);
        assertEquals(1, taken.size());
        var submit = taken.getFirst();
        TracersBitTest.Rec actual = new TracersBitTest.Rec(), expected = new TracersBitTest.Rec();
        submit.customGeometryRenderer().render(submit.pose(), actual);
        expected.addVertex(submit.pose(), 1, 2, 3).setColor(0x80ABCDEF).setUv(.25F, .75F).setLight(0xF000F0);
        assertEquals(expected.log, actual.log);
        var foreign = new TracersBitTest.Queue();
        RenderPort26.submitAt(foreign.event(pose), Vec3.ZERO, 0, Intent.AFTER_TERRAIN, s -> s.queue(material, part, 4));
        assertEquals(1, foreign.submissions.size());
        assertEquals(1, writes[0]);
        foreign.draw();
        assertEquals(2, writes[0]);
    }

    private static List<CustomFeatureRenderer.Submit> held(Object phase) {
        try {
            var byFeature = phase.getClass().getDeclaredField("submitsByFeature");
            byFeature.setAccessible(true);
            List<CustomFeatureRenderer.Submit> out = new ArrayList<>();
            for (Object feature : (Object[]) byFeature.get(phase)) {
                if (feature == null) continue;
                var batches = feature.getClass().getDeclaredField("batches"); batches.setAccessible(true);
                for (Object group : ((Map<?, ?>) batches.get(feature)).values())
                    for (Object submit : (List<?>) group) out.add((CustomFeatureRenderer.Submit) submit);
                var unbatched = feature.getClass().getDeclaredField("unbatched"); unbatched.setAccessible(true);
                for (Object submit : (List<?>) unbatched.get(feature)) out.add((CustomFeatureRenderer.Submit) submit);
            }
            return out;
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    @Test void queuedWriterExpiresEvenIfPartThrows() {
        RenderPort26 port = RenderPort26.INSTANCE;
        VertexWriter[] escaped = new VertexWriter[1];
        int material = port.material(TracerRibbons.MATERIAL);
        int part = port.part(w -> { escaped[0] = w; throw new IllegalArgumentException("probe"); });
        var foreign = new TracersBitTest.Queue();
        RenderPort26.submitAt(foreign.event(new PoseStack()), Vec3.ZERO, 0, Intent.QUEUE, s -> s.queue(material, part, 1));
        assertThrows(IllegalArgumentException.class, foreign::draw);
        assertThrows(IllegalStateException.class, () -> escaped[0].color(0, 0, 0, 0));
        assertThrows(IllegalStateException.class, () -> escaped[0].particle(0, 0, 0, 0, 0, 0, 0));
    }

    @Test void overflowLogsOnceThroughGameHostEvenAfterSlotsAreReused() throws ReflectiveOperationException {
        var factory = Tracers.class.getDeclaredMethod("ribbons");
        factory.setAccessible(true);
        TracerRibbons ribbons = (TracerRibbons) factory.invoke(null);
        // SLF4J keeps its delegate across LogManager context changes in other tests.
        var delegate = GunFeel.LOGGER.getClass().getDeclaredField("logger");
        delegate.setAccessible(true);
        var logger = (org.apache.logging.log4j.core.Logger) delegate.get(GunFeel.LOGGER);
        var level = logger.getLevel();
        DebugLogTest.Seen seen = new DebugLogTest.Seen();
        seen.start();
        logger.addAppender(seen);
        logger.setLevel(org.apache.logging.log4j.Level.WARN);
        TracerRibbons.Batch[] held = new TracerRibbons.Batch[TracerRibbons.SLOTS];
        try {
            for (int i = 0; i < held.length; i++) {
                held[i] = ribbons.begin();
                assertNotNull(held[i]);
            }
            assertNull(ribbons.begin());
            assertNull(ribbons.begin());
            held[0].draw(null); // Empty batches release without touching the sink.
            held[0] = ribbons.begin();
            assertNotNull(held[0]);
            assertNull(ribbons.begin());
            assertEquals(List.of("WARN All 256 tracer Part slots are busy; skipping tracer submission"), seen.lines);
        } finally {
            for (var batch : held) if (batch != null) batch.draw(null);
            logger.removeAppender(seen);
            logger.setLevel(level);
            seen.stop();
        }
    }
}
