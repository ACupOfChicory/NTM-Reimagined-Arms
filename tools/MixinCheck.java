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

import acup.gunfeel.compat.MixinProbe;
import acup.gunfeel.compat.MixinProbe.Member;

import static acup.gunfeel.compat.MixinProbe.MIXIN;
import static acup.gunfeel.compat.MixinProbe.annotation;
import static acup.gunfeel.compat.MixinProbe.list;
import static acup.gunfeel.compat.MixinProbe.simple;
import static acup.gunfeel.compat.MixinProbe.value;

/**
 * Checks the compiled mixins against the jars they will be applied to, without starting the game:
 * the things Mixin would otherwise only report by crashing at launch. Whether the targets are there is
 * acup.gunfeel.compat.MixinProbe, the same check the game runs on an untested NTM; this adds the rest.
 * Every *.mixins.json is read; a config with a plugin (the mixins into NTM) is checked as require = 1.
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

    // ---------------------------------------------------------------- run

    int run(Path classes) throws IOException {
        List<Path> configFiles;
        try (var s = Files.list(classes)) {
            configFiles = s.filter(p -> p.getFileName().toString().endsWith(".mixins.json")).sorted().toList();
        }
        if (configFiles.isEmpty()) {
            System.out.println("ERROR no *.mixins.json in " + classes);
            return 1;
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

        // package -> config; a config with a plugin (the mixins into NTM) loads them all with require = 0 so its
        // plugin decides, but against the NTM it is built for every one of them must fit, as if require were 1
        Map<String, String> configOf = new HashMap<>();
        Map<String, Integer> requireOf = new HashMap<>();
        Map<String, Set<String>> listedOf = new HashMap<>();
        Set<String> allListed = new HashSet<>();
        for (Path configFile : configFiles) {
            String json = Files.readString(configFile);
            String cfg = configFile.getFileName().toString();
            String pkg = stringValue(json, "package").replace('.', '/');
            int require = 1;
            Matcher req = Pattern.compile("\"defaultRequire\"\\s*:\\s*(\\d+)").matcher(json);
            if (req.find()) require = Integer.parseInt(req.group(1));
            if (json.contains("\"plugin\"")) require = 1;
            if (configOf.put(pkg, cfg) != null) errors.add(cfg + ": package " + pkg + " is also another config's");
            Set<String> listed = new LinkedHashSet<>();
            for (String key : List.of("mixins", "client", "server")) {
                for (String name : arrayValue(json, key)) {
                    if (!listed.add(name)) errors.add(cfg + ": " + name + " listed twice");
                    String internal = pkg + "/" + name.replace('.', '/');
                    if (!allListed.add(internal)) errors.add(cfg + ": " + name + " is listed by another config too");
                    if (!mod.containsKey(internal)) errors.add(cfg + ": lists " + name + " but there is no such class");
                    requireOf.put(internal, require);
                }
            }
            listedOf.put(pkg, listed);
        }

        Set<String> mixinClasses = new HashSet<>();
        for (ClassNode n : mod.values()) {
            String pkg = null;
            for (String p : configOf.keySet()) {
                if (n.name.startsWith(p + "/") && (pkg == null || p.length() > pkg.length())) pkg = p;
            }
            if (pkg == null) continue;
            String rel = n.name.substring(pkg.length() + 1).replace('/', '.');
            if (rel.contains("$")) continue; // inner classes go with their mixin
            boolean listed = listedOf.get(pkg).contains(rel);
            mixinClasses.add(n.name);
            if (annotation(n.invisibleAnnotations, n.visibleAnnotations, MIXIN + "Mixin;") == null) {
                errors.add(rel + ": in the mixin package but has no @Mixin (Mixin refuses to load such a class)");
            } else if (!listed) {
                errors.add(rel + ": has @Mixin but is not listed in " + configOf.get(pkg) + " (never applied)");
            }
        }

        // mod code must not touch a mixin class, except accessor/invoker interfaces cast to
        for (ClassNode n : mod.values()) {
            if (mixinClasses.contains(n.name.contains("$") ? n.name.substring(0, n.name.indexOf('$')) : n.name)) continue;
            for (String ref : references(n)) {
                if (!mixinClasses.contains(ref)) continue;
                ClassNode m = mod.get(ref);
                if ((m.access & Opcodes.ACC_INTERFACE) == 0) {
                    errors.add(simple(n.name) + ": uses mixin class " + simple(ref) + " directly (IllegalClassLoadError at runtime)");
                }
            }
        }

        MixinProbe probe = new MixinProbe(this::cls);
        for (String name : mixinClasses) {
            ClassNode m = mod.get(name);
            int require = requireOf.getOrDefault(name, 1);
            for (MixinProbe.Problem p : probe.check(m, require)) (p.soft() ? warnings : errors).add(p.toString());
            checkHandlers(m, require);
        }

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

    // ---------------------------------------------------------------- the handlers' own signatures (MixinProbe
    // checks that the targets are there)

    void checkHandlers(ClassNode mixin, int defaultRequire) {
        for (String target : MixinProbe.targets(mixin)) {
            ClassNode tc = cls(target);
            if (tc == null) continue;
            for (MethodNode h : mixin.methods) {
                for (String kind : MixinProbe.INJECTORS) {
                    AnnotationNode a = MixinProbe.methodAnn(h, "injection/" + kind + ";");
                    if (a == null) continue;
                    checkedInjectors++;
                    String where = simple(mixin.name) + "." + h.name;
                    List<AnnotationNode> ats = MixinProbe.ats(a);
                    for (Object sel : list(value(a, "method"))) {
                        String s = (String) sel;
                        if (s.contains("*") || s.startsWith("/")) {
                            warnings.add(where + ": wildcard/regex selector \"" + s + "\" not checked");
                            continue;
                        }
                        List<MethodNode> found = MixinProbe.select(tc, s);
                        if (found.size() > 1 && !s.contains("(")) {
                            warnings.add(where + ": \"" + s + "\" matches " + found.size() + " overloads in "
                                    + simple(tc.name) + ", give the descriptor");
                        }
                        for (MethodNode tm : found) {
                            String into = simple(tc.name) + "." + tm.name + tm.desc;
                            switch (kind) {
                                case "Inject" -> checkInjectHandler(where, h, tm, into, a);
                                case "Redirect" -> {
                                    if (!ats.isEmpty()) checkRedirectHandler(where, h, tm, ats.get(0), into);
                                }
                                case "ModifyArg" -> {
                                    if (!ats.isEmpty()) checkModifyArgHandler(where, h, a, ats.get(0));
                                }
                                default -> {
                                }
                            }
                        }
                    }
                }
            }
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
        if (t == null || t.desc() == null) return;
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
        if (t == null || t.desc() == null || !t.desc().contains(")")) return;
        Type[] args = Type.getArgumentTypes(t.desc());
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

    // ---------------------------------------------------------------- small helpers

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
