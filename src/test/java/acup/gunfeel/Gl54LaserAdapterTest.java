package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Random;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import acup.gunfeel.gl54.Gl54LaserRenderer;
import acup.gunfeel.gl54.Gl54Reflex;

/** Execute the compiled 26.2 adapter unchanged; only NTM's registry-backed aim input is substituted. */
class Gl54LaserAdapterTest {
    private static final String LASER = "acup.gunfeel.gl54.Gl54Laser";
    private static final String AIM = "com.hbm.items.weapon.sedna.ItemGunBaseNT";

    private static final class Adapter implements AutoCloseable {
        final Method render, beginFrame;
        final Field on, aiming, reflex;
        final Object previousReflex;
        final Gl54LaserRenderer renderer;

        Adapter() throws ReflectiveOperationException {
            // Loading these exact main-output bytes also exercises production mutations. No adapter instructions change.
            ClassLoader loader = new ClassLoader(Gl54LaserAdapterTest.class.getClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    if (!name.equals(LASER) && !name.equals(LASER + "$Drawing") && !name.equals(AIM))
                        return super.loadClass(name, resolve);
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> c = findLoadedClass(name);
                        if (c == null) {
                            byte[] bytes;
                            if (name.equals(AIM)) bytes = aimInput();
                            else try (var in = getResourceAsStream(name.replace('.', '/') + ".class")) {
                                if (in == null) throw new ClassNotFoundException(name);
                                bytes = in.readAllBytes();
                            } catch (IOException e) { throw new ClassNotFoundException(name, e); }
                            c = defineClass(name, bytes, 0, bytes.length);
                        }
                        if (resolve) resolveClass(c);
                        return c;
                    }
                }
            };
            Class<?> laser = loader.loadClass(LASER);
            render = laser.getMethod("render", SubmitNodeCollector.class, PoseStack.class, ItemStack.class);
            beginFrame = laser.getMethod("beginFrame");
            on = laser.getField("ON");
            aiming = loader.loadClass(AIM).getField("aiming");
            Field drawing = loader.loadClass(LASER + "$Drawing").getDeclaredField("RENDERER");
            drawing.setAccessible(true);
            renderer = (Gl54LaserRenderer) drawing.get(null);
            reflex = Gl54Reflex.class.getDeclaredField("loaded");
            reflex.setAccessible(true);
            previousReflex = reflex.get(null);
        }

        private static byte[] aimInput() {
            ClassWriter out = new ClassWriter(0);
            String owner = AIM.replace('.', '/');
            out.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
            out.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "aiming", "Z", null, null).visitEnd();
            var m = out.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getIsAiming",
                    "(Lnet/minecraft/world/item/ItemStack;)Z", null, null);
            m.visitCode();
            m.visitFieldInsn(Opcodes.GETSTATIC, owner, "aiming", "Z");
            m.visitInsn(Opcodes.IRETURN); m.visitMaxs(1, 1); m.visitEnd();
            out.visitEnd();
            return out.toByteArray();
        }

        void inputs(boolean enabled, boolean present, boolean aimed, double[] lens) throws IllegalAccessException {
            on.setBoolean(null, enabled);
            aiming.setBoolean(null, aimed);
            reflex.set(null, new Gl54Reflex(present, null, null, null, null, 0, null, 0, null, lens));
        }

        void collect() throws ReflectiveOperationException { beginFrame.invoke(null); }
        void frame() throws ReflectiveOperationException {
            RenderFrames.beginFrame(null);
            collect();
        }
        void submit(TracersBitTest.Queue queue, PoseStack pose) throws ReflectiveOperationException {
            render.invoke(null, queue.collector, pose, null);
        }
        @Override public void close() throws IllegalAccessException {
            RenderFrames.beginFrame(null);
            renderer.beginFrame(RenderPort26.frameNumber());
            reflex.set(null, previousReflex);
        }
    }

    @Test void twoSubmitsInOneRenderedFrameKeepItemAndWorldCallbacksAlive() throws ReflectiveOperationException {
        // Initialize Drawing after frame start: its first slot must already carry the current frame number.
        RenderFrames.beginFrame(null);
        long frame = RenderPort26.frameNumber();
        try (var adapter = new Adapter()) {
            var oldReflex = OldGl54Laser4.Gl54Reflex.current;
            boolean oldOn = OldGl54Laser4.Gl54Laser.ON;
            try {
                OldGl54Laser4.Gl54Laser.ON = true;
                var expected = new TracersBitTest.Queue[2];
                var actual = new TracersBitTest.Queue[2];
                for (int submit = 0; submit < 2; submit++) {
                    if (submit > 0) adapter.collect();
                    assertEquals(frame, RenderPort26.frameNumber());
                    expected[submit] = new TracersBitTest.Queue(); actual[submit] = new TracersBitTest.Queue();
                    PoseStack pose = new PoseStack();
                    double[] lens = {submit + 1, submit + 2, submit + 3};
                    adapter.inputs(true, true, true, lens);
                    Gl54LaserBitTest.oldItem(expected[submit], pose, lens);
                    adapter.submit(actual[submit], pose);
                    var player = Gl54LaserBitTest.player(new Random(100 + submit));
                    OldGl54Laser4.world(expected[submit].event(pose), player, .5F, player.eye);
                    Gl54LaserBitTest.world(adapter.renderer, actual[submit], pose, player, .5F, player.eye);
                }
                for (int submit = 0; submit < 2; submit++) {
                    assertEquals(2, actual[submit].submissions.size());
                    Gl54LaserBitTest.compare(expected[submit], actual[submit], "same frame submit " + submit);
                }
            } finally {
                OldGl54Laser4.Gl54Reflex.current = oldReflex;
                OldGl54Laser4.Gl54Laser.ON = oldOn;
            }
        }
    }

    @Test void realAdapterMatchesOldCallsAndEntityBytes() throws ReflectiveOperationException {
        var oldReflex = OldGl54Laser4.Gl54Reflex.current;
        boolean oldOn = OldGl54Laser4.Gl54Laser.ON;
        try (var adapter = new Adapter()) {
            OldGl54Laser4.Gl54Laser.ON = true;
            for (int seed = 0; seed < 256; seed++) {
                adapter.frame();
                Random r = new Random(202611206000L + seed);
                var old = new TracersBitTest.Queue(); var actual = new TracersBitTest.Queue();
                var oldBytes = new TracersBitTest.Queue(); var actualBytes = new TracersBitTest.Queue();
                for (int submission = 0; submission < 2; submission++) {
                    PoseStack pose = Gl54LaserBitTest.pose(r);
                    double[] lens = Gl54LaserBitTest.lens(r);
                    adapter.inputs(true, true, true, lens);
                    Gl54LaserBitTest.oldItem(old, pose, lens); adapter.submit(actual, pose);
                    Gl54LaserBitTest.oldItem(oldBytes, pose, lens); adapter.submit(actualBytes, pose);
                    // Inputs and poses of earlier submissions survive later calls until the deferred draw.
                    lens[0] += .125; pose.translate(100, 200, 300);
                }
                assertEquals(oldBytes.submissions.size(), actualBytes.submissions.size());
                for (int i = 0; i < oldBytes.submissions.size(); i++)
                    assertArrayEquals(Gl54LaserBitTest.bytes(oldBytes.submissions.get(i)),
                            Gl54LaserBitTest.bytes(actualBytes.submissions.get(i)), "real adapter ENTITY seed " + seed);
                Gl54LaserBitTest.compare(old, actual, "real adapter seed " + seed);
            }
        } finally {
            OldGl54Laser4.Gl54Reflex.current = oldReflex;
            OldGl54Laser4.Gl54Laser.ON = oldOn;
        }
    }

    @Test void realAdapterKeepsVisibilityGatesAndScaleBoundaries() throws ReflectiveOperationException {
        var oldReflex = OldGl54Laser4.Gl54Reflex.current;
        boolean oldOn = OldGl54Laser4.Gl54Laser.ON;
        try (var adapter = new Adapter()) {
            for (boolean enabled : new boolean[] {false, true})
                for (boolean present : new boolean[] {false, true})
                    for (boolean aimed : new boolean[] {false, true})
                        for (float scale : new float[] {0, Math.nextDown(1e-6F), 1e-6F, Math.nextUp(1e-6F),
                                .125F, -2, Float.NaN}) {
                            adapter.frame();
                            double[] lens = {1, -0.0, 3};
                            adapter.inputs(enabled, present, aimed, lens);
                            OldGl54Laser4.Gl54Laser.ON = enabled;
                            OldGl54Laser4.Gl54Reflex.current = new OldGl54Laser4.Gl54Reflex(present, lens);
                            PoseStack pose = new PoseStack(); pose.scale(scale, scale, scale);
                            var old = new TracersBitTest.Queue(); var actual = new TracersBitTest.Queue();
                            OldGl54Laser4.Gl54Laser.render(old.collector, pose, new OldGl54Laser4.ItemStack(aimed));
                            adapter.submit(actual, pose);
                            assertEquals(enabled && present && aimed && Math.abs(scale) > 1e-6F ? 1 : 0,
                                    actual.submissions.size());
                            Gl54LaserBitTest.compare(old, actual, "real adapter gates/scale " + scale);
                        }
        } finally {
            OldGl54Laser4.Gl54Reflex.current = oldReflex;
            OldGl54Laser4.Gl54Laser.ON = oldOn;
        }
    }

    @Test void acceptedButDiscardedItemAndWorldPartsReturnNextFrame() throws ReflectiveOperationException {
        try (var adapter = new Adapter()) {
            var queue = new TracersBitTest.Queue(); PoseStack pose = new PoseStack();
            adapter.inputs(true, true, true, new double[] {1, 2, 3});
            Field slots = Gl54LaserRenderer.class.getDeclaredField("slots"); slots.setAccessible(true);
            Field lens = Gl54LaserRenderer.Batch.class.getDeclaredField("lens"); lens.setAccessible(true);
            Gl54LaserRenderer.Batch first = null;
            for (int frame = 0; frame < 300; frame++) {
                adapter.frame();
                for (int i = 0; i < Gl54LaserRenderer.SLOTS; i++) {
                    if ((i & 1) == 0) adapter.submit(queue, pose);
                    else {
                        var b = adapter.renderer.begin(); assertNotNull(b, "frame " + frame + " slot " + i);
                        if (i == 1) { if (first == null) first = b; else assertSame(first, b); }
                        Gl54LaserBitTest.line(b.line, Gl54LaserBitTest.player(new Random(i)), .5F);
                        b.world(0, 0, 0);
                        RenderPort26.submitAt(queue.collector, pose, b);
                    }
                }
                assertEquals(Gl54LaserRenderer.SLOTS, queue.submissions.size(), "frame " + frame);
                adapter.collect();
                assertNull(adapter.renderer.begin(), "all slots survive another collection in frame " + frame);
                queue.submissions.clear(); // Accepted by the real port; no callback is run.
                adapter.frame();
                assertNull(first.line.start); assertNull(first.line.dir);
                assertNull(first.line.side); assertNull(first.line.up);
                for (var b : (Gl54LaserRenderer.Batch[]) slots.get(adapter.renderer)) assertNull(lens.get(b));
            }
            adapter.submit(queue, pose);
            assertEquals(1, queue.submissions.size());
            assertFalse(queue.draw().isEmpty());
        }
    }
}
