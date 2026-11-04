package acup.gunfeel.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

// port/continuum: src/test ClickAimTest on NTM Continuum. Vanilla's turn packet must reach the server before
// Continuum's gun key. Both go down the same connection in the order they are sent, so the turn goes right before
// HbmKeybindsClient.report sends its KeybindPayload, the one send of every gun key.
class ClickAimTest {
    static final String KEYBINDS = "com/hbm/client/HbmKeybindsClient";
    static final String KEY_PAYLOAD = "com/hbm/packet/KeybindPayload";
    static final String SEND = "dev/golem/core/net/GolemNetwork.sendToServer";
    static final String ROT = "net/minecraft/network/protocol/game/ServerboundMovePlayerPacket$Rot";

    // what a method makes and calls, in order: "new <class>" and "<owner>.<name>"
    static List<String> calls(MethodNode m) {
        List<String> out = new ArrayList<>();
        for (AbstractInsnNode in : m.instructions) {
            if (in instanceof TypeInsnNode t && t.getOpcode() == Opcodes.NEW) out.add("new " + t.desc);
            if (in instanceof MethodInsnNode mi) out.add(mi.owner + "." + mi.name);
        }
        return out;
    }

    static MethodNode method(ClassNode c, String name) {
        for (MethodNode m : c.methods) if (m.name.equals(name)) return m;
        return null;
    }

    @Test
    void continuumSendsTheGunKeyFromReport() {
        ClassNode keybinds = HbmMixinPlugin.read(ClickAimTest.class.getClassLoader(), KEYBINDS);
        assertNotNull(keybinds, "Continuum's HbmKeybindsClient");
        MethodNode report = method(keybinds, "report");
        assertNotNull(report, "HbmKeybindsClient.report");
        List<String> calls = calls(report);
        int made = calls.indexOf("new " + KEY_PAYLOAD);
        assertTrue(made >= 0, "report makes no KeybindPayload: " + calls);
        assertEquals(1, calls.stream().filter(SEND::equals).count(), "report sends not once: " + calls);
        assertTrue(calls.indexOf(SEND) > made, "report does not send its KeybindPayload: " + calls);
        // the gun keys (not the jetpack's, reportJetpack) are sent nowhere else
        for (MethodNode m : keybinds.methods)
            if (!m.name.equals("report") && !m.name.equals("reportJetpack") && calls(m).contains("new " + KEY_PAYLOAD))
                throw new AssertionError(m.name + " sends a KeybindPayload past report");
    }

    @Test
    void theTurnGoesRightBeforeTheSend() {
        ClassNode mixin = HbmMixinPlugin.read(ClickAimTest.class.getClassLoader(), "acup/gunfeel/mixin/MixinClickAim");
        assertNotNull(mixin);
        AnnotationNode target = mixin.invisibleAnnotations.stream().filter(a -> a.desc.endsWith("/Mixin;")).findFirst().orElseThrow();
        assertTrue(target.values.toString().contains("L" + KEYBINDS + ";"), "MixinClickAim is not on HbmKeybindsClient: " + target.values);
        MethodNode handler = method(mixin, "gunfeel$clickAim");
        assertNotNull(handler);
        AnnotationNode inject = (handler.visibleAnnotations == null ? List.<AnnotationNode>of() : handler.visibleAnnotations).stream()
                .filter(a -> a.desc.endsWith("/Inject;")).findFirst().orElseThrow();
        String values = inject.values.toString();
        assertTrue(values.contains("[report]"), "not injected into report: " + values);
        AnnotationNode at = (AnnotationNode) ((List<?>) inject.values.get(inject.values.indexOf("at") + 1)).get(0);
        assertTrue(at.values.contains("INVOKE"), "not at a call: " + at.values);
        assertTrue(at.values.toString().contains("Ldev/golem/core/net/GolemNetwork;sendToServer("), "not at the send: " + at.values);
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
