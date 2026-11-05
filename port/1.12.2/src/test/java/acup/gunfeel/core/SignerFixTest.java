package acup.gunfeel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

// SignerFix: what Java does to an unsigned class in a signed package with and without it (Java 8, the game's Java),
// and when it is put in at all
class SignerFixTest {
    private static final boolean JAVA8 = "1.8".equals(System.getProperty("java.specification.version"));
    private static final Certificate CERT = new Fake();

    @TempDir
    Path tmp;

    // ---- Java's check itself, on the two kinds of class loader ----

    @Test
    void anUnsignedClassNextToSignedOnesIsRefusedWithoutTheFix() throws Exception {
        assumeTrue(JAVA8);
        for (Loader l : new Loader[] {new Plain(), new Parallel()}) {
            l.define(SignerFix.PKG + ".Forge1", CERT);
            SecurityException e = assertThrows(SecurityException.class, () -> l.define(SignerFix.PKG + ".ModelLoaderRegistryR", null), l.toString());
            assertTrue(e.getMessage().contains("signer information does not match"), e.getMessage());
        }
    }

    @Test
    void theFixLetsItInAndForgesOwnClassesAfterIt() throws Exception {
        assumeTrue(JAVA8);
        for (Loader l : new Loader[] {new Plain(), new Parallel()}) {
            l.define(SignerFix.PKG + ".Forge1", CERT);
            assertNull(SignerFix.forget((ClassLoader) l), l.toString());
            l.define(SignerFix.PKG + ".ModelLoaderRegistryR", null);
            l.define(SignerFix.PKG + ".Forge2", CERT);
            // a second time changes nothing
            assertNull(SignerFix.forget((ClassLoader) l));
            l.define(SignerFix.PKG + ".Other", null);
        }
    }

    @Test
    void anotherPackageKeepsTheCheck() throws Exception {
        assumeTrue(JAVA8);
        for (Loader l : new Loader[] {new Plain(), new Parallel()}) {
            l.define("net.minecraftforge.common.Signed", CERT);
            assertNull(SignerFix.forget((ClassLoader) l));
            assertThrows(SecurityException.class, () -> l.define("net.minecraftforge.common.Unsigned", null), l.toString());
            // and a package seen before the fix keeps its signer
            l.define("a.b.Signed", CERT);
        }
        Plain p = new Plain();
        p.define("a.b.Signed", CERT);
        assertNull(SignerFix.forget(p));
        assertThrows(SecurityException.class, () -> p.define("a.b.Unsigned", null));
    }

    // ---- when it is put in ----

    @Test
    void onAJava8GameWithASignedForgeAndStellarCoresClassItIsOn() throws Exception {
        File game = game(true, true);
        try (URLClassLoader l = forgeLoader(JAVA8)) {
            String line = SignerFix.install(l, game);
            if (JAVA8) assertEquals("on, the signer check is off in this package for ModelLoaderRegistryR.class in StellarCore-1.6.7.jar", line);
            else assertNull(line);
        }
    }

