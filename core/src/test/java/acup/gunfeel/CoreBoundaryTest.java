package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The rule of core/: the code every Minecraft version shares names no class of the game, its loader, NTM or LWJGL.
 * The :core build already fails on such an import (none of them is on its class path); this test also catches a
 * name in a string (a class looked up by reflection) and says what the rule is. Comments may name them: they explain
 * what a version's adapter passes in. Core has no Stonecutter markers either ({@code //?}): what differs between
 * versions is the adapter's, in src/main/java.
 */
class CoreBoundaryTest {
    /** What core may not name: Minecraft, Mojang's libraries, NeoForge, NTM, LWJGL, the mixins. */
    static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(net\\.minecraft|com\\.mojang|net\\.neoforged|net\\.minecraftforge|cpw\\.mods|com\\.hbm|org\\.lwjgl|"
                    + "org\\.spongepowered|com\\.llamalad7)\\b");
    /** Stonecutter's version comments: {@code //? if ...}, {@code /*? ...}. */
    static final Pattern STONECUTTER = Pattern.compile("(?m)^\\s*(//\\?|/\\*\\?)");

    @Test
    void coreNamesNoGameClass() throws IOException {
        List<String> bad = new ArrayList<>();
        for (Path dir : List.of(Path.of("core/src/main/java"), Path.of("core/src/test/java"))) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.walk(dir)) {
                // this file names them in its own examples
                for (Path f : files.filter(p -> p.toString().endsWith(".java") && !p.endsWith("CoreBoundaryTest.java"))
                        .sorted().toList()) {
                    String text = Files.readString(f, StandardCharsets.UTF_8);
                    bad.addAll(problems(f.toString(), text));
                }
            }
        }
        assertTrue(bad.isEmpty(), "core/ is the code every version shares: no Minecraft, NeoForge, NTM or LWJGL class "
                + "and no Stonecutter marker in it; what needs the game stays in src/main/java and reaches core through "
                + "an interface core declares:\n" + String.join("\n", bad));
    }

    @Test
    void theScannerSeesCodeAndStringsButNotComments() {
        assertEquals(1, problems("A", "import net.minecraft.world.phys.Vec3;\nclass A {}").size());
        assertEquals(1, problems("A", "class A { Object o = Class.forName(\"com.hbm.items.ModItems\"); }").size());
        assertEquals(1, problems("A", "class A { net.neoforged.fml.loading.FMLPaths p; }").size());
        assertEquals(1, problems("A", "class A {\n    //? if >=26.2 {\n}").size());
        assertEquals(0, problems("A", "/** what {@code net.minecraft.world.phys.Vec3} gives */ class A {} // org.lwjgl.opengl.GL11").size());
        assertEquals(0, problems("A", "class A { String s = \"//\"; /* com.mojang */ char c = '\"'; }").size());
        assertEquals(0, problems("A", "class A { String s = \"\"\"\n  /* not a comment */ text\n  \"\"\"; }").size());
        assertEquals(1, problems("A", "class A { String s = \"\"\"\n  org.lwjgl.glfw.GLFW\n  \"\"\"; }").size());
    }

    /** Each forbidden name in code or strings of one file, and each Stonecutter marker, as "file:line: what". */
    static List<String> problems(String file, String text) {
        List<String> out = new ArrayList<>();
        String code = withoutComments(text);
        Matcher m = FORBIDDEN.matcher(code);
        while (m.find()) out.add(file + ":" + line(code, m.start()) + ": " + m.group(1));
        Matcher s = STONECUTTER.matcher(text);
        while (s.find()) out.add(file + ":" + line(text, s.start()) + ": Stonecutter marker");
        return out;
    }

    private static int line(String text, int at) {
        int n = 1;
        for (int i = 0; i < at; i++) if (text.charAt(i) == '\n') n++;
        return n;
    }

    /** The text with every comment blanked out (line breaks kept, so the line numbers stay); strings are kept. */
    static String withoutComments(String t) {
        StringBuilder b = new StringBuilder(t.length());
        int i = 0, n = t.length();
        while (i < n) {
            char c = t.charAt(i);
            if (c == '/' && i + 1 < n && t.charAt(i + 1) == '/') {
                while (i < n && t.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && t.charAt(i + 1) == '*') {
                i += 2;
                while (i < n && !(t.charAt(i) == '*' && i + 1 < n && t.charAt(i + 1) == '/')) {
                    if (t.charAt(i) == '\n') b.append('\n');
                    i++;
                }
                i += 2;
            } else if (t.startsWith("\"\"\"", i)) {
                int end = t.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                b.append(t, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && t.charAt(j) != c && t.charAt(j) != '\n') j += t.charAt(j) == '\\' ? 2 : 1;
                j = Math.min(n, j + 1);
                b.append(t, i, j);
                i = j;
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString();
    }
}
