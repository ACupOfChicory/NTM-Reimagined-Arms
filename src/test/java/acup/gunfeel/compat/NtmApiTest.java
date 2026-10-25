package acup.gunfeel.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

// ntmgunfeel-ntm-api.txt: what the compiled mod calls in NTM, per system (ApiProbe reads it at launch with an untested
// NTM). Made from the classes here: rewrite it with PIN_UPDATE=1 ./gradlew test after a change. Every line must be in
// the tested NTM, or the check would turn a system off for nothing.
class NtmApiTest {
    static final Path FILE = Path.of("src/main/resources", ApiProbe.FILE);
    static final String HBM = "com/hbm/";
    // Object's own methods: NTM's classes always have them, and an interface would not show them
    static final Set<String> OBJECT = Set.of("equals", "hashCode", "toString", "getClass", "clone", "finalize", "notify",
            "notifyAll", "wait");
    // the data dumps run only in a dev run (runDumpBlocks): what they read must not turn off the mod for players
    static final Set<String> DEV_ONLY = Set.of("GunDump", "BlockDump", "BulletCheck");

    @Test
    void listIsUpToDate() throws IOException {
        List<String> now = generate();
        if (System.getenv("PIN_UPDATE") != null) {
            Files.write(FILE, now, StandardCharsets.UTF_8);
            return;
        }
        List<String> was = Files.exists(FILE) ? Files.readAllLines(FILE, StandardCharsets.UTF_8) : List.of();
        if (!was.equals(now)) {
            List<String> gone = new ArrayList<>(was), added = new ArrayList<>(now);
            gone.removeAll(now);
            added.removeAll(was);
            fail(FILE + " is out of date (PIN_UPDATE=1 ./gradlew test)\n  new: " + head(added) + "\n  gone: " + head(gone));
        }
    }

    @Test
    void testedNtmHasAllOfIt() throws IOException {
        Map<Feature, List<String>> missing = ApiProbe.missing(generate(), CompatTest.classes());
        assertTrue(missing.isEmpty(), "the tested NTM lacks " + missing);
    }

    @Test
    void probeFindsWhatIsGone() {
        List<String> lines = List.of(
                "SPREAD method com/hbm/items/weapon/sedna/factory/Lego calcSpread_gone *",
                "TRACERS class com/hbm/NoSuchClass",
                "HUD field com/hbm/items/weapon/sedna/factory/Lego noSuchField *",
                "ARMS method com/hbm/items/weapon/sedna/factory/Lego calcSpread *");
        Map<Feature, List<String>> m = ApiProbe.missing(lines, CompatTest.classes());
        assertEquals(Set.of(Feature.SPREAD, Feature.TRACERS, Feature.HUD), m.keySet());
        assertEquals(List.of("Lego.calcSpread_gone()"), m.get(Feature.SPREAD));
    }

    static String head(List<String> l) {
        return l.size() <= 5 ? l.toString() : l.subList(0, 5) + " (+" + (l.size() - 5) + ")";
    }

    // ---------------------------------------------------------------- the list

    static Path classesDir() {
        try {
            return Path.of(Compat.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    // per system its classes and mixins; a class no system names is CORE's (the mod does not run without it)
    static Map<String, List<Feature>> owners() {
        Map<String, List<Feature>> m = new HashMap<>();
        for (Feature f : Feature.values()) {
            for (String c : f.classes) m.computeIfAbsent(c, k -> new ArrayList<>()).add(f);
            for (String c : f.mixins) m.computeIfAbsent(c, k -> new ArrayList<>()).add(f);
        }
        return m;
    }

    static List<String> generate() throws IOException {
        Path dir = classesDir();
        Map<String, List<Feature>> owners = owners();
        Set<String> out = new TreeSet<>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir.resolve("acup/gunfeel"))) {
            files = s.filter(p -> p.toString().endsWith(".class")).sorted().toList();
        }
        for (Path p : files) {
            ClassNode c = new ClassNode();
            new ClassReader(Files.readAllBytes(p)).accept(c, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            String simple = c.name.substring(c.name.lastIndexOf('/') + 1);
            int inner = simple.indexOf('$');
            if (inner >= 0) simple = simple.substring(0, inner);
            if (DEV_ONLY.contains(simple)) continue;
            List<Feature> fs = owners.getOrDefault(simple, List.of(Feature.CORE));
            Set<String> refs = new TreeSet<>();
            refs(c, refs);
            for (Feature f : fs) for (String r : refs) out.add(f.name() + " " + r);
        }
        return new ArrayList<>(out);
    }

    static void refs(ClassNode c, Set<String> refs) {
        type(Type.getObjectType(c.superName == null ? "java/lang/Object" : c.superName), refs);
        for (String i : c.interfaces) type(Type.getObjectType(i), refs);
        for (FieldNode f : c.fields) type(Type.getType(f.desc), refs);
        for (MethodNode m : c.methods) {
            method(Type.getMethodType(m.desc), refs);
            for (AbstractInsnNode in : m.instructions) {
                if (in instanceof MethodInsnNode mi) member("method", mi.owner, mi.name, mi.desc, refs);
                else if (in instanceof FieldInsnNode fi) member("field", fi.owner, fi.name, fi.desc, refs);
                else if (in instanceof TypeInsnNode ti) type(Type.getObjectType(ti.desc), refs);
                else if (in instanceof MultiANewArrayInsnNode ma) type(Type.getType(ma.desc), refs);
                else if (in instanceof LdcInsnNode ldc && ldc.cst instanceof Type t) constant(t, refs);
                else if (in instanceof InvokeDynamicInsnNode indy) {
                    method(Type.getMethodType(indy.desc), refs);
                    for (Object a : indy.bsmArgs) {
                        if (a instanceof Handle h) {
                            member(h.getTag() <= 4 ? "field" : "method", h.getOwner(), h.getName(), h.getDesc(), refs);
                        } else if (a instanceof Type t) constant(t, refs);
                    }
                }
            }
        }
    }

    static void constant(Type t, Set<String> refs) {
        if (t.getSort() == Type.METHOD) method(t, refs);
        else type(t, refs);
    }

    static void method(Type t, Set<String> refs) {
        type(t.getReturnType(), refs);
        for (Type a : t.getArgumentTypes()) type(a, refs);
    }

    static void type(Type t, Set<String> refs) {
        if (t.getSort() == Type.ARRAY) t = t.getElementType();
        if (t.getSort() == Type.OBJECT && t.getInternalName().startsWith(HBM)) refs.add("class " + t.getInternalName());
    }

    static void member(String kind, String owner, String name, String desc, Set<String> refs) {
        if (owner.startsWith("[")) {
            type(Type.getType(owner), refs);
            return;
        }
        if (!owner.startsWith(HBM)) {
            // a call into Minecraft may still take or give NTM's types
            if (kind.equals("method")) method(Type.getMethodType(desc), refs);
            else type(Type.getType(desc), refs);
            return;
        }
        if (kind.equals("method") && OBJECT.contains(name)) {
            refs.add("class " + owner);
            return;
        }
        refs.add(kind + " " + owner + " " + name + " " + desc);
    }
}
