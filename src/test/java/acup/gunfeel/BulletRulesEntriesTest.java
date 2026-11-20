package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

// The rules' file listener is set up by BulletRules' class initialisation; a call through BulletRules to a static it
// only inherits (BulletRulesBase, BulletRuleText) initialises the declaring class alone (JVMS 5.5), and the file would
// never be looked at again. So every static the mod's classes reach through BulletRules is declared in BulletRules,
// read off their bytecode (calls, method references, non-constant fields); BulletRules is never initialised here.
class BulletRulesEntriesTest {
    private static final String OWNER = "acup/gunfeel/BulletRules";
    // reloadNow only sets the flag that the file is looked at next time (it reads nothing): inherited since before
    // core, it needs no listener
    private static final Set<String> NO_LISTENER = Set.of("reloadNow()V");

    @Test
    void everyEntryDeclared() throws Exception {
        Class<?> rules = Class.forName("acup.gunfeel.BulletRules", false, BulletRulesEntriesTest.class.getClassLoader());
        Set<String> declared = new TreeSet<>(), fields = new TreeSet<>();
        for (var m : rules.getDeclaredMethods()) if (Modifier.isStatic(m.getModifiers())) declared.add(m.getName() + Type.getMethodDescriptor(m));
        for (var f : rules.getDeclaredFields()) fields.add(f.getName());
        Set<String> reached = new TreeSet<>(), missing = new TreeSet<>();
        Path dir = Path.of(rules.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (Stream<Path> s = Files.walk(dir.resolve("acup/gunfeel"))) {
            for (Path p : s.filter(x -> x.toString().endsWith(".class")).toList()) {
                ClassNode c = new ClassNode();
                try (InputStream in = Files.newInputStream(p)) {
                    new ClassReader(in).accept(c, ClassReader.SKIP_FRAMES);
                }
                if (c.name.equals(OWNER)) continue;
                for (MethodNode m : c.methods) for (AbstractInsnNode i : m.instructions) {
                    if (i instanceof MethodInsnNode mi && mi.owner.equals(OWNER) && mi.getOpcode() == Opcodes.INVOKESTATIC) {
                        reached.add(mi.name + mi.desc);
                    } else if (i instanceof InvokeDynamicInsnNode d) {
                        for (Object a : d.bsmArgs)
                            if (a instanceof Handle h && h.getOwner().equals(OWNER) && h.getTag() == Opcodes.H_INVOKESTATIC) reached.add(h.getName() + h.getDesc());
                    } else if (i instanceof FieldInsnNode fi && fi.owner.equals(OWNER) && fi.getOpcode() == Opcodes.GETSTATIC) {
                        if (!fields.contains(fi.name)) missing.add("field " + fi.name + " (in " + c.name + ")");
                    }
                }
            }
        }
        for (String r : reached) if (!declared.contains(r) && !NO_LISTENER.contains(r)) missing.add(r);
        assertEquals(new TreeSet<>(), missing, "reached through BulletRules but only inherited: declare them in BulletRules");
        // the scan sees the callers: breakRule, material, path, decal, hole... and BlockStates' machineOrPartial
        assertTrue(reached.size() >= 15, "reached " + reached);
        assertTrue(reached.contains("reloadNow()V") && reached.contains("whizz()Lacup/gunfeel/BulletRuleText$Whizz;"), "reached " + reached);
    }
}
