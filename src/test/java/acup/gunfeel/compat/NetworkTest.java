package acup.gunfeel.compat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

// The mod is needed on both sides: its payloads must not be optional, or a client without it could join a server
// with it (and the other way round) and play with half the game missing. And both sides need the very same build:
// the channel's version is the mod's version from git, never a constant raised by hand.
class NetworkTest {
    static final String REGISTRAR = "net/neoforged/neoforge/network/registration/PayloadRegistrar";
    static final String EVENT = "net/neoforged/neoforge/network/event/RegisterPayloadHandlersEvent";

    @Test
    void payloadsAreRequired() throws IOException {
        List<String> optional = new ArrayList<>(), fixed = new ArrayList<>();
        boolean registered = false;
        int registrars = 0;
        try (Stream<Path> s = Files.walk(NtmApiTest.classesDir().resolve("acup/gunfeel"))) {
            for (Path p : s.filter(f -> f.toString().endsWith(".class")).toList()) {
                ClassNode c = new ClassNode();
                new ClassReader(Files.readAllBytes(p)).accept(c, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                for (MethodNode m : c.methods) {
                    for (AbstractInsnNode in : m.instructions) {
                        if (in instanceof MethodInsnNode ev && ev.owner.equals(EVENT) && ev.name.equals("registrar")) {
                            registrars++;
                            AbstractInsnNode arg = ev.getPrevious();
                            while (arg != null && arg.getOpcode() < 0) arg = arg.getPrevious();
                            if (arg instanceof LdcInsnNode) fixed.add(c.name + "." + m.name);
                        }
                        if (!(in instanceof MethodInsnNode mi) || !mi.owner.equals(REGISTRAR)) continue;
                        if (mi.name.equals("optional")) optional.add(c.name + "." + m.name);
                        if (mi.name.startsWith("playTo")) registered = true;
                    }
                }
            }
        }
        assertTrue(registered, "no payload registered");
        assertTrue(optional.isEmpty(), "optional payloads: " + optional);
        assertTrue(registrars == 1, "registrars: " + registrars);
        assertTrue(fixed.isEmpty(), "a fixed channel version, not the mod's build: " + fixed);
    }
}
