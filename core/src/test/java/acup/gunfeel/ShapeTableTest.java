package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// The bullet shapes' table without the game: its file's lines, the config folder's file read once over the jar's
// (the mod's resource, on core's tests' classpath too), this client's shapes on top, a server's in their place
class ShapeTableTest {
    static long[] cells(int... set) {
        long[] bits = new long[65];
        for (int i : set) bits[i >> 6] |= 1L << (i & 63);
        return bits;
    }

    static String hex(long[] bits) {
        StringBuilder b = new StringBuilder();
        for (int j = 0; j < 64; j++) b.append(String.format("%016x", bits[j]));
        return b.toString();
    }

    // "key = <shape>", "key = @n" with "@n = <shape>", a shell's "!", the deflated "z"; a bad shape left out
    @Test
    void theFilesLines() throws IOException {
        long[] a = cells(0, 100, 4095), b = cells(7), shell = cells(1, 2);
        shell[64] = 1L;
        // a key's ":" escaped, as Properties.store writes it (the file's own are)
        String text = "mod\\:a = " + hex(a) + "\nmod\\:b = @1\nmod\\:c = @1\n@1 = " + ShapeCells.pack(b) + "\nmod\\:shell = !" + hex(shell)
                + "\nmod\\:bad = 12ab\nmod\\:none = @9\n";
        Map<String, long[]> into = new HashMap<>();
        ShapeTable.read(new StringReader(text), into);
        assertEquals(java.util.Set.of("mod:a", "mod:b", "mod:c", "mod:shell"), into.keySet());
        assertArrayEquals(a, into.get("mod:a"));
        assertArrayEquals(b, into.get("mod:b"));
        assertArrayEquals(b, into.get("mod:c"));
        assertArrayEquals(shell, into.get("mod:shell"));
        assertTrue(ShapeCells.shell(into.get("mod:shell")));
    }

    // the whole way: loaded once from the folder, put on top, a server's in place and back; one test, the table is the JVM's
    @Test
    void loadPutRemote(@TempDir Path config) throws IOException {
        long[] a = cells(3), b = cells(5), c = cells(9);
        Files.writeString(config.resolve(ShapeTable.FILE), "mod\\:a = " + hex(a) + "\nmod\\:b = " + hex(b) + "\n", StandardCharsets.UTF_8);
        Map<String, long[]> jar = new HashMap<>();
        try (var in = ShapeTable.class.getResourceAsStream("/ntmgunfeel-bullet-shapes.properties")) {
            if (in != null) ShapeTable.read(new java.io.InputStreamReader(in, StandardCharsets.UTF_8), jar);
        }
        ShapeTable.load0(config);
        Map<String, long[]> all = ShapeTable.all();
        assertEquals(jar.size() + 2, all.size());
        assertTrue(all.keySet().containsAll(jar.keySet()));
        // sorted by key (TreeMap)
        assertEquals(new java.util.TreeSet<>(all.keySet()).first(), all.keySet().iterator().next());
        assertArrayEquals(a, ShapeTable.byKey("mod:a"));
        assertArrayEquals(b, ShapeTable.localByKey("mod:b"));
        assertNull(ShapeTable.byKey("mod:c"));
        // read once: another folder changes nothing, nor does load()
        ShapeTable.load0(Path.of("/nonexistent"));
        ShapeTable.load();
        assertEquals(jar.size() + 2, ShapeTable.all().size());
        // what the jar has not: the config's both, packed (the jar's own are the jar's)
        Map<String, String> extra = ShapeTable.extra();
        assertEquals(java.util.Set.of("mod:a", "mod:b"), extra.keySet());
        assertArrayEquals(a, ShapeCells.unpack(extra.get("mod:a")));
        // this client's on top: a new version, the old map left whole
        int v = ShapeTable.localVersion();
        Map<String, long[]> before = ShapeTable.shapes();
        ShapeTable.put(Map.of("mod:c", c, "mod:a", b));
        assertEquals(v + 1, ShapeTable.localVersion());
        assertArrayEquals(b, ShapeTable.byKey("mod:a"));
        assertArrayEquals(c, ShapeTable.byKey("mod:c"));
        assertArrayEquals(a, before.get("mod:a"));
        assertEquals(jar.size() + 2, before.size());
        // a server's: the jar's and its own, not this game's; this game's still there for its own server
        ShapeTable.useRemote(Map.of("srv:x", ShapeCells.pack(c), "srv:bad", "nope"));
        assertArrayEquals(c, ShapeTable.byKey("srv:x"));
        assertNull(ShapeTable.byKey("mod:a"));
        assertNull(ShapeTable.byKey("srv:bad"));
        String fence = FenceFamilies.familyKey("fence", Map.of());
        if (jar.containsKey(fence)) assertArrayEquals(jar.get(fence), ShapeTable.byKey(fence));
        assertArrayEquals(b, ShapeTable.localByKey("mod:a"));
        assertEquals(v + 1, ShapeTable.localVersion());
        ShapeTable.useRemote(null);
        assertArrayEquals(b, ShapeTable.byKey("mod:a"));
        assertSame(ShapeTable.shapes(), ShapeTable.shapes());
    }
}
