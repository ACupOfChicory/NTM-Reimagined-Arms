package acup.gunfeel.compat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

// Does a mixin fit the classes it changes, read from their bytecode without applying it: the target class exists,
// @Shadow, @Accessor and @Invoker members are there with the same types, every injector's method selector matches a
// method of the target, and its @At INVOKE / FIELD / NEW target occurs in that method (as often as the ordinal needs).
// That is what Mixin would otherwise find out by crashing. Used by the build (tools/MixinCheck, which also checks the
// handlers' own signatures) and in game by HbmMixinPlugin when NTM is not a tested version.
public final class MixinProbe {
    public static final String MIXIN = "Lorg/spongepowered/asm/mixin/";
    public static final List<String> INJECTORS = List.of("Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyVariable",
            "ModifyConstant");

    // a class by internal name, null when there is none
    public interface Classes {
        ClassNode get(String internalName);
    }

    // soft: a miss Mixin lets pass (require = 0, or a @Pseudo target that is not there)
    public record Problem(String where, String text, boolean soft) {
        @Override
        public String toString() {
            return where + ": " + text;
        }
    }

    private final Classes classes;

    public MixinProbe(Classes classes) {
        this.classes = classes;
    }

    public static List<String> targets(ClassNode mixin) {
        List<String> out = new ArrayList<>();
        AnnotationNode ann = annotation(mixin.invisibleAnnotations, mixin.visibleAnnotations, MIXIN + "Mixin;");
        if (ann == null) return out;
        for (Object t : list(value(ann, "value"))) out.add(((Type) t).getInternalName());
        for (Object t : list(value(ann, "targets"))) out.add(((String) t).replace('.', '/'));
        return out;
    }

    public static boolean pseudo(ClassNode mixin) {
        return annotation(mixin.invisibleAnnotations, mixin.visibleAnnotations, MIXIN + "Pseudo;") != null;
    }

    public List<Problem> check(ClassNode mixin, int defaultRequire) {
        List<Problem> out = new ArrayList<>();
        String where = simple(mixin.name);
        boolean pseudo = pseudo(mixin);
        List<String> targets = targets(mixin);
        if (targets.isEmpty()) out.add(new Problem(where, "@Mixin without a target", false));
        if (targets.size() > 1) {
            for (FieldNode f : mixin.fields) checkMultiTargetShadow(out, where + "." + f.name,
                    annotation(f.invisibleAnnotations, f.visibleAnnotations, MIXIN + "Shadow;"));
            for (MethodNode m : mixin.methods) checkMultiTargetShadow(out, where + "." + m.name, methodAnn(m, "Shadow;"));
        }
        for (String target : targets) {
            ClassNode tc = classes.get(target);
            if (tc == null) {
                out.add(new Problem(where, (pseudo ? "@Pseudo target " : "target class ") + target + " not found", pseudo));
                continue;
            }
            for (FieldNode f : mixin.fields) checkShadowField(out, where, f, tc);
            for (MethodNode m : mixin.methods) checkMethod(out, where, m, tc, pseudo, defaultRequire);
        }
        return out;
    }

    private static void checkMultiTargetShadow(List<Problem> out, String where, AnnotationNode shadow) {
        if (shadow != null && !Boolean.FALSE.equals(value(shadow, "remap", true)))
            out.add(new Problem(where, "remappable @Shadow in a multi-target mixin: set remap=false on @Shadow", false));
    }

    private void checkShadowField(List<Problem> out, String where, FieldNode f, ClassNode target) {
        AnnotationNode shadow = annotation(f.invisibleAnnotations, f.visibleAnnotations, MIXIN + "Shadow;");
        if (shadow == null) return;
        String prefix = (String) value(shadow, "prefix", "shadow$");
        String name = f.name.startsWith(prefix) ? f.name.substring(prefix.length()) : f.name;
        FieldNode tf = field(target, name, null);
        if (tf == null) {
            out.add(new Problem(where, "@Shadow field " + name + " not in " + simple(target.name) + superHint(target, name, true), false));
        } else if (!tf.desc.equals(f.desc)) {
            out.add(new Problem(where, "@Shadow field " + name + " is " + f.desc + " here but " + tf.desc + " in "
                    + simple(target.name), false));
        }
    }

