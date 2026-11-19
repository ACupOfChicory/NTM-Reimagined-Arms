package acup.gunfeel;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import acup.gunfeel.host.HookPoint;
import acup.gunfeel.host.HookPointContract;

// every point core needs the game to call in at is wired on 26.2 (HookPointContract), read from the mod's compiled
// classes: the @Hook marks are in the class files only
class HookPoints26Test extends HookPointContract {
    private static final String HOOK = "Lacup/gunfeel/host/Hook;";
    private static List<ClassNode> classes;

    private static synchronized List<ClassNode> classes() throws IOException, URISyntaxException {
        if (classes != null) return classes;
        Path dir = Path.of(Wiring.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (Stream<Path> s = Files.walk(dir.resolve("acup/gunfeel"))) {
            classes = s.filter(p -> p.toString().endsWith(".class")).sorted().map(p -> {
                try (InputStream in = Files.newInputStream(p)) {
                    ClassNode n = new ClassNode();
                    new ClassReader(in).accept(n, ClassReader.SKIP_FRAMES);
                    return n;
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).toList();
        }
        return classes;
    }

    private static String id(String owner, String name, String desc) {
        return owner + "." + name + desc;
    }

    @Override
    protected Map<String, HookPoint> marked() throws Exception {
        Map<String, HookPoint> out = new TreeMap<>();
        for (ClassNode c : classes()) for (MethodNode m : c.methods) {
            if (m.invisibleAnnotations == null) continue;
            for (AnnotationNode a : m.invisibleAnnotations) {
                if (!a.desc.equals(HOOK)) continue;
                String[] value = (String[]) a.values.get(1);
                out.put(id(c.name, m.name, m.desc), HookPoint.valueOf(value[1]));
            }
        }
        return out;
    }

    @Override
    protected Set<String> called() throws Exception {
        Set<String> out = new HashSet<>();
        for (ClassNode c : classes()) for (MethodNode m : c.methods) {
            String self = id(c.name, m.name, m.desc);
            for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
                if (i instanceof MethodInsnNode mi) {
                    String to = id(mi.owner, mi.name, mi.desc);
                    if (!to.equals(self)) out.add(to);
                } else if (i instanceof InvokeDynamicInsnNode indy) {
                    // a method reference (SmokeRender::afterOpaque) is a handle among the bootstrap's arguments
                    for (Object arg : indy.bsmArgs) if (arg instanceof Handle h) out.add(id(h.getOwner(), h.getName(), h.getDesc()));
                }
            }
        }
        return out;
    }
}
