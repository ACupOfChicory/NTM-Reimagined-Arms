import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.objectweb.asm.ClassReader;
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

/**
 * Checks the compiled mixins against the jars they will be applied to, without starting the game:
 * the things Mixin would otherwise only report by crashing at launch.
 *
 * usage: java -cp <asm jars> tools/MixinCheck.java <compiled classes dir> <class path jars, ':'-separated>
 *
 * - every class in the mixin package is listed in the mixin config, and every listed class exists;
 * - mod code does not load a mixin class directly (only accessor interfaces may be used that way);
 * - @Mixin targets exist (unless @Pseudo);
 * - injector "method" selectors match a method declared in the target class;
 * - @At INVOKE / FIELD / NEW targets really occur inside that method, and an ordinal is in range;
 * - @Inject handlers take CallbackInfo or CallbackInfoReturnable as the target returns, after either no target
 *   arguments or all of them with the same types, and are static when the target is;
 * - @Redirect and @ModifyArg handlers fit the call they replace;
 * - @Shadow and @Accessor members exist in the target class with the same type.
 * A miss on an injector with require = 0 is a warning, everything else an error (exit code 1).
 */
public class MixinCheck {
    static final String MIXIN = "Lorg/spongepowered/asm/mixin/";
    static final String CI = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
    static final String CIR = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";