    private void checkMethod(List<Problem> out, String where, MethodNode m, ClassNode target, boolean pseudo, int defaultRequire) {
        AnnotationNode a;
        if ((a = methodAnn(m, "Shadow;")) != null) {
            String prefix = (String) value(a, "prefix", "shadow$");
            String name = m.name.startsWith(prefix) ? m.name.substring(prefix.length()) : m.name;
            if (method(target, name, m.desc) == null) {
                out.add(new Problem(where, "@Shadow method " + name + m.desc + " not in " + simple(target.name)
                        + superHint(target, name, false), false));
            }
            return;
        }
        if ((a = methodAnn(m, "gen/Accessor;")) != null) {
            checkAccessor(out, where, m, a, target);
            return;
        }
        if ((a = methodAnn(m, "gen/Invoker;")) != null) {
            String name = (String) value(a, "value", null);
            if (name == null) name = stripPrefix(m.name, "call", "invoke");
            if (method(target, name, m.desc) == null) {
                out.add(new Problem(where, "@Invoker " + name + m.desc + " not in " + simple(target.name), false));
            }
            return;
        }
        for (String kind : INJECTORS) {
            a = methodAnn(m, "injection/" + kind + ";");
            if (a != null) {
                checkInjector(out, where + "." + m.name, kind, a, target, pseudo, defaultRequire);
                return;
            }
        }
    }

    private void checkAccessor(List<Problem> out, String where, MethodNode m, AnnotationNode a, ClassNode target) {
        String name = (String) value(a, "value", null);
        Type[] args = Type.getArgumentTypes(m.desc);
        Type ret = Type.getReturnType(m.desc);
        boolean setter = ret.getSort() == Type.VOID;
        if (name == null || name.isEmpty()) {
            String base = m.name.contains("$") ? m.name.substring(m.name.lastIndexOf('$') + 1) : m.name;
            name = stripPrefix(base, "get", "set", "is");
        }
        FieldNode f = field(target, name, null);
        if (f == null) {
            out.add(new Problem(where, "@Accessor field " + name + " not in " + simple(target.name), false));
            return;
        }
        String type = setter ? (args.length == 1 ? args[0].getDescriptor() : "?") : ret.getDescriptor();
        if (!type.equals(f.desc)) {
            out.add(new Problem(where, "@Accessor " + m.name + " uses " + type + " but " + name + " is " + f.desc, false));
        }
    }

    // the methods an injector goes into, after checking its selectors and @At targets
    private void checkInjector(List<Problem> out, String where, String kind, AnnotationNode a, ClassNode target,
            boolean pseudo, int defaultRequire) {
        int require = (Integer) value(a, "require", -1);
        if (require < 0) require = defaultRequire;
        boolean soft = require == 0 || pseudo;
        List<Object> selectors = list(value(a, "method"));
        if (selectors.isEmpty()) {
            out.add(new Problem(where, "@" + kind + " without a method selector", soft));
            return;
        }
        List<MethodNode> methods = new ArrayList<>();
        for (Object sel : selectors) {
            String s = (String) sel;
            if (s.contains("*") || s.startsWith("/")) continue; // wildcard or regex: not checked
            List<MethodNode> found = select(target, s);
            if (found.isEmpty()) out.add(new Problem(where, "no method \"" + s + "\" in " + simple(target.name) + overloads(target, s), soft));
            methods.addAll(found);
        }
        for (MethodNode tm : methods) {
            String into = simple(target.name) + "." + tm.name + tm.desc;
            for (AnnotationNode at : ats(a)) checkAt(out, where, at, tm, into, soft);
        }
    }

    public static List<AnnotationNode> ats(AnnotationNode injector) {
        List<AnnotationNode> out = new ArrayList<>();
        Object at = value(injector, "at");
        if (at instanceof AnnotationNode an) out.add(an);
        else for (Object o : list(at)) out.add((AnnotationNode) o);
        return out;
    }

    private void checkAt(List<Problem> out, String where, AnnotationNode at, MethodNode tm, String into, boolean soft) {
        String point = (String) value(at, "value", "");
        String target = (String) value(at, "target", "");
        int ordinal = (Integer) value(at, "ordinal", -1);
        if (target.isEmpty()) return; // HEAD, RETURN, TAIL, CONSTANT...
        Member t = Member.parse(target);
        if (t == null) return; // not understood: not checked
        if (t.owner() != null && classes.get(t.owner()) == null) {
            out.add(new Problem(where, "@At target class " + t.owner() + " not found", soft));
            return;
        }
        int count = 0;
        for (AbstractInsnNode i : tm.instructions) {
            switch (point) {
                case "INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING" -> {
                    if (i instanceof MethodInsnNode mi && t.matches(mi.owner, mi.name, mi.desc)) count++;
                }
                case "FIELD" -> {
                    if (i instanceof FieldInsnNode fi && t.matches(fi.owner, fi.name, fi.desc)) count++;
                }
                case "NEW" -> {
                    if (i instanceof TypeInsnNode ti && i.getOpcode() == Opcodes.NEW
                            && (ti.desc.equals(t.name()) || ti.desc.equals(t.owner()))) count++;
                }
                default -> {
                    return;
                }
            }
        }
        if (count == 0) out.add(new Problem(where, into + " has no " + point + " " + target, soft));
        else if (ordinal >= count) out.add(new Problem(where, "ordinal " + ordinal + " but " + into + " has only " + count + " "
                + point + " " + target, soft));
    }

