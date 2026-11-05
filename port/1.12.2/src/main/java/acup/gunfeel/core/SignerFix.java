package acup.gunfeel.core;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.logging.log4j.LogManager;

/**
 * Lets a mod's unsigned class into Forge's signed package net.minecraftforge.client.model. StellarCore 1.6.x ships
 * net.minecraftforge.client.model.ModelLoaderRegistryR (a bridge to ModelLoaderRegistry's package-private methods)
 * and loads it from its ParallelModelLoader; the Forge 1.12.2 jar is signed, and Java refuses a class in a package
 * whose other classes have another signer ("signer information does not match"), so the game stops loading models.
 *
 * <p>Java keeps one signer per package in ClassLoader.package2certs. That map of the game's class loader is swapped
 * for one that forgets this package and only it, so the check is skipped there. It is done only when it is needed:
 * Java 8 (the field is closed on 9+, and Cleanroom's Forge is not signed), a signed Forge jar, and a class of another
 * jar in that package; and the key forgeSignerFix (config/ntm-gunfeel.properties, on by default) can turn it off. One
 * line in the mod's log says what was done or why not.
 */
public final class SignerFix {
    static final String PKG = "net.minecraftforge.client.model";
    static final String DIR = "net/minecraftforge/client/model/";
    public static final String KEY = "forgeSignerFix";
    private static final String LOG = "ntm-gunfeel";

    private SignerFix() {
    }

    // from ArmsCore.injectData: before any game class is loaded, after the mod's log is set up
    static void run(ClassLoader loader, File game) {
        String line;
        try {
            line = install(loader, game);
        } catch (Throwable t) {
            line = "could not turn the check off: " + t;
        }
        LogManager.getLogger(LOG).info("Forge signer fix ({}): {}", PKG, line);
    }

    static String install(ClassLoader loader, File game) throws IOException {
        String java = System.getProperty("java.specification.version");
        if (!"1.8".equals(java)) return "not needed (Java " + java + ")";
        if (!key(game)) return "off (" + KEY + " = false)";
        URL own = loader.getResource(DIR + "ModelLoaderRegistry.class");
        File forge = jarOf(own);
        if (forge == null) return "not needed (no Forge jar: " + own + ")";
        if (!signed(forge)) return "not needed (Forge is not signed)";
        List<String> foreign = foreign(jars(loader, game), forge);
        if (foreign.isEmpty()) return "not needed (no class of another jar in the package)";
        String why = forget(loader);
        return why != null ? "not done: " + why : "on, the signer check is off in this package for " + String.join(", ", foreign);
    }