    final Map<String, byte[]> classpath = new HashMap<>();
    final Map<String, ClassNode> parsed = new HashMap<>();
    final List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
    int defaultRequire = 1;
    int checkedInjectors;

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: MixinCheck <classes dir> <jar:jar:...>");
            System.exit(2);
        }
        MixinCheck c = new MixinCheck();
        for (String jar : args[1].split(":")) if (!jar.isBlank()) c.indexJar(Path.of(jar));
        int code = c.run(Path.of(args[0]));
        System.exit(code);
    }

    // ---------------------------------------------------------------- class path

    void indexJar(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                try (InputStream in = zip.getInputStream(e)) {
                    if (n.endsWith(".class") && !n.startsWith("META-INF/versions/")) {
                        classpath.putIfAbsent(n.substring(0, n.length() - 6), in.readAllBytes());
                    } else if (n.startsWith("META-INF/jarjar/") && n.endsWith(".jar")) {
                        indexNested(in);
                    }
                }
            }
        }
    }

    void indexNested(InputStream raw) throws IOException {
        ZipInputStream zip = new ZipInputStream(raw);
        for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
            String n = e.getName();
            if (n.endsWith(".class") && !n.startsWith("META-INF/versions/")) {
                classpath.putIfAbsent(n.substring(0, n.length() - 6), zip.readAllBytes());
            }
        }
    }

    ClassNode cls(String internal) {
        if (parsed.containsKey(internal)) return parsed.get(internal);
        byte[] bytes = classpath.get(internal);
        if (bytes == null) {
            try (InputStream in = ClassLoader.getSystemResourceAsStream(internal + ".class")) {
                if (in != null) bytes = in.readAllBytes();
            } catch (IOException ignored) {
            }
        }
        ClassNode node = null;
        if (bytes != null) {
            node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
        }
        parsed.put(internal, node);
        return node;
    }

    /** the class and all its superclasses and interfaces */
    List<ClassNode> hierarchy(String internal) {
        List<ClassNode> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> todo = new ArrayList<>(List.of(internal));
        while (!todo.isEmpty()) {
            String n = todo.remove(0);
            if (n == null || !seen.add(n)) continue;
            ClassNode c = cls(n);
            if (c == null) continue;
            out.add(c);
            todo.add(c.superName);
            todo.addAll(c.interfaces);
        }
        return out;
    }

    // ---------------------------------------------------------------- run

    int run(Path classes) throws IOException {
        Path configFile;
        try (var s = Files.list(classes)) {
            configFile = s.filter(p -> p.getFileName().toString().endsWith(".mixins.json")).findFirst().orElse(null);
        }
        if (configFile == null) {
            System.out.println("ERROR no *.mixins.json in " + classes);
            return 1;
        }
        String json = Files.readString(configFile);
        String pkg = stringValue(json, "package").replace('.', '/');
        Matcher req = Pattern.compile("\"defaultRequire\"\\s*:\\s*(\\d+)").matcher(json);
        if (req.find()) defaultRequire = Integer.parseInt(req.group(1));
        Set<String> listed = new LinkedHashSet<>();
        for (String key : List.of("mixins", "client", "server")) {
            for (String name : arrayValue(json, key)) {
                if (!listed.add(name)) errors.add(configFile.getFileName() + ": " + name + " listed twice");
            }
        }

        // the mod's own classes
        Map<String, ClassNode> mod = new HashMap<>();
        try (var s = Files.walk(classes)) {
            for (Path p : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".class"))::iterator) {
                ClassNode n = new ClassNode();
                new ClassReader(Files.readAllBytes(p)).accept(n, ClassReader.SKIP_FRAMES);
                mod.put(n.name, n);
                classpath.put(n.name, Files.readAllBytes(p));
            }
        }

        Set<String> mixinClasses = new HashSet<>();
        for (ClassNode n : mod.values()) {
            if (!n.name.startsWith(pkg + "/")) continue;
            String rel = n.name.substring(pkg.length() + 1).replace('/', '.');
            if (rel.contains("$")) continue; // inner classes go with their mixin
            mixinClasses.add(n.name);
            if (annotation(n.invisibleAnnotations, n.visibleAnnotations, MIXIN + "Mixin;") == null) {
                errors.add(rel + ": in the mixin package but has no @Mixin (Mixin refuses to load such a class)");
            } else if (!listed.contains(rel)) {
                errors.add(rel + ": has @Mixin but is not listed in " + configFile.getFileName() + " (never applied)");
            }
        }
        for (String name : listed) {
            if (!mod.containsKey(pkg + "/" + name.replace('.', '/'))) {
                errors.add(configFile.getFileName() + ": lists " + name + " but there is no such class");
            }
        }

        // mod code must not touch a mixin class, except accessor/invoker interfaces cast to
        for (ClassNode n : mod.values()) {
            if (n.name.startsWith(pkg + "/")) continue;
            for (String ref : references(n)) {
                if (!mixinClasses.contains(ref)) continue;
                ClassNode m = mod.get(ref);
                if ((m.access & Opcodes.ACC_INTERFACE) == 0) {
                    errors.add(simple(n.name) + ": uses mixin class " + simple(ref) + " directly (IllegalClassLoadError at runtime)");
                }
            }
        }

        for (String name : mixinClasses) checkMixin(mod.get(name));

        for (String w : warnings) System.out.println("WARN  " + w);
        for (String e : errors) System.out.println("ERROR " + e);
        System.out.printf("%d mixins, %d injectors checked: %d errors, %d warnings%n",
                mixinClasses.size(), checkedInjectors, errors.size(), warnings.size());
        return errors.isEmpty() ? 0 : 1;
    }

    Set<String> references(ClassNode n) {
        Set<String> out = new HashSet<>();
        for (MethodNode m : n.methods) {
            for (AbstractInsnNode i : m.instructions) {
                if (i instanceof MethodInsnNode mi) out.add(mi.owner);
                else if (i instanceof FieldInsnNode fi) out.add(fi.owner);
                else if (i instanceof TypeInsnNode ti && ti.getOpcode() == Opcodes.NEW) out.add(ti.desc);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- one mixin

    void checkMixin(ClassNode mixin) {
        String where = simple(mixin.name);
        AnnotationNode ann = annotation(mixin.invisibleAnnotations, mixin.visibleAnnotations, MIXIN + "Mixin;");
        if (ann == null) return;
        boolean pseudo = annotation(mixin.invisibleAnnotations, mixin.visibleAnnotations, MIXIN + "Pseudo;") != null;
        List<String> targets = new ArrayList<>();
        for (Object t : list(value(ann, "value"))) targets.add(((Type) t).getInternalName());
        for (Object t : list(value(ann, "targets"))) targets.add(((String) t).replace('.', '/'));
        if (targets.isEmpty()) errors.add(where + ": @Mixin without a target");

        for (String target : targets) {
            ClassNode tc = cls(target);
            if (tc == null) {
                if (pseudo) warnings.add(where + ": @Pseudo target " + target + " not on the class path, skipped");
                else errors.add(where + ": target class " + target + " not found");
                continue;
            }
            for (FieldNode f : mixin.fields) checkShadowField(where, mixin, f, tc);
            for (MethodNode m : mixin.methods) checkMethod(where, m, tc, pseudo);
        }
    }

    void checkShadowField(String where, ClassNode mixin, FieldNode f, ClassNode target) {
        AnnotationNode shadow = annotation(f.invisibleAnnotations, f.visibleAnnotations, MIXIN + "Shadow;");
        if (shadow == null) return;
        String prefix = (String) value(shadow, "prefix", "shadow$");
        String name = f.name.startsWith(prefix) ? f.name.substring(prefix.length()) : f.name;
        FieldNode tf = field(target, name, null);
        if (tf == null) {
            errors.add(where + ": @Shadow field " + name + " not in " + simple(target.name) + superHint(target, name, true));
        } else if (!tf.desc.equals(f.desc)) {
            errors.add(where + ": @Shadow field " + name + " is " + f.desc + " here but " + tf.desc + " in " + simple(target.name));
        }
    }

    void checkMethod(String where, MethodNode m, ClassNode target, boolean pseudo) {
        AnnotationNode a;
        if ((a = methodAnn(m, "Shadow;")) != null) {
            String prefix = (String) value(a, "prefix", "shadow$");
            String name = m.name.startsWith(prefix) ? m.name.substring(prefix.length()) : m.name;
            if (method(target, name, m.desc) == null) {
                errors.add(where + ": @Shadow method " + name + m.desc + " not in " + simple(target.name) + superHint(target, name, false));
            }
            return;
        }
        if ((a = methodAnn(m, "gen/Accessor;")) != null) {
            checkAccessor(where, m, a, target);
            return;
        }
        if ((a = methodAnn(m, "gen/Invoker;")) != null) {
            String name = (String) value(a, "value", null);
            if (name == null) name = stripPrefix(m.name, "call", "invoke");
            boolean any = false;
            for (MethodNode tm : target.methods) any |= tm.name.equals(name) && tm.desc.equals(m.desc);
            if (!any) errors.add(where + ": @Invoker " + name + m.desc + " not in " + simple(target.name));
            return;
        }
        for (String kind : List.of("Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyVariable", "ModifyConstant")) {
            a = methodAnn(m, "injection/" + kind + ";");
            if (a != null) {
                checkInjector(where + "." + m.name, kind, m, a, target, pseudo);
                return;
            }
        }
    }

    void checkAccessor(String where, MethodNode m, AnnotationNode a, ClassNode target) {
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
            errors.add(where + ": @Accessor field " + name + " not in " + simple(target.name));
            return;
        }
        String type = setter ? (args.length == 1 ? args[0].getDescriptor() : "?") : ret.getDescriptor();
        if (!type.equals(f.desc)) {
            errors.add(where + ": @Accessor " + m.name + " uses " + type + " but " + name + " is " + f.desc);
        }
    }

    void checkInjector(String where, String kind, MethodNode handler, AnnotationNode a, ClassNode target, boolean pseudo) {
        checkedInjectors++;
        int require = (Integer) value(a, "require", -1);
        if (require < 0) require = defaultRequire;
        boolean soft = require == 0 || pseudo;

        List<MethodNode> methods = new ArrayList<>();
        List<Object> selectors = list(value(a, "method"));
        if (selectors.isEmpty()) {
            report(soft, where + ": @" + kind + " without a method selector");
            return;
        }
        for (Object sel : selectors) {
            String s = (String) sel;
            if (s.contains("*") || s.startsWith("/")) {
                warnings.add(where + ": wildcard/regex selector \"" + s + "\" not checked");
                continue;
            }
            List<MethodNode> found = select(target, s);
            if (found.isEmpty()) {
                report(soft, where + ": no method \"" + s + "\" in " + simple(target.name) + overloads(target, s));
            } else {
                if (found.size() > 1 && !s.contains("(")) {
                    warnings.add(where + ": \"" + s + "\" matches " + found.size() + " overloads in "
                            + simple(target.name) + ", give the descriptor");
                }
                methods.addAll(found);
            }
        }

        List<AnnotationNode> ats = new ArrayList<>();
        Object at = value(a, "at");
        if (at instanceof AnnotationNode an) ats.add(an);
        else for (Object o : list(at)) ats.add((AnnotationNode) o);

        for (MethodNode tm : methods) {
            String into = simple(target.name) + "." + tm.name + tm.desc;
            for (AnnotationNode an : ats) checkAt(where, an, target, tm, into, soft);
            switch (kind) {
                case "Inject" -> checkInjectHandler(where, handler, tm, into, a);
                case "Redirect" -> {
                    if (!ats.isEmpty()) checkRedirectHandler(where, handler, tm, ats.get(0), into);
                }
                case "ModifyArg" -> {
                    if (!ats.isEmpty()) checkModifyArgHandler(where, handler, a, ats.get(0));
                }
                default -> {
                }
            }
        }
    }

    void checkAt(String where, AnnotationNode at, ClassNode owner, MethodNode tm, String into, boolean soft) {
        String point = (String) value(at, "value", "");
        String target = (String) value(at, "target", "");
        int ordinal = (Integer) value(at, "ordinal", -1);
        if (target.isEmpty()) return; // HEAD, RETURN, TAIL, CONSTANT...
        Member t = Member.parse(target);
        if (t == null) {
            warnings.add(where + ": @At target \"" + target + "\" not understood, skipped");
            return;
        }
        if (t.owner != null && cls(t.owner) == null) {
            report(soft, where + ": @At target class " + t.owner + " not found");
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
                            && (ti.desc.equals(t.name) || ti.desc.equals(t.owner))) count++;
                }
                default -> {
                    return;
                }
            }
        }
        if (count == 0) {
            report(soft, where + ": " + into + " has no " + point + " " + target);
        } else if (ordinal >= count) {
            report(soft, where + ": ordinal " + ordinal + " but " + into + " has only " + count + " " + point + " " + target);
        }
    }

    void checkInjectHandler(String where, MethodNode h, MethodNode tm, String into, AnnotationNode a) {
        boolean targetStatic = (tm.access & Opcodes.ACC_STATIC) != 0;
        if (targetStatic && (h.access & Opcodes.ACC_STATIC) == 0) {
            errors.add(where + ": handler must be static, " + into + " is static");
        }
        Type[] hp = Type.getArgumentTypes(h.desc);
        Type[] tp = Type.getArgumentTypes(tm.desc);
        boolean voidTarget = Type.getReturnType(tm.desc).getSort() == Type.VOID;
        String wantCb = voidTarget ? CI : CIR;
        if (Type.getReturnType(h.desc).getSort() != Type.VOID) errors.add(where + ": @Inject handler must return void");
        int cb;
        if (hp.length > 0 && isCallback(hp[0])) cb = 0;
        else {
            cb = tp.length;
            if (hp.length <= tp.length) {
                errors.add(where + ": handler needs " + (tp.length == 0 ? "" : "the target's arguments and then ")
                        + simple(wantCb) + " (" + into + ")");
                return;
            }
            for (int i = 0; i < tp.length; i++) {
                if (!hp[i].equals(tp[i])) {
                    errors.add(where + ": argument " + i + " is " + hp[i].getClassName() + ", " + into + " has " + tp[i].getClassName());
                    return;
                }
            }
        }
        if (!hp[cb].getInternalName().equals(wantCb)) {
            errors.add(where + ": takes " + simple(hp[cb].getInternalName()) + " but " + into + " needs " + simple(wantCb));
        }
        boolean capturesLocals = value(a, "locals") != null;
        if (hp.length > cb + 1 && !capturesLocals) {
            errors.add(where + ": extra arguments after the callback without locals capture");
        }
    }

    void checkRedirectHandler(String where, MethodNode h, MethodNode tm, AnnotationNode at, String into) {
        String point = (String) value(at, "value", "");
        Member t = Member.parse((String) value(at, "target", ""));
        if (t == null || t.desc == null) return;
        boolean handlerStatic = (h.access & Opcodes.ACC_STATIC) != 0;
        if ((tm.access & Opcodes.ACC_STATIC) != 0 && !handlerStatic) {
            errors.add(where + ": handler must be static, " + into + " is static");
        }
        List<Type> want = new ArrayList<>();
        Type ret;
        if (point.startsWith("INVOKE")) {
            AbstractInsnNode call = null;
            for (AbstractInsnNode i : tm.instructions) {
                if (i instanceof MethodInsnNode mi && t.matches(mi.owner, mi.name, mi.desc)) {
                    call = i;
                    break;
                }
            }
            if (call == null) return; // already reported by checkAt
            MethodInsnNode mi = (MethodInsnNode) call;
            if (mi.getOpcode() != Opcodes.INVOKESTATIC) want.add(Type.getObjectType(mi.owner));
            want.addAll(List.of(Type.getArgumentTypes(mi.desc)));
            ret = Type.getReturnType(mi.desc);
        } else if (point.equals("FIELD")) {
            FieldInsnNode fi = null;
            for (AbstractInsnNode i : tm.instructions) {
                if (i instanceof FieldInsnNode f && t.matches(f.owner, f.name, f.desc)) {
                    fi = f;
                    break;
                }
            }
            if (fi == null) return;
            int op = fi.getOpcode();
            if (op == Opcodes.GETFIELD || op == Opcodes.PUTFIELD) want.add(Type.getObjectType(fi.owner));
            if (op == Opcodes.PUTFIELD || op == Opcodes.PUTSTATIC) {
                want.add(Type.getType(fi.desc));
                ret = Type.VOID_TYPE;
            } else {
                ret = Type.getType(fi.desc);
            }
        } else {
            return;
        }
        Type[] hp = Type.getArgumentTypes(h.desc);
        Type[] tp = Type.getArgumentTypes(tm.desc);
        boolean ok = hp.length == want.size() || hp.length == want.size() + tp.length;
        for (int i = 0; ok && i < want.size(); i++) ok = hp[i].equals(want.get(i));
        for (int i = want.size(); ok && i < hp.length; i++) ok = hp[i].equals(tp[i - want.size()]);
        if (!ok) {
            errors.add(where + ": @Redirect handler should take " + typeList(want) + " (then optionally " + into
                    + "'s arguments), it takes " + typeList(List.of(hp)));
        }
        if (!Type.getReturnType(h.desc).equals(ret)) {
            errors.add(where + ": @Redirect handler should return " + ret.getClassName() + ", it returns "
                    + Type.getReturnType(h.desc).getClassName());
        }
    }

    void checkModifyArgHandler(String where, MethodNode h, AnnotationNode a, AnnotationNode at) {
        Member t = Member.parse((String) value(at, "target", ""));
        if (t == null || t.desc == null || !t.desc.contains(")")) return;
        Type[] args = Type.getArgumentTypes(t.desc);
        Type ret = Type.getReturnType(h.desc);
        int index = (Integer) value(a, "index", -1);
        if (index >= args.length) {
            errors.add(where + ": @ModifyArg index " + index + " but the call has " + args.length + " arguments");
            return;
        }
        if (index >= 0 && !args[index].equals(ret)) {
            errors.add(where + ": @ModifyArg returns " + ret.getClassName() + " but argument " + index + " is " + args[index].getClassName());
        }
        if (index < 0) {
            int same = 0;
            for (Type x : args) if (x.equals(ret)) same++;
            if (same != 1) errors.add(where + ": @ModifyArg without index needs exactly one " + ret.getClassName()
                    + " argument, the call has " + same);
        }
        Type[] hp = Type.getArgumentTypes(h.desc);
        boolean single = hp.length == 1 && hp[0].equals(ret);
        boolean all = java.util.Arrays.equals(hp, args);
        if (!single && !all) {
            errors.add(where + ": @ModifyArg handler should take (" + ret.getClassName() + ") or all of the call's arguments");
        }
    }

    // ---------------------------------------------------------------- lookups

    static boolean isCallback(Type t) {
        return t.getSort() == Type.OBJECT && (t.getInternalName().equals(CI) || t.getInternalName().equals(CIR));
    }

    /** methods of the target class itself matching a selector: name, name(args), name(args)ret, Lowner;name(desc) */
    List<MethodNode> select(ClassNode target, String selector) {
        Member s = Member.parse(selector);
        List<MethodNode> out = new ArrayList<>();
        if (s == null) return out;
        if (s.owner != null && !s.owner.equals(target.name)) return out;
        for (MethodNode m : target.methods) if (s.matches(target.name, m.name, m.desc)) out.add(m);
        return out;
    }

    FieldNode field(ClassNode c, String name, String desc) {
        for (FieldNode f : c.fields) if (f.name.equals(name) && (desc == null || f.desc.equals(desc))) return f;
        return null;
    }

    MethodNode method(ClassNode c, String name, String desc) {
        for (MethodNode m : c.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
        return null;
    }

    String superHint(ClassNode target, String name, boolean isField) {
        for (ClassNode c : hierarchy(target.name)) {
            if (c == target) continue;
            boolean has = isField ? field(c, name, null) != null : c.methods.stream().anyMatch(m -> m.name.equals(name));
            if (has) return " (it is declared in " + simple(c.name) + "; shadow it from a mixin of that class)";
        }
        return "";
    }

    String overloads(ClassNode target, String selector) {
        Member s = Member.parse(selector);
        if (s == null) return "";
        List<String> same = new ArrayList<>();
        for (MethodNode m : target.methods) if (m.name.equals(s.name)) same.add(m.name + m.desc);
        return same.isEmpty() ? "" : " (there is " + String.join(", ", same) + ")";
    }

    void report(boolean soft, String msg) {
        (soft ? warnings : errors).add(msg);
    }

    // ---------------------------------------------------------------- small helpers

    record Member(String owner, String name, String desc) {
        /** Lowner;name(args)ret, Lowner;name:Ltype;, name(args)ret, name(args), name */
        static Member parse(String s) {
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

        boolean matches(String o, String n, String d) {
            if (owner != null && !owner.equals(o)) return false;
            if (!name.equals(n)) return false;
            if (desc == null) return true;
            return desc.endsWith(")") ? d.startsWith(desc) : d.equals(desc);
        }
    }

    AnnotationNode methodAnn(MethodNode m, String suffix) {
        return annotation(m.invisibleAnnotations, m.visibleAnnotations, MIXIN + suffix);
    }

    @SafeVarargs
    static AnnotationNode annotation(String desc, List<AnnotationNode>... lists) {
        for (List<AnnotationNode> l : lists) if (l != null) for (AnnotationNode a : l) if (a.desc.equals(desc)) return a;
        return null;
    }

    static AnnotationNode annotation(List<AnnotationNode> a, List<AnnotationNode> b, String desc) {
        return annotation(desc, a, b);
    }

    static Object value(AnnotationNode a, String key) {
        if (a.values == null) return null;
        for (int i = 0; i < a.values.size(); i += 2) if (a.values.get(i).equals(key)) return a.values.get(i + 1);
        return null;
    }

    static Object value(AnnotationNode a, String key, Object def) {
        Object v = value(a, key);
        return v == null ? def : v;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) return (List<Object>) l;
        return List.of(v);
    }

    static String stripPrefix(String name, String... prefixes) {
        for (String p : prefixes) {
            if (name.startsWith(p) && name.length() > p.length()) {
                String rest = name.substring(p.length());
                return Character.toLowerCase(rest.charAt(0)) + rest.substring(1);
            }
        }
        return name;
    }

    static String simple(String internal) {
        return internal.substring(internal.lastIndexOf('/') + 1);
    }

    static String typeList(List<Type> types) {
        List<String> s = new ArrayList<>();
        for (Type t : types) s.add(t.getClassName().substring(t.getClassName().lastIndexOf('.') + 1));
        return "(" + String.join(", ", s) + ")";
    }

    static String stringValue(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    static List<String> arrayValue(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        List<String> out = new ArrayList<>();
        if (!m.find()) return out;
        Matcher s = Pattern.compile("\"([^\"]+)\"").matcher(m.group(1));
        while (s.find()) out.add(s.group(1));
        return out;
    }
}