    // ---------------------------------------------------------------- lookups

    // methods of the target class itself matching a selector: name, name(args), name(args)ret, Lowner;name(desc)
    public static List<MethodNode> select(ClassNode target, String selector) {
        Member s = Member.parse(selector);
        List<MethodNode> out = new ArrayList<>();
        if (s == null) return out;
        if (s.owner() != null && !s.owner().equals(target.name)) return out;
        for (MethodNode m : target.methods) if (s.matches(target.name, m.name, m.desc)) out.add(m);
        return out;
    }

    public static FieldNode field(ClassNode c, String name, String desc) {
        for (FieldNode f : c.fields) if (f.name.equals(name) && (desc == null || f.desc.equals(desc))) return f;
        return null;
    }

    public static MethodNode method(ClassNode c, String name, String desc) {
        for (MethodNode m : c.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
        return null;
    }

    // the class and all its superclasses and interfaces that can be found
    public List<ClassNode> hierarchy(String internal) {
        List<ClassNode> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> todo = new ArrayList<>(List.of(internal));
        while (!todo.isEmpty()) {
            String n = todo.remove(0);
            if (n == null || !seen.add(n)) continue;
            ClassNode c = classes.get(n);
            if (c == null) continue;
            out.add(c);
            todo.add(c.superName);
            todo.addAll(c.interfaces);
        }
        return out;
    }

    private String superHint(ClassNode target, String name, boolean isField) {
        for (ClassNode c : hierarchy(target.name)) {
            if (c == target) continue;
            boolean has = isField ? field(c, name, null) != null : c.methods.stream().anyMatch(m -> m.name.equals(name));
            if (has) return " (it is declared in " + simple(c.name) + "; shadow it from a mixin of that class)";
        }
        return "";
    }

    private static String overloads(ClassNode target, String selector) {
        Member s = Member.parse(selector);
        if (s == null) return "";
        List<String> same = new ArrayList<>();
        for (MethodNode m : target.methods) if (m.name.equals(s.name())) same.add(m.name + m.desc);
        return same.isEmpty() ? "" : " (there is " + String.join(", ", same) + ")";
    }

    // ---------------------------------------------------------------- small helpers

    public record Member(String owner, String name, String desc) {
        // Lowner;name(args)ret, Lowner;name:Ltype;, name(args)ret, name(args), name
        public static Member parse(String s) {
            if (s == null || s.isEmpty()) return null;
            String owner = null;
            int semi = s.indexOf(';'), paren = s.indexOf('(');
            if (s.startsWith("L") && semi > 0 && (paren < 0 || semi < paren)) {
                owner = s.substring(1, semi);
                s = s.substring(semi + 1);
            }
            if (s.isEmpty()) return new Member(null, owner, null); // a bare type, as NEW uses
            int p = s.indexOf('('), c = s.indexOf(':');
            if (p >= 0) return new Member(owner, s.substring(0, p), s.substring(p));
            if (c >= 0) return new Member(owner, s.substring(0, c), s.substring(c + 1));
            return new Member(owner, s, null);
        }

        public boolean matches(String o, String n, String d) {
            if (owner != null && !owner.equals(o)) return false;
            if (!name.equals(n)) return false;
            if (desc == null) return true;
            return desc.endsWith(")") ? d.startsWith(desc) : d.equals(desc);
        }
    }

    public static AnnotationNode methodAnn(MethodNode m, String suffix) {
        return annotation(m.invisibleAnnotations, m.visibleAnnotations, MIXIN + suffix);
    }

    public static AnnotationNode annotation(List<AnnotationNode> a, List<AnnotationNode> b, String desc) {
        for (List<AnnotationNode> l : List.of(a == null ? List.<AnnotationNode>of() : a, b == null ? List.<AnnotationNode>of() : b)) {
            for (AnnotationNode n : l) if (n.desc.equals(desc)) return n;
        }
        return null;
    }

    public static Object value(AnnotationNode a, String key) {
        if (a.values == null) return null;
        for (int i = 0; i < a.values.size(); i += 2) if (a.values.get(i).equals(key)) return a.values.get(i + 1);
        return null;
    }

    public static Object value(AnnotationNode a, String key, Object def) {
        Object v = value(a, key);
        return v == null ? def : v;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) return (List<Object>) l;
        return List.of(v);
    }

    public static String stripPrefix(String name, String... prefixes) {
        for (String p : prefixes) {
            if (name.startsWith(p) && name.length() > p.length()) {
                String rest = name.substring(p.length());
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return name;
    }

    public static String simple(String internal) {
        return internal.substring(internal.lastIndexOf('/') + 1);
    }
}
