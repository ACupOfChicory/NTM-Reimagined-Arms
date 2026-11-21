package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import acup.gunfeel.gl54.Gl54LaserRenderer;

/** The version's two world paths, call sites and allocation boundaries stay in place. */
class Gl54LaserPorts26Test {
    static ClassNode code(String name) throws java.io.IOException {
        try (var in = Gl54LaserPorts26Test.class.getResourceAsStream("/" + name + ".class")) {
            var node = new ClassNode();
            new org.objectweb.asm.ClassReader(Objects.requireNonNull(in)).accept(node, org.objectweb.asm.ClassReader.SKIP_FRAMES);
            return node;
        }
    }
    static MethodNode method(String owner, String name) throws java.io.IOException {
        return code(owner).methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
    }
    static List<String> calls(MethodNode method, String owner) {
        List<String> out = new ArrayList<>();
        for (var i : method.instructions) if (i instanceof MethodInsnNode c && c.owner.equals(owner)) out.add(c.name + c.desc);
        return out;
    }

    @Test void collectionReclaimsTheSharedRendererUsingTheSharedFrameWithoutFrameObjects() throws java.io.IOException {
        String client = "acup/gunfeel/gl54/Gl54Client", laser = "acup/gunfeel/gl54/Gl54Laser";
        List<String> frameListeners = new ArrayList<>();
        for (var i : method(client, "init").instructions) if (i instanceof InvokeDynamicInsnNode d)
            for (Object arg : d.bsmArgs) if (arg instanceof org.objectweb.asm.Handle h
                    && h.getOwner().equals(client)
                    && h.getDesc().equals("(Lnet/neoforged/neoforge/client/event/SubmitCustomGeometryEvent;)V"))
                frameListeners.addAll(calls(method(client, h.getName()), laser));
        assertEquals(List.of("beginFrame()V"), frameListeners);
        assertEquals(List.of("frameNumber()J"), calls(method(laser, "beginFrame"), "acup/gunfeel/RenderPort26"));
        assertEquals(List.of("beginFrame(J)V"), calls(method(laser, "beginFrame"),
                "acup/gunfeel/gl54/Gl54LaserRenderer"));
        assertEquals(List.of("frameNumber()J"), calls(method(laser + "$Drawing", "<clinit>"), "acup/gunfeel/RenderPort26"));
        for (MethodNode m : List.of(method(laser, "beginFrame"),
                method("acup/gunfeel/gl54/Gl54LaserRenderer", "beginFrame"),
                method("acup/gunfeel/gl54/Gl54LaserRenderer$Batch", "release")))
            for (var i : m.instructions) {
                assertNotEquals(Opcodes.NEW, i.getOpcode(), m.name);
                assertNotEquals(Opcodes.ANEWARRAY, i.getOpcode(), m.name);
                assertNotEquals(Opcodes.NEWARRAY, i.getOpcode(), m.name);
                assertFalse(i instanceof InvokeDynamicInsnNode, m.name);
            }
    }

    @Test void bothItemCallSitesAndIndependentWorldListenerRemain() throws java.io.IOException {
        String render = "render(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemStack;)V";
        for (String name : List.of("renderFirstPerson", "submitRestBody"))
            assertEquals(List.of(render), calls(method("acup/gunfeel/gl54/ItemRenderGl54", name), "acup/gunfeel/gl54/Gl54Laser"));
        List<String> listener = new ArrayList<>();
        for (var m : code("acup/gunfeel/gl54/Gl54Client").methods) for (var i : m.instructions)
            if (i instanceof InvokeDynamicInsnNode d) for (Object arg : d.bsmArgs)
                if (arg instanceof org.objectweb.asm.Handle h && h.getOwner().equals("acup/gunfeel/gl54/Gl54LaserWorld"))
                    listener.add(h.getName() + h.getDesc());
        assertEquals(List.of("submit(Lnet/neoforged/neoforge/client/event/SubmitCustomGeometryEvent;)V"), listener);
        assertEquals(List.of("submitAt(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lcom/mojang/blaze3d/vertex/PoseStack;Lacup/gunfeel/host/render/Drawer;)V"),
                calls(method("acup/gunfeel/gl54/Gl54Laser", "render"), "acup/gunfeel/RenderPort26"));
        MethodNode beam = method("acup/gunfeel/gl54/Gl54LaserWorld", "beam");
        assertEquals(List.of("submitAt(Lnet/neoforged/neoforge/client/event/SubmitCustomGeometryEvent;Lnet/minecraft/world/phys/Vec3;JILacup/gunfeel/host/render/Drawer;)V"),
                calls(beam, "acup/gunfeel/RenderPort26"));
        for (var i : beam.instructions) if (i instanceof MethodInsnNode c && c.name.equals("submitAt")) {
            // The Drawer load follows the inlined Intent.QUEUE; AFTER_TERRAIN would be ICONST_5.
            assertEquals(Opcodes.ICONST_2, i.getPrevious().getPrevious().getOpcode());
        }
        assertEquals(List.of("inRange(D)Z"), calls(method("acup/gunfeel/gl54/Gl54LaserWorld", "submit"), "acup/gunfeel/gl54/Gl54LaserGeometry"));
    }

