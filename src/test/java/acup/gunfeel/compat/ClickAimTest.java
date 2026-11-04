package acup.gunfeel.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

// clickAim: vanilla's turn packet must reach the server before NTM's gun key. Both go down the same connection in the
// order they are sent, so the turn has to be sent before NTM sends the key: at the head of Keybinds.gunChanged, which
// sends KeybindPayload, and before anything else NTM does with the key.
class ClickAimTest {
    static final String KEYBINDS = "com/hbm/client/Keybinds";
    static final String KEY_PAYLOAD = "com/hbm/packet/toserver/KeybindPayload";
    static final String ROT = "net/minecraft/network/protocol/game/ServerboundMovePlayerPacket$Rot";

    // what a method makes and calls, in order: "new <class>" and "<owner>.<name>"
    static List<String> calls(MethodNode m) {
        List<String> out = new ArrayList<>();
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof TypeInsnNode t && t.getOpcode() == org.objectweb.asm.Opcodes.NEW) out.add("new " + t.desc);
            if (in instanceof MethodInsnNode mi) out.add(mi.owner + "." + mi.name);
        }
        return out;
    }

    static MethodNode method(ClassNode c, String name) {
        for (MethodNode m : c.methods) if (m.name.equals(name)) return m;
        return null;
    }

    @Test
    void ntmSendsTheGunKeyFromGunChanged() {
        ClassNode keybinds = HbmMixinPlugin.read(ClickAimTest.class.getClassLoader(), KEYBINDS);
        assertNotNull(keybinds, "NTM's Keybinds");
        MethodNode m = method(keybinds, "gunChanged");
        assertNotNull(m, "Keybinds.gunChanged");
        List<String> calls = calls(m);
        int made = calls.indexOf("new " + KEY_PAYLOAD);
        assertTrue(made >= 0, "gunChanged makes no KeybindPayload: " + calls);
        assertTrue(calls.subList(made, calls.size()).stream().anyMatch(c -> c.endsWith(".sendToServer")),
                "gunChanged does not send its KeybindPayload: " + calls);
    }

    @Test
    void theTurnGoesAtTheHeadOfGunChanged() {
        ClassNode mixin = HbmMixinPlugin.read(ClickAimTest.class.getClassLoader(), "acup/gunfeel/mixin/MixinClickAim");
        assertNotNull(mixin);
        AnnotationNode target = mixin.invisibleAnnotations.stream().filter(a -> a.desc.endsWith("/Mixin;")).findFirst().orElseThrow();
        assertTrue(target.values.toString().contains("Lcom/hbm/client/Keybinds;"), "MixinClickAim is not on Keybinds: " + target.values);
        MethodNode handler = method(mixin, "gunfeel$clickAim");
        assertNotNull(handler);
        AnnotationNode inject = (handler.visibleAnnotations == null ? List.<AnnotationNode>of() : handler.visibleAnnotations).stream()
                .filter(a -> a.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        String values = inject.values.toString();
        assertTrue(values.contains("[gunChanged]"), "not injected into gunChanged: " + values);
        AnnotationNode at = (AnnotationNode) ((List<?>) inject.values.get(inject.values.indexOf("at") + 1)).get(0);
        assertEquals(List.of("value", "HEAD"), at.values, "not at the head of gunChanged");
        assertEquals(List.of("acup/gunfeel/ClickAim.gunKey"), calls(handler));
    }

    @Test
    void clickAimSendsVanillasTurnPacket() {
        ClassNode clickAim = HbmMixinPlugin.read(ClickAimTest.class.getClassLoader(), "acup/gunfeel/ClickAim");
        assertNotNull(clickAim);
        List<String> calls = calls(method(clickAim, "send"));
        int made = calls.indexOf("new " + ROT);
        assertTrue(made >= 0, "no turn packet: " + calls);
        assertTrue(calls.subList(made, calls.size()).contains("net/minecraft/client/multiplayer/ClientPacketListener.send"),
                "the turn packet is not sent at once: " + calls);
    }

    @Test
    void anyTurnIsSentAndNoneTwice() {
        assertTrue(acup.gunfeel.ClickAim.changed(40F, 0F, 0F, 0F));
        assertTrue(acup.gunfeel.ClickAim.changed(0F, -0.01F, 0F, 0F));
        assertTrue(!acup.gunfeel.ClickAim.changed(12.5F, -3F, 12.5F, -3F));
    }
}
