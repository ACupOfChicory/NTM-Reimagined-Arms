package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** The whole receiver update must use core's class monitor. */
class BalanceGunsMonitorTest {
    @Test void syncLocksOnlyTheBaseAcrossTheWholeBody() throws Exception {
        ClassNode type = new ClassNode();
        try (var in = BalanceGuns.class.getResourceAsStream("BalanceGuns.class")) {
            new ClassReader(in).accept(type, 0);
        }
        for (MethodNode method : type.methods) assertEquals(0, method.access & Opcodes.ACC_SYNCHRONIZED, method.name);
        for (MethodNode method : type.methods) for (var insn : method.instructions)
            assertNotEquals(Opcodes.MONITORENTER, insn.getOpcode(), method.name);
        MethodNode sync = type.methods.stream().filter(m -> m.name.equals("sync")).findFirst().orElseThrow();
        List<AbstractInsnNode> code = new ArrayList<>();
        for (var insn : sync.instructions) if (insn.getOpcode() >= 0) code.add(insn);
        assertEquals(2, code.size());
        MethodInsnNode delegate = assertInstanceOf(MethodInsnNode.class, code.get(0));
        assertEquals(Opcodes.INVOKESTATIC, delegate.getOpcode());
        assertEquals(Type.getInternalName(BalanceGunsBase.class), delegate.owner);
        assertEquals("sync", delegate.name);
        assertEquals("()V", delegate.desc);
        assertEquals(Opcodes.RETURN, code.get(1).getOpcode());

        ClassNode base = new ClassNode();
        try (var in = BalanceGunsBase.class.getResourceAsStream("BalanceGunsBase.class")) {
            new ClassReader(in).accept(base, 0);
        }
        assertEquals(Type.getInternalName(BalanceGunsBase.class), base.name);
        List<MethodNode> updates = base.methods.stream().filter(m -> m.name.equals("sync")).toList();
        assertEquals(2, updates.size());
        for (MethodNode update : updates) {
            assertEquals(Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED,
                    update.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED), update.desc);
            for (var insn : update.instructions) assertNotEquals(Opcodes.MONITORENTER, insn.getOpcode());
        }
    }
}