    @Test void geometryAllocatesFourLineVectorsNoArraysAndNoItemObjects() throws java.io.IOException {
        for (String name : List.of("item", "beam", "line")) {
            MethodNode m = method("acup/gunfeel/gl54/Gl54LaserGeometry", name);
            int vectors = 0;
            for (var i : m.instructions) {
                assertNotEquals(Opcodes.ANEWARRAY, i.getOpcode(), name);
                assertNotEquals(Opcodes.NEWARRAY, i.getOpcode(), name);
                if (i.getOpcode() == Opcodes.NEW) {
                    assertEquals("acup/gunfeel/Vec3d", ((TypeInsnNode) i).desc);
                    vectors++;
                }
            }
            assertEquals(name.equals("line") ? 4 : 0, vectors, name);
        }
    }

    @Test void nativeItemBindingsDelegateToNtmWithoutCopiesOrScratchObjects() throws java.io.IOException {
        String nativeItem = "acup/gunfeel/gl54/Gl54Laser$Native";
        String emit = "emit(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lcom/mojang/blaze3d/vertex/PoseStack$Pose;FFFIFFIFFF)V";
        assertNull(Gl54LaserRenderer.ITEM.texture());
        assertEquals(List.of(emit), calls(method(nativeItem, "emit"), "com/hbm/render/util/Vertices"));
        assertEquals(List.of(emit), calls(method("acup/gunfeel/RenderPort26$Writer", "decal"), nativeItem));
        assertEquals(List.of("material()Lnet/minecraft/client/renderer/rendertype/RenderType;"),
                calls(method("acup/gunfeel/RenderPort26", "renderType"), nativeItem));
        List<String> fields = new ArrayList<>();
        for (var i : method(nativeItem, "material").instructions) if (i instanceof FieldInsnNode f)
            fields.add(f.getOpcode() + " " + f.owner + "." + f.name + " " + f.desc);
        assertEquals(List.of(Opcodes.GETSTATIC + " com/hbm/client/render/RenderTextures.WHITE Lnet/minecraft/resources/Identifier;"), fields);
        for (MethodNode m : List.of(method(nativeItem, "emit"), method(nativeItem, "material"),
                method("acup/gunfeel/RenderPort26$Writer", "decal"))) {
            for (var i : m.instructions) {
                assertNotEquals(Opcodes.NEW, i.getOpcode(), m.name);
                assertNotEquals(Opcodes.NEWARRAY, i.getOpcode(), m.name);
                assertNotEquals(Opcodes.ANEWARRAY, i.getOpcode(), m.name);
                if (i instanceof MethodInsnNode c)
                    assertFalse(Set.of("transformPosition", "transformNormal", "copy").contains(c.name), c.name);
            }
        }
    }

    @Test void actualAdapterWarnsOnceAndDoesNotGrowItsSlotPool() throws ReflectiveOperationException {
        var field = Class.forName("acup.gunfeel.gl54.Gl54Laser$Drawing").getDeclaredField("RENDERER");
        field.setAccessible(true); var renderer = (Gl54LaserRenderer) field.get(null);
        var delegate = GunFeel.LOGGER.getClass().getDeclaredField("logger"); delegate.setAccessible(true);
        var logger = (org.apache.logging.log4j.core.Logger) delegate.get(GunFeel.LOGGER);
        var level = logger.getLevel(); var seen = new DebugLogTest.Seen(); seen.start();
        logger.addAppender(seen); logger.setLevel(org.apache.logging.log4j.Level.WARN);
        var held = new Gl54LaserRenderer.Batch[Gl54LaserRenderer.SLOTS];
        try {
            for (int i = 0; i < held.length; i++) { held[i] = renderer.begin(); assertNotNull(held[i]); }
            assertNull(renderer.begin()); assertNull(renderer.begin());
            held[0].item(1, new double[3]); assertThrows(NullPointerException.class, () -> held[0].draw(null));
            held[0] = renderer.begin(); assertNotNull(held[0]); assertNull(renderer.begin());
            RenderFrames.beginFrame(null);
            acup.gunfeel.gl54.Gl54Laser.beginFrame();
            for (int i = 0; i < held.length; i++) { held[i] = renderer.begin(); assertNotNull(held[i]); }
            assertNull(renderer.begin());
            assertEquals(List.of("WARN All 256 GL-54 laser submission slots of this frame are busy; skipping laser submission"), seen.lines);
        } finally {
            for (var b : held) if (b != null) { b.item(1, new double[3]); assertThrows(NullPointerException.class, () -> b.draw(null)); }
            logger.removeAppender(seen); logger.setLevel(level); seen.stop();
        }
    }
}
