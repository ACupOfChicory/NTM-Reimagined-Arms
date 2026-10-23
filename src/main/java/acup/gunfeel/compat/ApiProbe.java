package acup.gunfeel.compat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

// What the mod's code calls in NTM, per system: ntmgunfeel-ntm-api.txt, one line per class, method or field,
//   <FEATURE> class <owner>
//   <FEATURE> method <owner> <name> <descriptor>
//   <FEATURE> field <owner> <name> <descriptor>
// made from the compiled classes by NtmApiTest (it fails when the file is out of date). missing() says which of them
// an NTM no longer has; a member may be declared in a superclass, and one with a superclass that cannot be read
// (Minecraft's, say) counts as there.
public final class ApiProbe {
    public static final String FILE = "ntmgunfeel-ntm-api.txt";

    private ApiProbe() {
    }

    public static Map<Feature, List<String>> missing(List<String> lines, MixinProbe.Classes classes) {
        Map<Feature, Set<String>> out = new EnumMap<>(Feature.class);
        for (String line : lines) {
            String[] p = line.trim().split(" ");
            if (p.length < 3 || p[0].startsWith("#")) continue;
            Feature f = Feature.byId(p[0]);
            if (f == null) continue;
            String gone = check(p, classes);
            if (gone != null) out.computeIfAbsent(f, k -> new LinkedHashSet<>()).add(gone);
        }
        Map<Feature, List<String>> list = new EnumMap<>(Feature.class);
        out.forEach((f, s) -> list.put(f, new ArrayList<>(s)));
        return list;
    }

    // null when NTM has it, else a short name of what is gone
    static String check(String[] p, MixinProbe.Classes classes) {
        String owner = p[2];
        String simple = owner.substring(owner.lastIndexOf('/') + 1);
        if (classes.get(owner) == null) return simple;
        if (p[1].equals("class") || p.length < 5) return null;
        boolean isMethod = p[1].equals("method");
        String name = p[3], desc = p[4];
        Set<String> seen = new HashSet<>();
        List<String> todo = new ArrayList<>(List.of(owner));
        boolean unknown = false;
        while (!todo.isEmpty()) {
            String n = todo.remove(0);
            if (n == null || !seen.add(n)) continue;
            ClassNode c = classes.get(n);
            if (c == null) {
                // the JDK's classes hold none of NTM's members (the list leaves out Object's own methods)
                if (!n.startsWith("java/")) unknown = true;
                continue;
            }
            if (isMethod) {
                for (MethodNode m : c.methods) if (m.name.equals(name) && (desc.equals("*") || m.desc.equals(desc))) return null;
            } else {
                for (FieldNode fl : c.fields) if (fl.name.equals(name) && (desc.equals("*") || fl.desc.equals(desc))) return null;
            }
            todo.add(c.superName);
            todo.addAll(c.interfaces);
        }
        return unknown ? null : simple + "." + name + (isMethod ? "()" : "");
    }
}