    // Cleanroom (Java 25): no line, and not even the loader is asked
    @Test
    void onANewerJavaItDoesNothingAndSaysNothing() throws Exception {
        assumeTrue(!JAVA8);
        File game = game(true, true);
        int[] asked = {0};
        ClassLoader l = new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                asked[0]++;
                return null;
            }
        };
        SignerFix.run(l, game);
        assertNull(SignerFix.install(l, game));
        assertEquals(0, asked[0]);
    }

    @Test
    void itStaysOutWhenNotNeeded() throws Exception {
        assumeTrue(JAVA8);
        try (URLClassLoader l = forgeLoader(false)) {
            assertEquals("not needed (Forge is not signed)", SignerFix.install(l, game(true, true)));
        }
        try (URLClassLoader l = forgeLoader(true)) {
            assertEquals("not needed (no class of another jar in the package)", SignerFix.install(l, game(false, true)));
        }
        try (URLClassLoader l = new URLClassLoader(new URL[0], null)) {
            assertTrue(SignerFix.install(l, game(true, true)).startsWith("not needed (no Forge jar"));
        }
    }

    @Test
    void theKeyTurnsItOff() throws Exception {
        assumeTrue(JAVA8);
        File game = game(true, true);
        Files.write(new File(game, "config/ntm-gunfeel.properties").toPath(), "forgeSignerFix = false\n".getBytes(StandardCharsets.ISO_8859_1));
        try (URLClassLoader l = forgeLoader(true)) {
            assertEquals("off (forgeSignerFix = false)", SignerFix.install(l, game));
        }
    }

    @Test
    void theKeyIsOnUnlessFalse() throws IOException {
        File game = tmp.resolve("k").toFile();
        assertTrue(SignerFix.key(game), "no file");
        new File(game, "config").mkdirs();
        Path f = game.toPath().resolve("config/ntm-gunfeel.properties");
        for (String[] c : new String[][] {{"forgeSignerFix=false", "false"}, {"forgeSignerFix = FALSE ", "false"}, {"forgeSignerFix=true", "true"},
                {"forgeSignerFix=no", "true"}, {"holeCache=false", "true"}, {"", "true"}}) {
            Files.write(f, c[0].getBytes(StandardCharsets.ISO_8859_1));
            assertEquals(Boolean.parseBoolean(c[1]), SignerFix.key(game), c[0]);
        }
    }

    @Test
    void onlyClassesRightInThePackageOfOtherJarsCount() throws IOException {
        File forge = jar("forge.jar", false, SignerFix.DIR + "ModelLoaderRegistry.class");
        File other = jar("other.jar", false, SignerFix.DIR + "pipeline/Deep.class", SignerFix.DIR + "readme.txt", "net/minecraftforge/client/Near.class");
        File stellar = jar("stellar.jar", false, SignerFix.DIR + "ModelLoaderRegistryR.class");
        java.util.Set<File> jars = new java.util.LinkedHashSet<>(java.util.Arrays.asList(forge, other, stellar));
        assertEquals(Collections.singletonList("ModelLoaderRegistryR.class in stellar.jar"), SignerFix.foreign(jars, forge));
        assertEquals(List.of("ModelLoaderRegistry.class in forge.jar", "ModelLoaderRegistryR.class in stellar.jar"), SignerFix.foreign(jars, other));
    }

    @Test
    void onlyAnEntryReallySignedCounts() throws IOException {
        assertTrue(SignerFix.signed(jar("s.jar", true, "a/B.class"), "a/B.class"));
        assertFalse(SignerFix.signed(jar("u.jar", false, "a/B.class"), "a/B.class"));
        assertFalse(SignerFix.signed(jar("n.jar", true, "a/B.class"), "a/C.class"), "no such entry");
        // signature files without a signer of the class: a repacked Forge
        File fake = Files.createTempDirectory(tmp, "jar").resolve("fake.jar").toFile();
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(fake))) {
            z.putNextEntry(new ZipEntry("META-INF/FORGE.SF"));
            z.write("Signature-Version: 1.0\n".getBytes(StandardCharsets.ISO_8859_1));
            z.putNextEntry(new ZipEntry("META-INF/FORGE.DSA"));
            z.putNextEntry(new ZipEntry("a/B.class"));
        }
        assertFalse(SignerFix.signed(fake, "a/B.class"));
    }

    @Test
    void theModsFolderAndItsVersionFolderAreSearched() throws IOException {
        File game = game(false, false);
        File a = new File(game, "mods/a.jar"), b = new File(game, "mods/1.12.2/b.jar");
        b.getParentFile().mkdirs();
        Files.write(a.toPath(), new byte[0]);
        Files.write(b.toPath(), new byte[0]);
        Files.write(new File(game, "mods/notes.txt").toPath(), new byte[0]);
        assertEquals(new java.util.HashSet<>(java.util.Arrays.asList(a.getAbsoluteFile(), b.getAbsoluteFile())),
                SignerFix.jars(new URLClassLoader(new URL[0], null), game));
    }

    // ---- the parts ----

    // a game folder with config/ and mods/, StellarCore's jar in mods/ when stellar
    private File game(boolean stellar, boolean config) throws IOException {
        File game = Files.createTempDirectory(tmp, "game").toFile();
        new File(game, "mods").mkdirs();
        if (config) new File(game, "config").mkdirs();
        if (stellar) Files.copy(jar("StellarCore-1.6.7.jar", false, SignerFix.DIR + "ModelLoaderRegistryR.class").toPath(), game.toPath().resolve("mods/StellarCore-1.6.7.jar"));
        return game;
    }

    private URLClassLoader forgeLoader(boolean signed) throws IOException {
        File forge = jar(signed ? "forge-signed.jar" : "forge-unsigned.jar", signed, SignerFix.DIR + "ModelLoaderRegistry.class");
        return new URLClassLoader(new URL[] {forge.toURI().toURL()}, null);
    }

    // a jar with these (empty) entries; signed by jarsigner as Forge's is when signed
    private File jar(String name, boolean signed, String... entries) throws IOException {
        File f = Files.createTempDirectory(tmp, "jar").resolve(name).toFile();
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            for (String e : entries) {
                z.putNextEntry(new ZipEntry(e));
                if (!e.endsWith("/")) z.write(1);
            }
        }
        if (signed) sign(f);
        return f;
    }

    private void sign(File jar) throws IOException {
        // a JRE without the JDK's tools (test25 may run on one): what needs a signed jar is skipped there
        assumeTrue(tool("keytool") != null && tool("jarsigner") != null, "no keytool or jarsigner");
        File store = tmp.resolve("keys.jks").toFile();
        if (!store.isFile()) exec(tool("keytool"), "-genkeypair", "-keystore", store.getPath(), "-storepass", "testpass", "-keypass", "testpass",
                "-alias", "forge", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=test", "-validity", "2");
        exec(tool("jarsigner"), "-keystore", store.getPath(), "-storepass", "testpass", "-keypass", "testpass", jar.getPath(), "forge");
    }

    // a JDK tool of the Java the tests run on (Java 8's java.home is the jre/ inside the JDK)
    private static String tool(String name) {
        File home = new File(System.getProperty("java.home"));
        for (File dir : new File[] {new File(home, "bin"), new File(home.getParentFile(), "bin")}) {
            File t = new File(dir, name);
            if (t.canExecute()) return t.getPath();
        }
        return null;
    }

    private static void exec(String... cmd) throws IOException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (p.waitFor() != 0) throw new IOException(String.join(" ", cmd) + ": " + out);
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }

    static byte[] bytes(String name) {
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name.replace('.', '/'), null, "java/lang/Object", null);
        w.visitEnd();
        return w.toByteArray();
    }

    interface Loader {
        void define(String name, Certificate cert) throws Exception;
    }

    // not parallel capable, as LaunchClassLoader: package2certs is a Hashtable
    static final class Plain extends ClassLoader implements Loader {
        Plain() {
            super(null);
        }

        public void define(String name, Certificate cert) throws Exception {
            byte[] b = bytes(name);
            defineClass(name, b, 0, b.length, new java.security.ProtectionDomain(new CodeSource(new URL("file:/x.jar"), cert == null ? null : new Certificate[] {cert}), null));
        }

        @Override
        public String toString() {
            return "a plain loader";
        }
    }

    // parallel capable: package2certs is a ConcurrentHashMap
    static final class Parallel extends ClassLoader implements Loader {
        static {
            registerAsParallelCapable();
        }

        Parallel() {
            super(null);
        }

        public void define(String name, Certificate cert) throws Exception {
            byte[] b = bytes(name);
            defineClass(name, b, 0, b.length, new java.security.ProtectionDomain(new CodeSource(new URL("file:/x.jar"), cert == null ? null : new Certificate[] {cert}), null));
        }

        @Override
        public String toString() {
            return "a parallel loader";
        }
    }

    // a signer for the tests: Java compares signers by their encoded form
    static final class Fake extends Certificate {
        Fake() {
            super("X.509");
        }

        public byte[] getEncoded() {
            return new byte[] {1, 2, 3};
        }

        public void verify(PublicKey key) {
        }

        public void verify(PublicKey key, String provider) {
        }

        public String toString() {
            return "fake";
        }

        public PublicKey getPublicKey() {
            return null;
        }
    }
}
