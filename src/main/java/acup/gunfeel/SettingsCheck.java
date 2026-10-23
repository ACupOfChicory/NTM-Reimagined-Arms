package acup.gunfeel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

// The server's settings for the players (ServerSettings), checked on a real dedicated server without a client (a dev
// run only: ./gradlew runCheckSettings -PnoAssets). -Dntmgunfeel.checkSettings=<file>: once the mods and the tags are
// loaded, before any world, this game plays both sides. As the server it writes its files and packs the payload,
// which goes through the network codec. As a player on another game's server it then has other files of its own,
// takes the payload and asks the rules about real blocks; then it leaves the server and asks again. One line per check
// goes to the file, and the server stops (exit 1 if a check failed). Without the property nothing is registered.
// Not covered (needs a real client): the payload going over the network, that single player and a LAN host leave it
// alone, and that leaving the server drops it (ServerSettingsClient).
public final class SettingsCheck {
    public static final String PROPERTY = "ntmgunfeel.checkSettings";

    private SettingsCheck() {
    }

    public static void registerIfAsked() {
        String file = System.getProperty(PROPERTY);
        if (file == null || file.isBlank()) return;
        NeoForge.EVENT_BUS.addListener(ServerAboutToStartEvent.class, e -> {
            List<String> out = new ArrayList<>();
            int code;
            try {
                code = run(out) ? 0 : 1;
            } catch (Throwable t) {
                out.add("FAIL crashed: " + t);
                for (StackTraceElement s : t.getStackTrace()) out.add("    at " + s);
                code = 1;
            }
            try {
                Path f = Path.of(file);
                Files.createDirectories(f.getParent());
                Files.write(f, out, StandardCharsets.UTF_8);
            } catch (Exception ex) {
                ex.printStackTrace();
                code = 1;
            }
            out.forEach(System.out::println);
            Runtime.getRuntime().halt(code);
        });
    }

    private static List<String> lines;
    private static boolean ok;

    private static void check(String name, Object want, Object got) {
        boolean pass = Objects.equals(want, got);
        ok &= pass;
        lines.add((pass ? "OK   " : "FAIL ") + name + (pass ? "" : ": want " + want + ", got " + got));
    }

    // the server's files and the player's differ in every line the check looks at
    static final String SERVER_RULES = """
            path minecraft:oak_fence through
            break minecraft:bookshelf instant
            path minecraft:oak_planks model
            hole minecraft:iron_bars plain
            """;
    static final String PLAYER_RULES = """
            path minecraft:oak_fence hitbox
            break minecraft:bookshelf never
            hole minecraft:iron_bars glass
            """;

    private static boolean run(List<String> out) throws Exception {
        lines = out;
        ok = true;
        Path dir = FMLPaths.CONFIGDIR.get();
        Path rules = dir.resolve(BulletRules.FILE), server = dir.resolve(ReaimTimes.FILE);
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState(), shelf = Blocks.BOOKSHELF.defaultBlockState();
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState(), bars = Blocks.IRON_BARS.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();

        // ---- the server: its files, a shape its own client made (a LAN host), the payload
        Files.writeString(rules, SERVER_RULES);
        Files.writeString(server, "bulletSpeed = 2.5\ncameraScaleMin = 0.4\n");
        long[] cells = new long[65];
        for (int i = 0; i < 64; i += 3) cells[i] = 0x0F0F0F0F0F0F0F0FL;
        PassableClip.putShapes(Map.of("minecraft:oak_planks", cells));
        Thread.sleep(1100); // the files are looked at once a second
        check("server: its own path rule", "through", BulletRules.path(fence));
        check("server: its own bulletSpeed", 2.5F, ReaimTimes.number("bulletSpeed", 1.6F));

        ServerSettingsPayload sent = ServerSettingsPayload.current();
        ByteBuf buf = Unpooled.buffer();
        ServerSettingsPayload.CODEC.encode(buf, sent);
        ServerSettingsPayload got = ServerSettingsPayload.CODEC.decode(buf);
        check("codec: every byte back", java.util.Arrays.toString(sent.data()), java.util.Arrays.toString(got.data()));
        check("codec: nothing left over", 0, buf.readableBytes());
        lines.add("     payload " + sent.data().length + " bytes");
        ServerSettings.Snapshot s = ServerSettings.decode(got.data());
        check("payload: the rules file", SERVER_RULES, s.bullets());
        check("payload: bulletSpeed", "2.5", s.server().get("bulletSpeed"));
        check("payload: cameraScaleMin", "0.4", s.server().get("cameraScaleMin"));
        check("payload: the made shape", PassableClip.pack(cells), s.shapes().get("minecraft:oak_planks"));
        check("payload: no shape the jar has", false, s.shapes().keySet().stream().anyMatch(k -> k.startsWith("~fence[")));

        // ---- the player, on another game's server: files of its own, then the server's settings
        Files.writeString(rules, PLAYER_RULES);
        Files.writeString(server, "bulletSpeed = 1.0\n");
        Thread.sleep(1100);
        check("files changed: packed anew", false, ServerSettingsPayload.current() == sent);
        ServerSettings.useRemote(s);
        check("remote: path is the server's", "through", BulletRules.path(fence));
        check("remote: through is the server's", true, BulletRules.through(fence));
        check("remote: break is the server's (instant)", 1, mode(shelf));
        check("remote: the server's model rule", "model", BulletRules.path(planks));
        check("remote: the server's shape", PassableClip.pack(cells), packed(PassableClip.shape(planks)));
        check("remote: hole is the player's", "glass", BulletRules.hole(bars)[0]);
        check("remote: material still the mod's", Materials.Material.GLASS, BulletRules.material(Blocks.GLASS.defaultBlockState()).material());
        check("remote: a fence's cells still the jar's", true, PassableClip.byKey(PassableClip.familyKey("fence", Map.of())) != null);
        check("remote: bulletSpeed is the server's", 2.5F, ReaimTimes.number("bulletSpeed", 1.6F));
        check("remote: cameraScaleMin is the server's", 0.4F, ReaimTimes.number("cameraScaleMin", 0F));
        check("remote: a reaim time from the jar", 0.6F, ReaimTimes.seconds("hbm:gun_double_barrel", "inspect"));
        // a shape this client makes meanwhile waits: the server goes by its own
        PassableClip.putShapes(Map.of("minecraft:dirt", cells));
        check("remote: a shape made now is not used", null, packed(PassableClip.shape(dirt)));

        // ---- the player leaves: its own files again
        ServerSettings.useRemote(null);
        Thread.sleep(1100);
        check("left: path is the player's", "hitbox", BulletRules.path(fence));
        check("left: through is the player's", false, BulletRules.through(fence));
        check("left: break is the player's (never: no rule)", null, mode(shelf));
        check("left: no model rule", null, BulletRules.path(planks));
        check("left: hole is the player's", "glass", BulletRules.hole(bars)[0]);
        check("left: bulletSpeed is the player's", 1.0F, ReaimTimes.number("bulletSpeed", 1.6F));
        check("left: cameraScaleMin from the jar", 0F, ReaimTimes.number("cameraScaleMin", 0F));
        check("left: the shape made meanwhile is used", PassableClip.pack(cells), packed(PassableClip.shape(dirt)));
        lines.add(ok ? "ALL OK" : "SOME FAILED");
        return ok;
    }

    private static Object mode(BlockState state) {
        BulletRules.Break b = BulletRules.breakRule(state);
        return b == null ? null : b.mode();
    }

    private static Object packed(long[] bits) {
        return bits == null ? null : PassableClip.pack(bits);
    }
}