    // the key, read from the file itself: the mod's Config is not loaded this early
    static boolean key(File game) {
        File f = new File(new File(game, "config"), "ntm-gunfeel.properties");
        if (!f.isFile()) return true;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.ISO_8859_1)) {
            p.load(r);
        } catch (IOException | IllegalArgumentException e) {
            return true;
        }
        return !"false".equalsIgnoreCase(p.getProperty(KEY, "true").trim());
    }

    // the jar file a jar: URL is in, or null
    static File jarOf(URL url) throws IOException {
        if (url == null || !"jar".equals(url.getProtocol())) return null;
        URLConnection c = url.openConnection();
        if (!(c instanceof JarURLConnection)) return null;
        URL file = ((JarURLConnection) c).getJarFileURL();
        if (!"file".equals(file.getProtocol())) return null;
        try {
            return new File(file.toURI());
        } catch (java.net.URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    // a jar signed as Forge's is: a signature file in META-INF
    static boolean signed(File jar) throws IOException {
        try (ZipFile z = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> e = z.entries();
            while (e.hasMoreElements()) {
                String n = e.nextElement().getName().toUpperCase(java.util.Locale.ROOT);
                if (n.startsWith("META-INF/") && n.indexOf('/', 9) < 0 && (n.endsWith(".SF") || n.endsWith(".DSA") || n.endsWith(".RSA") || n.endsWith(".EC")))
                    return true;
            }
        }
        return false;
    }

    // the jars the game may load classes from: the class loader's sources and mods/ (and mods/1.12.2/), whose mods
    // are not all added yet when the coremods start
    static Set<File> jars(ClassLoader loader, File game) {
        Set<File> out = new LinkedHashSet<>();
        if (loader instanceof java.net.URLClassLoader) {
            for (URL u : ((java.net.URLClassLoader) loader).getURLs()) {
                if (!"file".equals(u.getProtocol())) continue;
                try {
                    File f = new File(u.toURI());
                    if (f.isFile()) out.add(f.getAbsoluteFile());
                } catch (java.net.URISyntaxException | IllegalArgumentException e) {
                    // not a plain file
                }
            }
        }
        for (File dir : new File[] {new File(game, "mods"), new File(new File(game, "mods"), "1.12.2")}) {
            File[] list = dir.listFiles();
            if (list == null) continue;
            for (File f : list) {
                String n = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (f.isFile() && (n.endsWith(".jar") || n.endsWith(".zip"))) out.add(f.getAbsoluteFile());
            }
        }
        return out;
    }

    // "Name.class in file.jar" for each class right in the package found in another jar than Forge's
    static List<String> foreign(Set<File> jars, File forge) {
        List<String> out = new ArrayList<>();
        File self = forge.getAbsoluteFile();
        for (File j : jars) {
            if (j.equals(self)) continue;
            try (ZipFile z = new ZipFile(j)) {
                Enumeration<? extends ZipEntry> e = z.entries();
                while (e.hasMoreElements()) {
                    String n = e.nextElement().getName();
                    if (n.startsWith(DIR) && n.endsWith(".class") && n.indexOf('/', DIR.length()) < 0)
                        out.add(n.substring(DIR.length()) + " in " + j.getName());
                }
            } catch (IOException e) {
                // not a zip: not loaded from either
            }
        }
        return out;
    }

    // swaps the loader's package2certs for one that forgets PKG; null when done, else why not
    static String forget(ClassLoader loader) {
        Field f;
        try {
            f = ClassLoader.class.getDeclaredField("package2certs");
            f.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "no ClassLoader.package2certs (" + e + ")";
        }
        try {
            // checkCerts reads and fills the map under the loader's lock when it is not parallel capable
            synchronized (loader) {
                Object old = f.get(loader);
                Map<String, Certificate[]> map;
                if (old instanceof Forgetting) return null;
                if (old instanceof ConcurrentHashMap) map = new ForgettingConcurrent();
                else if (old instanceof Hashtable) map = new ForgettingTable();
                else return "package2certs is a " + (old == null ? "null" : old.getClass().getName());
                @SuppressWarnings("unchecked")
                Map<String, Certificate[]> was = (Map<String, Certificate[]>) old;
                map.putAll(was);
                map.remove(PKG);
                f.set(loader, map);
            }
        } catch (IllegalAccessException | RuntimeException e) {
            return "package2certs not set (" + e + ")";
        }
        return null;
    }

    interface Forgetting {
    }

    // a loader that is not parallel capable (LaunchClassLoader): checkCerts does get, then put when get gave null
    static final class ForgettingTable extends Hashtable<String, Certificate[]> implements Forgetting {
        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Certificate[] get(Object key) {
            return PKG.equals(key) ? null : super.get(key);
        }
    }

    // a parallel capable loader: checkCerts does putIfAbsent and compares with what was there
    static final class ForgettingConcurrent extends ConcurrentHashMap<String, Certificate[]> implements Forgetting {
        private static final long serialVersionUID = 1L;

        @Override
        public Certificate[] get(Object key) {
            return PKG.equals(key) ? null : super.get(key);
        }

        @Override
        public Certificate[] putIfAbsent(String key, Certificate[] value) {
            return PKG.equals(key) ? null : super.putIfAbsent(key, value);
        }
    }
}
