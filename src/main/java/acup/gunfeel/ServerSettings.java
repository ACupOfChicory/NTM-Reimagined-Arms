package acup.gunfeel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.jspecify.annotations.Nullable;

// What the server decides, sent to every player who joins and again whenever it changes, so a player's client draws
// what the server does:
//  - the server's own bullet rules (config/ntm-gunfeel-bullets.txt), of which the client takes the kinds the server
//    acts on (break, material, path, ricochet, entity, kill) and keeps its own for what only it draws (hit, decal,
//    hole, whizz, fly);
//  - ntm-gunfeel-server.properties (bullet speed, spread, reaim times, the camera recoil minimum);
//  - the bullet shapes the server has beyond the mod jar's (config/ntm-gunfeel-bullet-shapes.properties, and on a LAN
//    host the ones its own client made).
// The client keeps them in memory while connected and forgets them on leaving: nothing is written, so another server's
// settings and the player's own files are never touched. In single player and on a LAN host the server is this game
// itself and reads the same files: the client uses its files as they are (see ServerSettingsClient).
public final class ServerSettings {
    // the server's settings as received; null: this game's own files count
    public record Snapshot(String bullets, Map<String, String> server, Map<String, String> shapes) {
    }

    // rule kinds the server acts on; every other kind is what the client alone draws
    public static final Set<String> SERVER_KINDS = Set.of("break", "material", "path", "ricochet", "entity", "kill");
    public static final Set<String> CLIENT_KINDS = Set.of("hit", "decal", "hole", "whizz", "fly");
    // past this the shapes are left out and the client goes by the mod jar's (a client-bound packet holds 1 MiB)
    static final int MAX_BYTES = 1_000_000;

    private static volatile @Nullable Snapshot remote;

    private ServerSettings() {
    }

    public static boolean serverKind(String kind) {
        return SERVER_KINDS.contains(kind);
    }

    public static @Nullable Snapshot remote() {
        return remote;
    }

    // the client joined another game's server (null: left it, or this game is the server)
    public static void useRemote(@Nullable Snapshot s) {
        remote = s;
        PassableClip.useRemoteShapes(s == null ? null : s.shapes());
    }

    // ---- the payload: gzip of the three parts

    public static byte[] encode(Snapshot s) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            string(out, s.bullets());
            map(out, s.server());
            map(out, s.shapes());
        }
        return bytes.toByteArray();
    }

    public static Snapshot decode(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
            String bullets = string(in);
            Map<String, String> server = map(in);
            Map<String, String> shapes = map(in);
            return new Snapshot(bullets, server, shapes);
        }
    }

    // the snapshot packed, leaving the shapes out when all together would not fit one packet
    public static byte[] encodeFitting(Snapshot s) throws IOException {
        byte[] all = encode(s);
        if (all.length <= MAX_BYTES || s.shapes().isEmpty()) return all;
        org.slf4j.LoggerFactory.getLogger("ntm-gunfeel").warn("The server's bullet shapes are too many to send ({} bytes): players go by the mod's own", all.length);
        return encode(new Snapshot(s.bullets(), s.server(), Map.of()));
    }

    private static void string(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String string(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 64 * MAX_BYTES) throw new IOException("bad length " + n);
        return new String(in.readNBytes(n), StandardCharsets.UTF_8);
    }

    private static void map(DataOutputStream out, Map<String, String> m) throws IOException {
        out.writeInt(m.size());
        for (Map.Entry<String, String> e : m.entrySet()) {
            string(out, e.getKey());
            string(out, e.getValue());
        }
    }

    private static Map<String, String> map(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 16 * MAX_BYTES) throw new IOException("bad count " + n);
        Map<String, String> m = new TreeMap<>();
        for (int i = 0; i < n; i++) m.put(string(in), string(in));
        return m;
    }
}
