package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;


/**
 * The dedicated server has no client classes (NeoForge's dist cleaner refuses them). What the server does with the mod's
 * classes, done in a loader that refuses them the same way: core's root (Wiring) found and made, as CoreServices
 * makes it (Wiring(false): FML's side is a dedicated server's), the mod's class and its common event subscribers. A port of
 * the client's made in Wiring loaded the client's classes and the server did not start (from X2055). And by the
 * bytecode, narrower: the classes Wiring names name no client class but those listed with their reason (CLIENT_NAMING).
 *
 * <p>Not covered here: the mixins (they go into the game's classes) and the classes the server loads later, on a tick,
 * an event or a command: the dedicated server's stand is what holds those.
 */
class ServerLoadTest {
    private static final String[] CLIENT = {"net.minecraft.client.", "com.mojang.blaze3d.", "net.neoforged.neoforge.client."};

    // the test's classpath, every class its own (none from the test's loader), the client's refused
    static final class ServerLoader extends URLClassLoader {
        ServerLoader() {
            super(urls(), ClassLoader.getPlatformClassLoader());
        }

        private static URL[] urls() {
            List<URL> out = new ArrayList<>();
            for (String p : System.getProperty("java.class.path").split(File.pathSeparator)) {
                try {
                    out.add(new File(p).toURI().toURL());
                } catch (java.net.MalformedURLException e) {
                    throw new IllegalStateException(p, e);
                }
            }
            return out.toArray(URL[]::new);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            for (String c : CLIENT) if (name.startsWith(c)) throw new ClassNotFoundException("not on the dedicated server: " + name);
            return super.loadClass(name, resolve);
        }
    }

    // core's root made as the server makes it: no port of the client's, none of its classes loaded
    @Test
    void theServersWiring() throws Exception {
        try (ServerLoader loader = new ServerLoader()) {
            // the loader's own package acup.gunfeel, not the test's: its constructor reached past the package
            var make = Class.forName(Wiring.class.getName(), true, loader).getDeclaredConstructor(boolean.class);
            make.setAccessible(true);
            Object wiring = make.newInstance(false);
            Class<?> render = Class.forName(acup.gunfeel.host.render.RenderPort.class.getName(), false, loader);
            assertNull(wiring.getClass().getMethod("port", Class.class).invoke(wiring, render));
        }
    }

    // and the client's has the render port
    @Test
    void theClientsWiring() {
        assertSame(RenderPort26.INSTANCE, new Wiring(true).port(acup.gunfeel.host.render.RenderPort.class));
        assertTrue(Wiring.client(), "no FML: a client's");
    }

