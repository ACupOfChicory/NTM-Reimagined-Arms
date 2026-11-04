package acup.gunfeel.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

// port: 26.2's ClickAimTest (e809204). clickAim: vanilla's turn packet must reach the server before NTM's gun key. Both
// go down the same connection in the order they are sent, so the turn is sent just before NTM sends the key: at the
// send in HbmKeybinds.handleProps, which makes the KeybindPacket and sends it.
class ClickAimTest {
    static final String KEYBINDS = "com/hbm/handler/HbmKeybinds";
    static final String KEY_PACKET = "com/hbm/packet/KeybindPacket";
    static final String SEND = "com/hbm/main/NetworkHandler.sendToServer";
    static final String ROT = "net/minecraft/network/play/client/CPacketPlayer$Rotation";
    static final String CONNECTION_SEND = "net/minecraft/client/network/NetHandlerPlayClient.sendPacket";

    static ClassNode read(String name) {
        try (InputStream in = ClickAimTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            if (in == null) return null;
            ClassNode c = new ClassNode();
            new ClassReader(in).accept(c, ClassReader.SKIP_FRAMES);
            return c;
        } catch (IOException e) {
            throw new AssertionError(name, e);
        }
    }

    // what a method makes and calls, in order: "new <class>" and "<owner>.<name>"
    static List<String> calls(MethodNode m) {
        List<String> out = new ArrayList<String>();
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof TypeInsnNode && in.getOpcode() == Opcodes.NEW) out.add("new " + ((TypeInsnNode) in).desc);
            if (in instanceof MethodInsnNode) out.add(((MethodInsnNode) in).owner + "." + ((MethodInsnNode) in).name);
        }
        return out;
    }

    static MethodNode method(ClassNode c, String name) {
        for (MethodNode m : c.methods) if (m.name.equals(name)) return m;
        return null;
    }

    static AnnotationNode annotation(List<AnnotationNode> list, String suffix) {
        for (AnnotationNode a : list == null ? Collections.<AnnotationNode>emptyList() : list) if (a.desc.endsWith(suffix)) return a;
        return null;
    }

    @Test
    void ntmSendsEachKeyFromHandleProps() {
        ClassNode keybinds = read(KEYBINDS);
        assertNotNull(keybinds, "NTM's HbmKeybinds");
        MethodNode m = method(keybinds, "handleProps");
        assertNotNull(m, "HbmKeybinds.handleProps");
        List<String> calls = calls(m);
        int made = calls.indexOf("new " + KEY_PACKET);
        assertTrue(made >= 0, "handleProps makes no KeybindPacket: " + calls);
        assertTrue(calls.subList(made, calls.size()).contains(SEND), "handleProps does not send its KeybindPacket: " + calls);
        assertEquals(1, Collections.frequency(calls, SEND), "handleProps sends more than the key: " + calls);
    }

    @Test
    void theTurnGoesJustBeforeTheSend() {
        ClassNode mixin = read("acup/gunfeel/mixins/MixinClickAim");
        assertNotNull(mixin);
        AnnotationNode target = annotation(mixin.invisibleAnnotations, "/Mixin;");
        assertNotNull(target);
        assertTrue(target.values.toString().contains("L" + KEYBINDS + ";"), "MixinClickAim is not on HbmKeybinds: " + target.values);
        MethodNode handler = method(mixin, "gunfeel$clickAim");
        assertNotNull(handler);
        AnnotationNode inject = annotation(handler.visibleAnnotations, "/Inject;");
        assertNotNull(inject);
        String values = inject.values.toString();
        assertTrue(values.contains("[handleProps]"), "not injected into handleProps: " + values);
        AnnotationNode at = (AnnotationNode) ((List<?>) inject.values.get(inject.values.indexOf("at") + 1)).get(0);
        String where = at.values.toString();
        assertTrue(where.contains("INVOKE") && where.contains("L" + SEND.replace('.', ';')), "not at the key's send: " + where);
        assertEquals(Collections.singletonList("acup/gunfeel/ClickAim.gunKey"), calls(handler));
    }

    @Test
    void clickAimSendsVanillasTurnPacket() {
        ClassNode clickAim = read("acup/gunfeel/ClickAim");
        assertNotNull(clickAim);
        List<String> calls = calls(method(clickAim, "send"));
        int made = calls.indexOf("new " + ROT);
        assertTrue(made >= 0, "no turn packet: " + calls);
        assertTrue(calls.subList(made, calls.size()).contains(CONNECTION_SEND), "the turn packet is not sent at once: " + calls);
    }

    @Test
    void anyTurnIsSentAndNoneTwice() {
        assertTrue(acup.gunfeel.ClickAim.changed(40F, 0F, 0F, 0F));
        assertTrue(acup.gunfeel.ClickAim.changed(0F, -0.01F, 0F, 0F));
        assertTrue(!acup.gunfeel.ClickAim.changed(12.5F, -3F, 12.5F, -3F));
    }
}
