package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;

// Fences, fence gates and walls (tools/fence_shapes.py): a bullet goes through the gaps (пролёт) and meets only the
// rails, posts and the wall itself, not their 1.5 block high collision box.
class FenceShapesTest {
    static final Path FILE = Path.of("src/main/resources/ntmgunfeel-bullet-shapes.properties");

    static Map<String, long[]> families() throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        Map<String, long[]> out = new HashMap<>();
        for (String k : p.stringPropertyNames()) {
            if (!k.startsWith("~")) continue;
            long[] bits = PassableClip.unpack(p.getProperty(k).trim());
            assertNotNull(bits, k);
            out.put(k, bits);
        }
        return out;
    }

    static long[] get(Map<String, long[]> all, String family, String... kv) {
        Map<String, String> props = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) props.put(kv[i], kv[i + 1]);
        String key = PassableClip.familyKey(family, props);
        long[] bits = all.get(key);
        assertNotNull(bits, "no " + key);
        return bits;
    }

    static boolean at(long[] bits, int x, int y, int z) {
        return PassableClip.cell(bits, x, y, z);
    }

    @Test
    void everyStateOfEveryFamily() throws IOException {
        Map<String, Integer> count = new HashMap<>();
        for (String k : families().keySet()) count.merge(k.substring(1, k.indexOf('[')), 1, Integer::sum);
        assertEquals(Map.of("fence", 16, "fence_gate", 16, "wall", 162, "bamboo_fence", 16, "bamboo_fence_gate", 16), count);
    }

    @Test
    void fenceRailsStopTheGapsDoNot() throws IOException {
        long[] f = get(families(), "fence", "east", "true", "north", "false", "south", "false", "west", "false");
        assertTrue(at(f, 8, 3, 8), "post");
        assertTrue(at(f, 12, 7, 8), "lower rail");
        assertTrue(at(f, 12, 13, 8), "upper rail");
        assertFalse(at(f, 12, 10, 8), "between the rails");
        assertFalse(at(f, 12, 3, 8), "under the lower rail");
        assertFalse(at(f, 8, 7, 12), "no rail to the south");
    }

    @Test
    void gateBarsAndGaps() throws IOException {
        Map<String, long[]> all = families();
        long[] north = get(all, "fence_gate", "facing", "north", "in_wall", "false", "open", "false");
        assertTrue(at(north, 4, 7, 8), "lower bar");
        assertFalse(at(north, 4, 10, 8), "between the bars");
        assertFalse(at(north, 4, 2, 8), "under the gate");
        long[] east = get(all, "fence_gate", "facing", "east", "in_wall", "false", "open", "false");
        assertTrue(at(east, 8, 7, 4), "lower bar, turned");
        assertFalse(at(east, 4, 7, 8), "nothing across, turned");
        // open: the leaves swung to the sides, the middle is free
        long[] open = get(all, "fence_gate", "facing", "north", "in_wall", "false", "open", "true");
        assertFalse(at(open, 8, 7, 8), "open gate's middle");
    }

    @Test
    void wallHeights() throws IOException {
        Map<String, long[]> all = families();
        long[] low = get(all, "wall", "north", "low", "east", "none", "south", "none", "west", "none", "up", "false");
        assertTrue(at(low, 8, 7, 3), "low wall");
        assertFalse(at(low, 8, 15, 3), "above the low wall (14 of 16)");
        assertFalse(at(low, 2, 7, 3), "beside the wall");
        long[] tall = get(all, "wall", "north", "tall", "east", "none", "south", "none", "west", "none", "up", "false");
        assertTrue(at(tall, 8, 15, 3), "tall wall");
        long[] post = get(all, "wall", "north", "none", "east", "none", "south", "none", "west", "none", "up", "true");
        assertTrue(at(post, 8, 15, 8), "post");
    }
}