    // the mod's class, checked and set up, and the common event subscribers, whose methods NeoForge reads (a method
    // naming a client class in its signature is a class not found)
    @Test
    void theModsClassAndCommonSubscribers() throws Exception {
        List<String> subscribers = new ArrayList<>();
        Path root = Path.of(Wiring.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var files = Files.walk(root)) {
            for (Path f : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class"))::iterator) {
                String name = root.relativize(f).toString().replace(File.separatorChar, '.').replaceAll("\\.class$", "");
                if (!new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.ISO_8859_1).contains("EventBusSubscriber")) continue;
                var at = Class.forName(name, false, getClass().getClassLoader()).getAnnotation(net.neoforged.fml.common.EventBusSubscriber.class);
                if (at != null && (at.value().length == 0 || List.of(at.value()).contains(net.neoforged.api.distmarker.Dist.DEDICATED_SERVER))) subscribers.add(name);
            }
        }
        assertTrue(subscribers.contains(LevelUnload.class.getName()), "subscribers found: " + subscribers);
        // core's roots (META-INF/services) checked and set up: making Wiring is theServersWiring's
        List<String> providers = new ArrayList<>();
        Path dir = Path.of(getClass().getClassLoader().getResource("META-INF/services/" + acup.gunfeel.host.Ports.class.getName()).toURI()).getParent();
        try (var services = Files.list(dir)) {
            for (Path f : (Iterable<Path>) services::iterator)
                for (String line : Files.readAllLines(f)) if (!line.isBlank() && !line.startsWith("#")) providers.add(line.trim());
        }
        assertTrue(providers.contains(Wiring.class.getName()), "providers: " + providers);
        try (ServerLoader loader = new ServerLoader()) {
            Class.forName(GunFeelMod.class.getName(), true, loader);
            for (String s : providers) Class.forName(s, true, loader);
            for (String s : subscribers) Class.forName(s, true, loader).getDeclaredMethods();
        }
    }
    // The classes Wiring names that name the client's classes themselves: each one only in a method the server never
    // calls, or called on the client only. A new one is added here with its reason, under review.
    private static final Map<String, String> CLIENT_NAMING = Map.of(
            // GamePause: asks Minecraft whether the game is paused, called by the client's systems only
            "acup/gunfeel/ClientPause", "pause read on the client",
            // MainHand: the client's options (the main hand) in its methods, called by the client's arms only
            "acup/gunfeel/GameMainHand", "options read on the client",
            // the render port's holder, called by Wiring only when client() (RenderPort26 behind it)
            "acup/gunfeel/RenderPortClient", "the render port, Wiring(true) only");

    // a narrower watch by the bytecode: what Wiring names (its constant pool), and what each of those names in turn
    @Test
    void wiringNamesNoNewClassOfTheClients() throws Exception {
        Path root = Path.of(Wiring.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Set<String> named = new TreeSet<>(), hits = new TreeSet<>();
        for (String c : classes(Files.readAllBytes(root.resolve("acup/gunfeel/Wiring.class")))) {
            if (!c.startsWith("acup/")) continue;
            named.add(c);
            Path f = root.resolve(c + ".class");
            if (!Files.exists(f)) continue;
            for (String x : classes(Files.readAllBytes(f)))
                for (String p : CLIENT) if (x.startsWith(p.replace('.', '/'))) hits.add(c);
        }
        assertTrue(named.contains("acup/gunfeel/RenderPortClient"), "Wiring names: " + named);
        hits.removeAll(CLIENT_NAMING.keySet());
        assertEquals(Set.of(), hits, "classes Wiring names that name the client's (a holder called on the client only, or CLIENT_NAMING)");
    }

    // the classes a class file names: its CONSTANT_Class entries and the types in its descriptors
    static Set<String> classes(byte[] b) {
        java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(b);
        in.position(8);
        int n = in.getShort() & 0xFFFF;
        String[] utf = new String[n];
        List<Integer> cls = new ArrayList<>(), desc = new ArrayList<>();
        for (int k = 1; k < n; k++) {
            int tag = in.get() & 0xFF;
            switch (tag) {
                case 1 -> {
                    byte[] s = new byte[in.getShort() & 0xFFFF];
                    in.get(s);
                    utf[k] = new String(s, java.nio.charset.StandardCharsets.UTF_8);
                }
                case 7 -> cls.add(in.getShort() & 0xFFFF);
                case 16 -> desc.add(in.getShort() & 0xFFFF);
                case 8, 19, 20 -> in.getShort();
                case 15 -> {
                    in.get();
                    in.getShort();
                }
                case 12 -> {
                    in.getShort();
                    desc.add(in.getShort() & 0xFFFF);
                }
                case 3, 4, 9, 10, 11, 17, 18 -> in.getInt();
                case 5, 6 -> {
                    in.getLong();
                    k++;
                }
                default -> throw new IllegalArgumentException("constant pool tag " + tag);
            }
        }
        Set<String> out = new TreeSet<>();
        for (int c : cls) out.add(utf[c].replaceAll("^\\[+L?|;$", ""));
        java.util.regex.Pattern type = java.util.regex.Pattern.compile("L([\\w/$]+);");
        for (int d : desc) {
            var m = type.matcher(utf[d]);
            while (m.find()) out.add(m.group(1));
        }
        return out;
    }
}
