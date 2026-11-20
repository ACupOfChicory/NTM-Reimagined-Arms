package acup.gunfeel;

import java.lang.ref.Reference;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.neoforged.fml.ModList;

/**
 * LevelLeakStand's search for what keeps an object alive: breadth first from the static fields of the classes of the
 * mods named (and from extra roots), through maps, collections, arrays and the fields of objects whose classes let
 * themselves be read (not the JDK's, apart from its collections), over strong references only (no Reference's referent,
 * no weak map's keys); the first few paths
 * to the object, as "Class.FIELD.field[3].key ...". A stand's tool: slow, and it initializes the mods' classes it reads.
 */
final class LevelLeakSearch {
    private static final int MAX_NODES = 3_000_000, MAX_PATHS = 6;

    private record Node(Object o, String path, int depth) {
    }

    private LevelLeakSearch() {
    }

    /** each root that keeps the object, one path each: a search, its path's root left out, the search again */
    static List<String> paths(Object target, List<String> mods, Map<String, Object> extraRoots, int maxDepth) {
        List<String> out = new ArrayList<>();
        java.util.Set<String> without = new java.util.HashSet<>();
        for (int i = 0; i < MAX_PATHS; i++) {
            String p = path(target, mods, extraRoots, maxDepth, without);
            out.add(p);
            if (p.startsWith("none found")) break;
            int dot = p.indexOf('.', p.indexOf('.') + 1);
            without.add(dot < 0 ? p : p.substring(0, dot));
        }
        return out;
    }

    private static String path(Object target, List<String> mods, Map<String, Object> extraRoots, int maxDepth, java.util.Set<String> without) {
        List<String> out = new ArrayList<>();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<Node> q = new ArrayDeque<>();
        for (var e : extraRoots.entrySet()) if (!without.contains(e.getKey())) push(q, seen, e.getValue(), e.getKey(), 0);
        ClassLoader loader = LevelLeakSearch.class.getClassLoader();
        for (String mod : mods) {
            var file = ModList.get().getModFileById(mod);
            if (file == null) continue;
            for (var cd : file.getFile().getScanResult().getClasses()) {
                Class<?> c;
                try {
                    c = Class.forName(cd.clazz().getClassName(), false, loader);
                } catch (Throwable t) {
                    continue;
                }
                for (Field f : fields(c)) {
                    if (!Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    try {
                        f.setAccessible(true);
                        String root = c.getSimpleName() + "." + f.getName();
                        if (!without.contains(root)) push(q, seen, f.get(null), root, 0);
                    } catch (Throwable t) {
                        // a class that cannot be initialized here, a field not to be read
                    }
                }
            }
        }
        int nodes = 0;
        while (!q.isEmpty() && nodes++ < MAX_NODES && out.isEmpty()) {
            Node n = q.poll();
            if (n.o == target) {
                out.add(n.path);
                continue;
            }
            if (n.depth >= maxDepth) continue;
            Object o = n.o;
            int d = n.depth + 1;
            try {
                if (o instanceof Reference<?>) continue;
                if (o instanceof Map<?, ?> m) {
                    int i = 0;
                    boolean weakKeys = weakKeys(m);
                    for (var e : m.entrySet()) {
                        if (!weakKeys) push(q, seen, e.getKey(), n.path + ".key" + i, d);
                        push(q, seen, e.getValue(), n.path + ".value" + i, d);
                        i++;
                    }
                    if (isJdk(o.getClass())) continue;
                } else if (o instanceof Collection<?> c) {
                    int i = 0;
                    for (Object e : c) push(q, seen, e, n.path + "[" + i++ + "]", d);
                    if (isJdk(o.getClass())) continue;
                } else if (o.getClass().isArray()) {
                    if (o.getClass().getComponentType().isPrimitive()) continue;
                    int len = Array.getLength(o);
                    for (int i = 0; i < len; i++) push(q, seen, Array.get(o, i), n.path + "[" + i + "]", d);
                    continue;
                }
                if (isJdk(o.getClass())) continue;
                for (Field f : fields(o.getClass())) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    try {
                        f.setAccessible(true);
                        push(q, seen, f.get(o), n.path + "." + f.getName(), d);
                    } catch (Throwable t) {
                        // not readable from here
                    }
                }
            } catch (Throwable t) {
                // a collection changing under the walk, an iterator that fails: that branch is left
            }
        }
        return out.isEmpty() ? "none found in " + nodes + " objects" : out.get(0);
    }

    private static void push(ArrayDeque<Node> q, IdentityHashMap<Object, Boolean> seen, Object o, String path, int depth) {
        if (o == null || o instanceof String || o instanceof Number || o instanceof Boolean || o instanceof Class<?>) return;
        if (seen.put(o, Boolean.TRUE) != null) return;
        q.add(new Node(o, path, depth));
    }

    /**
     * a map whose keys it holds weakly: a WeakHashMap, or the JDK's synchronized wrapper round one (this mod wraps only
     * WeakHashMaps so; its inner map is read when the JDK lets it, else taken for one)
     */
    private static boolean weakKeys(Map<?, ?> m) {
        if (m instanceof java.util.WeakHashMap<?, ?>) return true;
        if (!m.getClass().getName().equals("java.util.Collections$SynchronizedMap")) return false;
        try {
            Field f = m.getClass().getDeclaredField("m");
            f.setAccessible(true);
            return f.get(m) instanceof java.util.WeakHashMap<?, ?>;
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean isJdk(Class<?> c) {
        String n = c.getName();
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.") || n.startsWith("sun.") || n.startsWith("com.sun.");
    }

    private static List<Field> fields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                for (Field f : k.getDeclaredFields()) out.add(f);
            } catch (Throwable t) {
                // a class whose fields name a class not there (client-only on a server)
            }
        }
        return out;
    }
}
