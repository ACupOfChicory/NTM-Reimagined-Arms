package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** The remaining version-side synchronization must share core's monitor. */
class BalanceGunsMonitorTest {
    @Test void syncLocksOnlyTheBaseAcrossTheWholeBody() throws Exception {
        ClassNode type = new ClassNode();
        try (var in = BalanceGuns.class.getResourceAsStream("BalanceGuns.class")) {
            new ClassReader(in).accept(type, 0);
        }
        for (MethodNode method : type.methods) assertEquals(0, method.access & Opcodes.ACC_SYNCHRONIZED, method.name);
        MethodNode sync = type.methods.stream().filter(m -> m.name.equals("sync")).findFirst().orElseThrow();
        List<AbstractInsnNode> code = new ArrayList<>();
        for (var insn : sync.instructions) if (insn.getOpcode() >= 0) code.add(insn);
        LdcInsnNode lock = assertInstanceOf(LdcInsnNode.class, code.get(0));
        assertEquals(Type.getType(BalanceGunsBase.class), lock.cst);
        assertEquals(Opcodes.MONITORENTER, code.get(3).getOpcode());
        assertEquals(1, code.stream().filter(i -> i.getOpcode() == Opcodes.MONITORENTER).count());
        assertTrue(sync.tryCatchBlocks.stream().anyMatch(t -> t.type == null));
        assertTrue(code.stream().filter(i -> i.getOpcode() == Opcodes.MONITOREXIT).count() >= 2);
        // No game read or write precedes acquiring the common monitor.
        assertTrue(code.subList(0, 3).stream().noneMatch(i -> i instanceof MethodInsnNode || i instanceof FieldInsnNode));
    }
}
