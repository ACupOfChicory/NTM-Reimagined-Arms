package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import acup.gunfeel.ai.AiSpawn;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

// The client's frame under fire (runFireStand, -PfireStand=<cases>): the player in survival, healed every tick, in a
// set of armour, N of NTM's FBI agents in a team of their own on an arc ahead of it, shooting at it (fire), at a
// target beside it (miss) or not at all (idle: the player in their team). Each case: the arena cleared, the agents
// spawned, 8 s to settle, 20 s measured: the frame (FPS, mean, p50, p95, p99, 1 % low), the client's tick, the
// server's tick, the packets the client takes by kind, the player's hurts, the mod's own counters (particles, holes,
// tracers). Cases: <armour>:<n>:<fire|miss|idle>[:<key>=<value>,...], keys of the client's settings (Config's fields:
// bulletFx, bulletHoles, tracers, cameraShake, suppressionDarken, suppressionMuffle, aiVoice, hitVolume, cornerHud)
// or the server's (s.<key>, the server settings' file). A row each to notes/perf/fire-stand/<label>.tsv, packets to
// <label>.packets.tsv; the case's window in epoch ms, for the flight recording.
final class FireStand {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.fireStand");
    private static final String CASES_PROP = System.getProperty("ntmgunfeel.fireStandCases", "dns:32:fire");
    private static final Path OUT = Path.of(System.getProperty("ntmgunfeel.fireStandFile", "fire-stand.tsv"));
    private static final BlockPos O = new BlockPos(0, 100, 0);
    private static final int SETTLE = 160, MEASURE = 400;
    private static final List<String> ROWS = new ArrayList<>(), PACKET_ROWS = new ArrayList<>();
    private static final Map<String, LongAdder> PACKETS = new ConcurrentHashMap<>();
    /** the packets the client throws away in this case (drop=<name>+<name>: a class's simple name, payload <id>) */
    private static volatile Set<String> DROP = Set.of();
    private static final long[] FRAMES = new long[200_000];
    private static final long[] TICKS = new long[20_000];

    private record Case(String text, String armour, int n, String mode, String[] keys) {
        static Case of(String text) {
            String[] p = text.split(":", 4);
            return new Case(text, p[0], Integer.parseInt(p[1]), p[2], p.length > 3 ? p[3].split(",") : new String[0]);
        }
    }

    private static List<Case> cases;
    private static int index, step, until, frames, ticks, hurts, lastHurt;
    private static volatile int incoming, deaths;
    private static long casingSum, casingSamples, casingMax, drawnSum;
    // alt=A/B (ntm, cull, batch): the casings' way switched every 32 frames in one case, each frame's time to its way
    private static String[] alt;
    private static final long[][] ALT_FRAMES = new long[2][100_000];
    private static final int[] ALT_N = new int[2];
    private static int altMode;
    private static long lastFrame, tickStart, windowStart, partsAt, holesAt, tracersAt, hudAt;
    private static double serverMs;
    private static int serverSamples;
    private static final Map<String, Object> CLIENT_DEFAULTS = new TreeMap<>();
    private static String fileBefore;
    private static boolean fileExisted;
    private static Item[] guns;

