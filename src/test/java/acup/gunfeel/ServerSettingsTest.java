package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

// The server's settings sent to the players (ServerSettings): what goes in comes out, every rule kind is either the
// server's or the client's, and the packet stays under the client-bound limit.
class ServerSettingsTest {
    @Test
    void whatGoesInComesOut() throws IOException {
        Map<String, String> server = new TreeMap<>(Map.of("bulletSpeed", "1.6", "hbm:gun_maresleg.reaimReload", "0.4", "ключ", "значение"));
        Map<String, String> shapes = new TreeMap<>(Map.of("somemod:railing[facing=north]", "z" + "A".repeat(40)));
        ServerSettings.Snapshot s = new ServerSettings.Snapshot("path somemod:railing fence\nbreak #c:glass_blocks hp 5\n", server, shapes);
        assertEquals(s, ServerSettings.decode(ServerSettings.encode(s)));
        ServerSettings.Snapshot empty = new ServerSettings.Snapshot("", Map.of(), Map.of());
        assertEquals(empty, ServerSettings.decode(ServerSettings.encode(empty)));
    }

    // every kind the rules know (the file's help and the mod's own lines) is the server's or the client's, not both
    @Test
    void everyRuleKindHasASide() throws IOException {
        Set<String> kinds = new HashSet<>();
        String src = Files.readString(Path.of("src/main/java/acup/gunfeel/BulletRules.java"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("(?m)^\\s*# ([a-z]+) (<blocks>|<who>|sound=)").matcher(src.substring(src.indexOf("HELP = \"\"\"")));
        while (m.find()) kinds.add(m.group(1));
        for (String f : new String[] {"src/main/resources/ntmgunfeel-bullets.txt", "src/main/resources/ntmgunfeel-materials.txt"}) {
            for (String line : Files.readAllLines(Path.of(f), StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (!t.isEmpty() && !t.startsWith("#")) kinds.add(t.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT));
            }
        }
        assertTrue(kinds.size() >= 11, "kinds found: " + kinds);
        for (String k : kinds) {
            boolean server = ServerSettings.SERVER_KINDS.contains(k), client = ServerSettings.CLIENT_KINDS.contains(k);
            assertTrue(server != client, k + ": server " + server + ", client " + client);
        }
        assertEquals(Set.of(), intersect(ServerSettings.SERVER_KINDS, ServerSettings.CLIENT_KINDS));
    }

    private static Set<String> intersect(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.retainAll(b);
        return out;
    }

    // too many shapes for one packet: they are left out, the rules and numbers still go
    @Test
    void tooManyShapesAreLeftOut() throws IOException {
        Random r = new Random(1);
        Map<String, String> shapes = new TreeMap<>();
        byte[] noise = new byte[600];
        for (int i = 0; i < 3000; i++) {
            r.nextBytes(noise);
            shapes.put("mod:block_" + i, "z" + java.util.Base64.getEncoder().encodeToString(noise));
        }
        ServerSettings.Snapshot s = new ServerSettings.Snapshot("path mod:block_1 model", Map.of("bulletSpeed", "2"), shapes);
        byte[] data = ServerSettings.encodeFitting(s);
        assertTrue(data.length <= ServerSettings.MAX_BYTES, "packed " + data.length);
        ServerSettings.Snapshot got = ServerSettings.decode(data);
        assertEquals(Map.of(), got.shapes());
        assertEquals(s.bullets(), got.bullets());
        assertEquals(s.server(), got.server());
        // a few fit as they are
        Map<String, String> few = new TreeMap<>();
        shapes.entrySet().stream().limit(10).forEach(e -> few.put(e.getKey(), e.getValue()));
        ServerSettings.Snapshot small = new ServerSettings.Snapshot("", Map.of(), few);
        assertEquals(small, ServerSettings.decode(ServerSettings.encodeFitting(small)));
    }

    // the shapes go packed as in the shapes file and come back with the same cells, a shell's mark too
    @Test
    void packedShapesComeBack() {
        long[] bits = new long[65];
        Random r = new Random(2);
        for (int i = 0; i < 64; i++) bits[i] = r.nextLong();
        assertArrayEquals(bits, PassableClip.unpack(PassableClip.pack(bits)));
        bits[64] = 1;
        assertArrayEquals(bits, PassableClip.unpack(PassableClip.pack(bits)));
    }
}
