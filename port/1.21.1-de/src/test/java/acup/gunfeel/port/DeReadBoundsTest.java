package acup.gunfeel.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.world.level.BlockGetter;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

// The patch to DE beta.14's BlockReadBounds (MixinBlockReadBounds, notes/ports/de-bug-blockreadbounds.md)
class DeReadBoundsTest {
    // the crash: a section of chunk (0, 2) is built from chunks -1..1 x 1..3; a probe at block (0, 15) is in chunk
    // (0, 0), index (0 - -1) + (0 - 1) * 3 = -2 of the 9 (RenderChunkRegion.getChunk)
    @Test
    void theCrashingReadIsOutside() {
        assertEquals(-2, RenderChunkRegion.index(-1, 1, 0, 0));
        assertFalse(DeReadBounds.inRegion(-1, 1, 0, 15));
        assertTrue(DeReadBounds.inRegion(-1, 1, 0, 16));
    }

    // inside exactly when RenderChunkRegion.index is 0..8 and the column is one of the 3 x 3, negative blocks too
    @Test
    void sameAsTheRegionsIndex() {
        for (int minX = -3; minX <= 2; minX++) for (int minZ = -3; minZ <= 2; minZ++)
            for (int bx = -80; bx <= 80; bx += 7) for (int bz = -80; bz <= 80; bz += 5) {
                int cx = bx >> 4, cz = bz >> 4;
                boolean in = cx - minX >= 0 && cx - minX < RenderChunkRegion.SIZE && cz - minZ >= 0 && cz - minZ < RenderChunkRegion.SIZE;
                assertEquals(in, DeReadBounds.inRegion(minX, minZ, bx, bz), minX + "," + minZ + " " + bx + "," + bz);
                if (in) assertTrue(RenderChunkRegion.index(minX, minZ, cx, cz) >= 0 && RenderChunkRegion.index(minX, minZ, cx, cz) < 9);
            }
        assertTrue(DeReadBounds.inRegion(-1, -1, -16, -1));
        assertFalse(DeReadBounds.inRegion(-1, -1, -17, 0));
        assertFalse(DeReadBounds.inRegion(-1, -1, 32, 0));
    }

    // What DE does (JDK 21): after setAccessible(true), unreflectVarHandle still checks access for the lookup's class
    // and throws, where unreflectGetter on the same field works
    @Test
    void unreflectVarHandleFailsWhereGetterWorks() throws Exception {
        Field f = RenderChunkRegion.class.getDeclaredField("minChunkX");
        assertTrue(Modifier.isPrivate(f.getModifiers()));
        f.setAccessible(true);
        assertThrows(IllegalAccessException.class, () -> MethodHandles.lookup().unreflectVarHandle(f));
        assertEquals(int.class, MethodHandles.lookup().unreflectGetter(f).type().returnType());
    }

    // DE beta.14 itself: both VarHandles null, so canRead lets every read through. When this fails, DE reads the corner
    // and the patch can go
    @Test
    void deBeta14HasNoCorner() throws Exception {
        Class<?> client = Class.forName(DeReadBounds.TARGET, true, getClass().getClassLoader());
        for (String name : new String[] {"MIN_X", "MIN_Z"}) {
            Field f = client.getDeclaredField(name);
            f.setAccessible(true);
            assertNull(f.get(null), name);
        }
        client.getDeclaredMethod("canRead", BlockGetter.class, int.class, int.class, int.class);
    }

    // the log line's check: canRead calls the handler only when the injection went in
    @Test
    void patchedSeesTheHandlerCall() throws Exception {
        ClassNode node = new ClassNode();
        try (var in = getClass().getClassLoader().getResourceAsStream("com/hbm/util/BlockReadBounds$Client.class")) {
            new ClassReader(in.readAllBytes()).accept(node, 0);
        }
        assertFalse(DeReadBounds.patched(node));
        MethodNode canRead = node.methods.stream().filter(m -> m.name.equals("canRead")).findFirst().orElseThrow();
        InsnList call = new InsnList();
        call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, node.name, "handler$zza000$gunfeel$canRead", "()V"));
        canRead.instructions.insert(call);
        assertTrue(DeReadBounds.patched(node));
    }
}
