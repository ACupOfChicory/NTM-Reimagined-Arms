package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

// ServerKeys.needed: the NTM CE at hand presses its keys on the network thread (its handler calls onPressedServer
// itself), so the press is moved; an NTM that queues it (addScheduledTask, or a lambda handed on) leaves the fix out
class ServerKeysTest {
    private static final String MESSAGE = "(Lcom/hbm/packet/KeybindPacket;Lnet/minecraftforge/fml/common/network/simpleimpl/MessageContext;)Lnet/minecraftforge/fml/common/network/simpleimpl/IMessage;";

    @Test
    void theTestedNtmPressesOnTheNetworkThread() throws Exception {
        ClassNode handler = new ClassNode();
        try (InputStream in = ServerKeysTest.class.getClassLoader().getResourceAsStream("com/hbm/packet/KeybindPacket$Handler.class")) {
            assertNotNull(in, "NTM CE's KeybindPacket$Handler on the test class path");
            new ClassReader(in).accept(handler, 0);
        }
        assertTrue(ServerKeys.needed(handler));
    }

    @Test
    void anNtmThatQueuesThePressLeavesTheFixOut() {
        assertTrue(ServerKeys.needed(handler(false, null)));
        assertFalse(ServerKeys.needed(handler(true, "addScheduledTask")));
        assertFalse(ServerKeys.needed(handler(true, "func_152344_a")));
        assertFalse(ServerKeys.needed(handler(false, "lambda")));
        // no press of its own: nothing to move
        ClassNode none = new ClassNode();
        none.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "onMessage", MESSAGE, null, null));
        none.methods.get(0).instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        assertFalse(ServerKeys.needed(none));
        assertFalse(ServerKeys.needed((ClassNode) null));
    }

    // onMessage calling onPressedServer, with a queueing call before it (or a lambda: an invokedynamic)
    private static ClassNode handler(boolean queue, String how) {
        ClassNode c = new ClassNode();
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "onMessage", MESSAGE, null, null);
        if (queue) m.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "net/minecraft/util/IThreadListener", how,
                "(Ljava/lang/Runnable;)Lcom/google/common/util/concurrent/ListenableFuture;", true));
        if ("lambda".equals(how)) m.instructions.add(new org.objectweb.asm.tree.InvokeDynamicInsnNode("run", "()Ljava/lang/Runnable;",
                new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false)));
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "com/hbm/handler/HbmKeybindsServer", "onPressedServer",
                "(Lnet/minecraft/entity/player/EntityPlayer;Lcom/hbm/handler/HbmKeybinds$EnumKeybind;Z)V", false));
        c.methods.add(m);
        return c;
    }
}
