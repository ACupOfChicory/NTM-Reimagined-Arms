import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

// Do two builds of the mod run the same? Compares two jars entry by entry:
//   classes: the bytecode without debug info (line numbers, local variable names, source file name), which only
//            shows in stack traces: everything the game runs must be the same;
//   other files (textures, sounds, shaders, configs, mixin lists): byte for byte.
// The manifest is only listed, as build tools write their own name into it.
// Run: java -cp <asm, asm-tree, asm-util jars> tools/JarDiff.java old.jar new.jar   Exit 0 = the same.
public class JarDiff {
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: JarDiff old.jar new.jar");
            System.exit(2);
        }
        Map<String, byte[]> a = read(args[0]), b = read(args[1]);
        int diffs = 0;
        TreeSet<String> names = new TreeSet<>(a.keySet());
        names.addAll(b.keySet());
        for (String n : names) {
            byte[] x = a.get(n), y = b.get(n);
            if (x == null || y == null) {
                System.out.println((x == null ? "only in new: " : "only in old: ") + n);
                diffs++;
                continue;
            }
            if (n.equals("META-INF/MANIFEST.MF")) {
                if (!Arrays.equals(x, y)) System.out.println("note: the manifests differ (not counted)");
                continue;
            }
            if (n.endsWith(".class")) {
                if (!Arrays.equals(code(x), code(y))) {
                    System.out.println("code differs: " + n);
                    firstDifference(text(x), text(y));
                    diffs++;
                }
            } else if (!Arrays.equals(x, y)) {
                System.out.println("file differs: " + n + " (" + x.length + " -> " + y.length + " bytes)");
                diffs++;
            }
        }
        System.out.println(names.size() + " entries compared: " + (diffs == 0 ? "the same" : diffs + " differ"));
        System.exit(diffs == 0 ? 0 : 1);
    }

    static Map<String, byte[]> read(String jar) throws IOException {
        Map<String, byte[]> out = new TreeMap<>();
        try (ZipFile z = new ZipFile(jar)) {
            for (ZipEntry e : java.util.Collections.list(z.entries())) {
                if (e.isDirectory()) continue;
                try (var in = z.getInputStream(e)) {
                    out.put(e.getName(), in.readAllBytes());
                }
            }
        }
        return out;
    }

    // the class written back without debug info: the same code gives the same bytes whatever debug flags javac had
    static byte[] code(byte[] cls) {
        ClassWriter w = new ClassWriter(0);
        new ClassReader(cls).accept(w, ClassReader.SKIP_DEBUG);
        return w.toByteArray();
    }

    static List<String> text(byte[] cls) {
        StringWriter s = new StringWriter();
        new ClassReader(cls).accept(new TraceClassVisitor(null, new Textifier(), new PrintWriter(s)), ClassReader.SKIP_DEBUG);
        return new ArrayList<>(Arrays.asList(s.toString().split("\n")));
    }

    static void firstDifference(List<String> x, List<String> y) {
        int i = 0;
        while (i < x.size() && i < y.size() && x.get(i).equals(y.get(i))) i++;
        for (int k = Math.max(0, i - 3); k < i; k++) System.out.println("      " + x.get(k).strip());
        System.out.println("    - " + (i < x.size() ? x.get(i).strip() : "(end)"));
        System.out.println("    + " + (i < y.size() ? y.get(i).strip() : "(end)"));
    }
}