    private FireStand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.framerateLimit().set(260);
        mc.options.enableVsync().set(false);
        mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
        mc.options.pauseOnLostFocus = false;
        mc.options.fov().set(70);
        cases = Arrays.stream(CASES_PROP.split(";")).map(String::strip).filter(s -> !s.isEmpty()).map(Case::of).toList();
        ROWS.add("случай\tброня\tботов\tрежим\tключи\tначало_мс\tконец_мс\tкадров\tFPS\tсредний_мс\tp50_мс\tp95_мс\tp99_мс\tmax_мс"
                + "\t1%low_FPS\tтик_клиента_мс\tтик_клиента_p99_мс\tтик_сервера_мс\tпопаданий\tурон_входящий\tсмертей_отменено\tпакетов_в_с\tчастиц\tдыр\tтрасс\tботов_живо\tгильз_сред\tгильз_макс\tнарисовано_сред");
        PACKET_ROWS.add("случай\tпакет\tв_секунду");
        try {
            Path f = settingsFile();
            fileExisted = Files.exists(f);
            fileBefore = fileExisted ? Files.readString(f) : "";
        } catch (Exception e) {
            fail("подготовка: " + e);
            return;
        }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Pre.class, e -> tickStart = System.nanoTime());
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, e -> {
            if (step == 2 && tickStart > 0 && ticks < TICKS.length) TICKS[ticks++] = System.nanoTime() - tickStart;
        });
        var conn = mc.getConnection().getConnection();
        var pipe = conn.channel().pipeline();
        String handler = pipe.context(Connection.class).name();
        pipe.addBefore(handler, "fire_stand_count", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (step == 2) count(msg);
                if (!DROP.isEmpty() && DROP.contains(name(msg))) return;
                ctx.fireChannelRead(msg);
            }
        });
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            var rules = level.getGameRules();
            rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server);
            rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
            rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER, false, server);
            for (int x = -24; x <= 24; x++) for (int z = -8; z <= 32; z++) {
                level.setBlock(O.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 0; y <= 8; y++) level.setBlock(O.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "armsai ammo infinite");
        }).join();
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent.class, e -> {
            if (step == 2 && e.getEntity() instanceof ServerPlayer) incoming++;
        });
        // the player never dies on the stand: a burst inside one tick would leave the rest of the cases on a corpse
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.entity.living.LivingDeathEvent.class, e -> {
            if (step < 999 && e.getEntity() instanceof ServerPlayer p) {
                e.setCanceled(true);
                p.setHealth(p.getMaxHealth());
                deaths++;
            }
        });
        // healed every server tick: the hits go on, the player stays
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, e -> {
            if (step > 2 && step != 3) return;
            ServerPlayer p = player(e.getServer());
            p.setHealth(p.getMaxHealth());
            p.getFoodData().setFoodLevel(20);
            p.clearFire();
            if (step == 2) {
                serverMs += e.getServer().getCurrentSmoothedTickTime();
                serverSamples++;
            }
        });
        for (String k : List.of("bulletFx", "bulletHoles", "tracers", "cameraShake", "suppressionDarken", "suppressionMuffle", "aiVoice", "hitVolume",
                "cornerHud", "casingMax")) {
            try {
                CLIENT_DEFAULTS.put(k, Config.class.getField(k).get(null));
            } catch (Exception e) {
                fail("ключ клиента " + k + ": " + e);
                return;
            }
        }
        index = 0;
        prepare();
    }

    private static void count(Object msg) {
        if (msg instanceof ClientboundBundlePacket b) {
            for (var p : b.subPackets()) count(p);
            return;
        }
        PACKETS.computeIfAbsent(name(msg), k -> new LongAdder()).increment();
    }

    // NTM's spent casings alive on the client (its CasingParticleGroup's queue)
    private static int casings() {
        try {
            var engine = Minecraft.getInstance().particleEngine;
            for (var f : engine.getClass().getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                for (Object g : ((Map<?, ?>) f.get(engine)).values()) {
                    if (!g.getClass().getName().equals("com.hbm.particle.CasingParticleGroup")) continue;
                    var q = net.minecraft.client.particle.ParticleGroup.class.getDeclaredField("particles");
                    q.setAccessible(true);
                    return ((java.util.Collection<?>) q.get(g)).size();
                }
            }
        } catch (Exception e) {
            return -1;
        }
        return 0;
    }

    private static String name(Object msg) {
        return msg instanceof ClientboundCustomPayloadPacket c ? "payload " + c.payload().type().id() : msg.getClass().getSimpleName();
    }

    private static void prepare() {
        Case c = cases.get(index);
        try {
            applyKeys(c);
        } catch (Exception e) {
            fail("ключи " + c.text + ": " + e);
            return;
        }
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> setup(server, c)).join();
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(null);
        mc.player.setYRot(0F);
        mc.player.setXRot(0F);
        step = 1;
        until = now() + SETTLE;
        GunFeel.LOGGER.info("[fire-stand] случай {} начат", c.text);
    }

    private static void setup(MinecraftServer server, Case c) {
        ServerLevel level = server.overworld();
        ServerPlayer p = player(server);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "armsai clear");
        for (Entity e : toList(level)) if (e != null && !(e instanceof ServerPlayer) && e.distanceToSqr(Vec3.atCenterOf(O)) < 80 * 80) e.discard();
        var board = server.getScoreboard();
        var red = board.getPlayerTeam("fire_red");
        if (red == null) red = board.addPlayerTeam("fire_red");
        if (board.getPlayersTeam(p.getScoreboardName()) != null) board.removePlayerFromTeam(p.getScoreboardName());
        if (!c.mode.equals("fire")) board.addPlayerToTeam(p.getScoreboardName(), red);
        p.setGameMode(GameType.SURVIVAL);
        p.getAbilities().flying = false;
        p.onUpdateAbilities();
        p.teleportTo(level, O.getX() + 0.5, O.getY(), O.getZ() + 0.5, Set.of(), 0F, 0F, false);
        String[] suffix = {"_helmet", "_plate", "_legs", "_boots"};
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < 4; i++) {
            ItemStack s = ItemStack.EMPTY;
            if (!c.armour.equals("none")) {
                var item = BuiltInRegistries.ITEM.getOptional(Identifier.fromNamespaceAndPath("hbm", c.armour + suffix[i]));
                if (item.isEmpty()) GunFeel.LOGGER.warn("[fire-stand] нет hbm:{}{}", c.armour, suffix[i]);
                else s = new ItemStack(item.get());
            }
            p.setItemSlot(slots[i], s);
        }
        if (c.mode.equals("miss")) {
            Mannequin target = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
            target.snapTo(O.getX() + 3.5, O.getY(), O.getZ() + 0.5, 0F, 0F);
            target.setInvulnerable(true);
            target.addTag(acup.gunfeel.ai.AiBots.TARGET_TAG);
            level.addFreshEntity(target);
        }
        // an arc ahead of the player, 14 to 20 blocks off, the same guns case to case
        if (guns == null || guns.length < c.n) guns = Arrays.copyOf(guns == null ? new Item[0] : guns, Math.max(c.n, 1));
        for (int i = 0; i < c.n; i++) {
            double a = Math.toRadians(-60 + 120.0 * (i + 0.5) / c.n), r = 14 + (i % 3) * 3;
            Vec3 at = new Vec3(O.getX() + 0.5 + Math.sin(a) * r, O.getY(), O.getZ() + 0.5 + Math.cos(a) * r);
            List<Mob> made = AiSpawn.spawn(level, at, AiSpawn.Kind.FBI, 1, guns[i]);
            for (Mob m : made) {
                if (guns[i] == null) guns[i] = m.getMainHandItem().getItem();
                board.addPlayerToTeam(m.getScoreboardName(), red);
            }
        }
    }

    static void frame() {
        try {
            long t = System.nanoTime();
            Minecraft mc = Minecraft.getInstance();
            if (step == 2) {
                if (lastFrame > 0 && frames < FRAMES.length) {
                    if (alt != null && ALT_N[altMode] < ALT_FRAMES[altMode].length) ALT_FRAMES[altMode][ALT_N[altMode]++] = t - lastFrame;
                    FRAMES[frames++] = t - lastFrame;
                }
                if (alt != null) {
                    altMode = (frames / 32) % 2;
                    CasingCull.on = !alt[altMode].equals("ntm");
                    CasingCull.batch = alt[altMode].equals("batch");
                }
                if (mc.player.hurtTime > lastHurt) hurts++;
                if ((frames & 15) == 0) {
                    int n = casings();
                    casingSum += n;
                    casingSamples++;
                    casingMax = Math.max(casingMax, n);
                    drawnSum += CasingCull.drawn;
                }
                lastHurt = mc.player.hurtTime;
            }
            lastFrame = t;
            if (step == 1 && now() >= until) {
                step = 2;
                until = now() + MEASURE;
                frames = ticks = hurts = incoming = deaths = 0;
                casingSum = casingSamples = casingMax = drawnSum = 0;
                serverMs = 0;
                serverSamples = 0;
                PACKETS.clear();
                ALT_N[0] = ALT_N[1] = 0;
                windowStart = System.currentTimeMillis();
                partsAt = ClientSmoke.particlesSeen();
                holesAt = ClientSmoke.holesSeen();
                tracersAt = ClientSmoke.tracesSeen();
            } else if (step == 2 && now() >= until) {
                step = 3;
                measure(cases.get(index), windowStart, System.currentTimeMillis());
                write();
                if (++index >= cases.size()) {
                    restore();
                    ClientSmoke.finish();
                    step = 999;
                } else prepare();
            }
        } catch (Throwable e) {
            GunFeel.LOGGER.error("[fire-stand]", e);
            fail("исключение: " + e);
        }
    }

    private static void measure(Case c, long from, long to) {
        long[] f = Arrays.copyOf(FRAMES, frames);
        long[] k = Arrays.copyOf(TICKS, ticks);
        Arrays.sort(f);
        Arrays.sort(k);
        double sum = 0;
        for (long v : f) sum += v;
        double sec = (to - from) / 1000.0;
        // 1 % low: the frame rate over the slowest 1 % of the frames
        int worst = Math.max(1, f.length / 100);
        double worstSum = 0;
        for (int i = f.length - worst; i < f.length; i++) worstSum += f[i];
        double ksum = 0;
        for (long v : k) ksum += v;
        long packets = 0;
        for (var e : PACKETS.values()) packets += e.sum();
        int bots = Minecraft.getInstance().getSingleplayerServer().submit(() -> {
            int n = 0;
            for (Entity e : Minecraft.getInstance().getSingleplayerServer().overworld().getAllEntities())
                if (e != null && e instanceof Mob m && m.isAlive() && acup.gunfeel.ai.AiBots.get(m) != null) n++;
            return n;
        }).join();
        ROWS.add(String.join("\t", c.text, c.armour, Integer.toString(c.n), c.mode, String.join(",", c.keys), Long.toString(from), Long.toString(to),
                Integer.toString(f.length), d(f.length / sec), ms(sum / Math.max(1, f.length)), ms(pct(f, 50)), ms(pct(f, 95)), ms(pct(f, 99)),
                ms(f.length == 0 ? 0 : f[f.length - 1]), d(worstSum == 0 ? 0 : worst / (worstSum / 1e9)), ms(ksum / Math.max(1, k.length)),
                ms(pct(k, 99)), d(serverSamples == 0 ? 0 : serverMs / serverSamples), Integer.toString(hurts), Integer.toString(incoming), Integer.toString(deaths), d(packets / sec),
                Long.toString(ClientSmoke.particlesSeen() - partsAt), Long.toString(ClientSmoke.holesSeen() - holesAt),
                Long.toString(ClientSmoke.tracesSeen() - tracersAt), Integer.toString(bots), Long.toString(casingSamples == 0 ? 0 : casingSum / casingSamples), Long.toString(casingMax), Long.toString(casingSamples == 0 ? 0 : drawnSum / casingSamples)));
        var byRate = new ArrayList<>(PACKETS.entrySet());
        byRate.sort((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()));
        for (var e : byRate) PACKET_ROWS.add(c.text + "\t" + e.getKey() + "\t" + d(e.getValue().sum() / sec));
        GunFeel.LOGGER.info("[fire-stand] {}", ROWS.getLast());
        if (alt != null) for (int m = 0; m < 2; m++) {
            long[] a = Arrays.copyOf(ALT_FRAMES[m], ALT_N[m]);
            Arrays.sort(a);
            double s = 0;
            for (long v : a) s += v;
            ROWS.add(String.join("\t", c.text + " → " + alt[m], c.armour, Integer.toString(c.n), c.mode, alt[m], "", "", Integer.toString(a.length),
                    "", ms(s / Math.max(1, a.length)), ms(pct(a, 50)), ms(pct(a, 95)), ms(pct(a, 99))));
        }
    }

    private static List<Entity> toList(ServerLevel level) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : level.getAllEntities()) if (e != null) out.add(e);
        return out;
    }

    private static double pct(long[] sorted, int p) {
        if (sorted.length == 0) return 0;
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * p / 100.0) - 1)];
    }

    // the case's keys over the defaults, the rest back to the defaults
    private static void applyKeys(Case c) throws Exception {
        for (var e : CLIENT_DEFAULTS.entrySet()) Config.class.getField(e.getKey()).set(null, e.getValue());
        StringBuilder server = new StringBuilder();
        DROP = Set.of();
        CasingCull.on = CasingCull.batch = true;
        alt = null;
        for (String kv : c.keys) {
            String[] p = kv.split("=", 2);
            if (p[0].equals("alt")) {
                alt = p[1].split("/");
                continue;
            }
            if (p[0].equals("casing")) {
                CasingCull.on = !p[1].equals("ntm");
                CasingCull.batch = p[1].equals("batch");
                continue;
            }
            if (p[0].equals("drop")) {
                Set<String> d = new java.util.HashSet<>();
                for (String n : p[1].split("\\+")) d.add(n.contains(":") ? "payload " + n : n);
                DROP = Set.copyOf(d);
                continue;
            }
            if (p[0].startsWith("s.")) {
                server.append(p[0].substring(2)).append(" = ").append(p[1]).append('\n');
                continue;
            }
            var field = Config.class.getField(p[0]);
            Class<?> type = field.getType();
            field.set(null, type == boolean.class ? (Object) Boolean.parseBoolean(p[1]) : type == float.class ? (Object) Float.parseFloat(p[1])
                    : type == int.class ? (Object) Integer.parseInt(p[1]) : p[1]);
        }
        String base = fileBefore;
        Files.writeString(settingsFile(), base + (base.isEmpty() || base.endsWith("\n") ? "" : "\n") + server);
        Files.setLastModifiedTime(settingsFile(), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
    }

    private static void restore() {
        try {
            for (var e : CLIENT_DEFAULTS.entrySet()) Config.class.getField(e.getKey()).set(null, e.getValue());
            if (fileExisted) Files.writeString(settingsFile(), fileBefore);
            else Files.deleteIfExists(settingsFile());
        } catch (Exception e) {
            GunFeel.LOGGER.warn("[fire-stand] настройки не восстановлены", e);
        }
    }

    private static void write() {
        try {
            Files.createDirectories(OUT.toAbsolutePath().getParent());
            Files.write(OUT, ROWS);
            Files.write(Path.of(OUT.toString().replaceAll("\\.tsv$", "") + ".packets.tsv"), PACKET_ROWS);
        } catch (Exception e) {
            GunFeel.LOGGER.error("[fire-stand] запись", e);
        }
    }

    private static void fail(String text) {
        ClientSmoke.check(false, "стенд огня: " + text);
        restore();
        write();
        ClientSmoke.finish();
        step = 999;
    }

    private static Path settingsFile() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(ReaimTimes.FILE);
    }

    private static int now() {
        return Minecraft.getInstance().getSingleplayerServer().getTickCount();
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static String ms(double nanos) {
        return String.format(Locale.ROOT, "%.2f", nanos / 1e6);
    }

    private static String d(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
