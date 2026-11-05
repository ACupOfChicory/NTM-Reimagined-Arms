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
        try (URLClassLoader l = forgeLoader(true)) {
            String line = SignerFix.install(l, game);
            if (JAVA8) assertEquals("on, the signer check is off in this package for ModelLoaderRegistryR.class in StellarCore-1.6.7.jar", line);
            else assertEquals("not needed (Java " + System.getProperty("java.specification.version") + ")", line);
        }
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
        File forge = jar("forge.jar", true, SignerFix.DIR + "ModelLoaderRegistry.class");
        File other = jar("other.jar", false, SignerFix.DIR + "pipeline/Deep.class", SignerFix.DIR + "readme.txt", "net/minecraftforge/client/Near.class");
        File stellar = jar("stellar.jar", false, SignerFix.DIR + "ModelLoaderRegistryR.class");
        java.util.Set<File> jars = new java.util.LinkedHashSet<>(java.util.Arrays.asList(forge, other, stellar));
        assertEquals(Collections.singletonList("ModelLoaderRegistryR.class in stellar.jar"), SignerFix.foreign(jars, forge));
        assertEquals(List.of("ModelLoaderRegistry.class in forge.jar", "ModelLoaderRegistryR.class in stellar.jar"), SignerFix.foreign(jars, other));
    }

    @Test
    void aSignatureFileInMetaInfIsASignedJar() throws IOException {
        assertTrue(SignerFix.signed(jar("s.jar", true, "a/B.class")));
        assertFalse(SignerFix.signed(jar("u.jar", false, "a/B.class", "META-INF/MANIFEST.MF", "META-INF/sub/X.SF")));
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

    private File jar(String name, boolean signed, String... entries) throws IOException {
        File f = Files.createTempDirectory(tmp, "jar").resolve(name).toFile();
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            if (signed) {
                z.putNextEntry(new ZipEntry("META-INF/FORGE.SF"));
                z.write("Signature-Version: 1.0\n".getBytes(StandardCharsets.ISO_8859_1));
                z.putNextEntry(new ZipEntry("META-INF/FORGE.DSA"));
            }
            for (String e : entries) z.putNextEntry(new ZipEntry(e));
        }
        return f;
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
