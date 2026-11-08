package acup.gunfeel.ai.imitation;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ai.AiBot;
import acup.gunfeel.ai.AiBots;
import acup.gunfeel.ai.CoverQuery;
import acup.gunfeel.ai.TacticalMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * The fights recorded for the model to learn from: a player's (he turns it on for himself, /armsai record on), and
 * for the stands every AI mob's of a level (/armsai record bots on; a model trained on those must answer as the
 * formula did, a check of the whole chain). A file each in logs/ntm-gunfeel-fights/, gzipped JSON lines: a header
 * (the numbers' names, who, when), then a view every {@link #EVERY} ticks in a fight (every {@link #QUIET} out of
 * one): where it is and looks, what it has, the model's numbers ({@link FightFeatures}), the enemies it knows of, the
 * cover it could take (the bot's own search) with their rows. What it decided is worked out from the views later
 * (tools/imitation), not here. Off by default; a file stops at {@link #MAX_BYTES}, and none is started past
 * {@link #MAX_DIR} in the folder. Server thread.
 */
public final class FightRecorder {
    public static final String DIR = "logs/ntm-gunfeel-fights";
    /** ticks between two views in a fight, and out of one */
    static final int EVERY = 4, QUIET = 20;
    /** cover places kept a view */
    static final int COVERS = 8;
    /** a file's most (as written, before gzip), and the folder's most (gzipped) */
    static final long MAX_BYTES = 256L << 20, MAX_DIR = 2L << 30;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    static final class Subject {
        final int id;
        final String name;
        final Path file;
        final Writer out;
        final FightHistory history = new FightHistory();
        final FightLook.Sight sight = new FightLook.Sight();
        long bytes, lines, next;
        @Nullable
        String stopped;

        Subject(int id, String name, Path file, Writer out) {
            this.id = id;
            this.name = name;
            this.file = file;
            this.out = out;
        }
    }

    /** players by their entity id, then bots */
    private static final Map<Integer, Subject> PLAYERS = new LinkedHashMap<>(), BOTS = new LinkedHashMap<>();
    /** the level whose bots are recorded, if any */
    private static @Nullable ServerLevel botsLevel;
    private static @Nullable Path botsDir;

    private FightRecorder() {
    }

    public static boolean recording(ServerPlayer p) {
        return PLAYERS.containsKey(p.getId());
    }

    /** starts recording a player; the file's path, or why not */
    public static String start(ServerPlayer p) throws IOException {
        Subject s = PLAYERS.get(p.getId());
        if (s != null) return "already: " + s.file;
        Path dir = dir(p.level().getServer());
        String full = full(dir);
        if (full != null) return full;
        s = open(dir.resolve(LocalDateTime.now().format(STAMP) + "-" + safe(p.getGameProfile().name()) + ".jsonl.gz"), p.getId(),
                p.getGameProfile().name(), "player");
        PLAYERS.put(p.getId(), s);
        return s.file.toString();
    }

    /** stops a player's recording; what was written */
    public static String stop(ServerPlayer p) {
        Subject s = PLAYERS.remove(p.getId());
        if (s == null) return "not recording";
        close(s);
        return s.file + ": " + s.lines + " views";
    }

    /** the bots of this level from now on (those that come later too), a file each in a folder of their own */
    public static String startBots(ServerLevel level) throws IOException {
        if (botsLevel != null) return "already: " + botsDir;
        Path dir = dir(level.getServer());
        String full = full(dir);
        if (full != null) return full;
        botsDir = dir.resolve(LocalDateTime.now().format(STAMP) + "-bots");
        Files.createDirectories(botsDir);
        botsLevel = level;
        return botsDir.toString();
    }

    public static String stopBots() {
        if (botsLevel == null) return "not recording bots";
        long views = 0;
        for (Subject s : BOTS.values()) {
            views += s.lines;
            close(s);
        }
        int n = BOTS.size();
        BOTS.clear();
        String out = botsDir + ": " + n + " bots, " + views + " views";
        botsLevel = null;
        botsDir = null;
        return out;
    }

    public static void stopAll() {
        for (Subject s : PLAYERS.values()) close(s);
        PLAYERS.clear();
        stopBots();
    }

    public static String info() {
        StringBuilder b = new StringBuilder("recording: ");
        b.append(PLAYERS.size()).append(" players");
        for (Subject s : PLAYERS.values()) b.append("; ").append(s.name).append(' ').append(s.lines).append(" views, ").append(s.bytes >> 10).append(" KiB")
                .append(s.stopped != null ? " (stopped: " + s.stopped + ")" : "");
        if (botsLevel != null) b.append("; bots: ").append(BOTS.size()).append(" in ").append(botsDir);
        return b.toString();
    }

    /** every tick of a level: the views due */
    public static void tick(ServerLevel level) {
        if (PLAYERS.isEmpty() && botsLevel != level) return;
        long tick = level.getGameTime();
        for (Iterator<Subject> it = PLAYERS.values().iterator(); it.hasNext();) {
            Subject s = it.next();
            if (!(level.getEntity(s.id) instanceof ServerPlayer p)) {
                // gone from this level: another level's tick has it, or he left the game
                if (level.getServer().getPlayerList().getPlayers().stream().noneMatch(x -> x.getId() == s.id)) {
                    close(s);
                    it.remove();
                }
                continue;
            }
            if (tick < s.next || s.stopped != null) continue;
            // the map's cells round him, for the cover search (the AI mobs keep them only round themselves)
            if (tick % 20 == 0) TacticalMap.of(level).want(p.getBlockX(), p.getBlockY(), p.getBlockZ(), 24, 8);
            FightView v = FightLook.player(level, p, s.sight, s.history, tick);
            view(level, s, p, v, tick);
        }
        if (botsLevel != level) return;
        for (AiBot b : AiBots.all(level)) {
            Subject s = BOTS.get(b.mob.getId());
            if (s == null) {
                if (!b.mob.isAlive()) continue;
                try {
                    s = open(botsDir.resolve(b.mob.getId() + ".jsonl.gz"), b.mob.getId(), b.mob.getName().getString(), "bot");
                } catch (IOException e) {
                    continue;
                }
                BOTS.put(b.mob.getId(), s);
            }
            if (tick < s.next || s.stopped != null || !b.mob.isAlive()) continue;
            FightView v = FightLook.bot(level, b, s.history, tick);
            view(level, s, b.mob, v, tick);
        }
    }

    private static void view(ServerLevel level, Subject s, LivingEntity e, FightView v, long tick) {
        boolean fight = !v.enemies.isEmpty();
        s.next = tick + (fight ? EVERY : QUIET);
        List<CoverQuery.Spot> covers = fight ? FightLook.covers(level, e, v, COVERS) : List.of();
        write(s, line(v, FightFeatures.of(v), covers, FightLook.spotRows(covers, v)));
    }

    /** a view as a line of JSON */
    static String line(FightView v, double[] f, List<CoverQuery.Spot> covers, double[][] rows) {
        StringBuilder b = new StringBuilder(1024);
        b.append("{\"t\":").append(v.tick);
        num(b.append(",\"x\":"), v.x);
        num(b.append(",\"y\":"), v.y);
        num(b.append(",\"z\":"), v.z);
        num(b.append(",\"yaw\":"), v.yaw);
        num(b.append(",\"pitch\":"), v.pitch);
        num(b.append(",\"hp\":"), v.health);
        b.append(",\"ld\":").append(v.loaded).append(",\"cap\":").append(v.capacity);
        b.append(",\"rl\":").append(v.reloading ? 1 : 0).append(",\"cr\":").append(v.crouching ? 1 : 0).append(",\"pr\":").append(v.prone ? 1 : 0)
                .append(",\"sp\":").append(v.sprinting ? 1 : 0).append(",\"fi\":").append(v.fired ? 1 : 0).append(",\"hu\":").append(v.hurt ? 1 : 0)
                .append(",\"ch\":").append(v.coverHere).append(",\"hid\":").append(v.hidden() ? 1 : 0).append(",\"exp\":").append(v.exposed() ? 1 : 0);
        b.append(",\"f\":");
        row(b, f);
        b.append(",\"e\":[");
        for (int i = 0; i < v.enemies.size(); i++) {
            FightView.Enemy en = v.enemies.get(i);
            if (i > 0) b.append(',');
            b.append('[').append(en.id());
            num(b.append(','), en.x());
            num(b.append(','), en.y());
            num(b.append(','), en.z());
            b.append(',').append(en.visible() ? 1 : 0).append(',').append(en.exposedTo() ? 1 : 0).append(',').append(en.seesMe() ? 1 : 0).append(']');
        }
        b.append("],\"c\":[");
        for (int i = 0; i < covers.size(); i++) {
            CoverQuery.Spot c = covers.get(i);
            if (i > 0) b.append(',');
            b.append('[').append(BlockPos.getX(c.cell())).append(',').append(BlockPos.getY(c.cell())).append(',').append(BlockPos.getZ(c.cell()));
            num(b.append(','), c.score());
            b.append(',').append(c.height()).append(',').append(c.hard() ? 1 : 0).append(',').append(c.hasPeek() ? 1 : 0).append(",");
            row(b, rows[i]);
            b.append(']');
        }
        b.append("]}\n");
        return b.toString();
    }

    static String header(String kind, String name, int id) {
        StringBuilder b = new StringBuilder("{\"kind\":\"header\",\"v\":1,\"who\":\"").append(kind).append("\",\"name\":\"").append(safe(name))
                .append("\",\"id\":").append(id).append(",\"every\":").append(EVERY).append(",\"quiet\":").append(QUIET).append(",\"features\":[");
        names(b, FightFeatures.NAMES);
        b.append("],\"spotFeatures\":[");
        names(b, FightFeatures.SPOT_NAMES);
        return b.append("]}\n").toString();
    }

    private static void names(StringBuilder b, List<String> names) {
        for (int i = 0; i < names.size(); i++) b.append(i > 0 ? ",\"" : "\"").append(names.get(i)).append('"');
    }

    private static void row(StringBuilder b, double[] f) {
        b.append('[');
        for (int i = 0; i < f.length; i++) num(i > 0 ? b.append(',') : b, f[i]);
        b.append(']');
    }

    /** three decimals, no exponent */
    static void num(StringBuilder b, double x) {
        if (!Double.isFinite(x)) {
            b.append(0);
            return;
        }
        long m = Math.round(x * 1000);
        if (m < 0) {
            b.append('-');
            m = -m;
        }
        b.append(m / 1000);
        long frac = m % 1000;
        if (frac != 0) {
            b.append('.');
            if (frac < 100) b.append('0');
            if (frac < 10) b.append('0');
            while (frac % 10 == 0) frac /= 10;
            b.append(frac);
        }
    }

    private static Subject open(Path file, int id, String name, String kind) throws IOException {
        Files.createDirectories(file.getParent());
        OutputStream os = new GZIPOutputStream(Files.newOutputStream(file), 1 << 16, true);
        Writer w = new java.io.OutputStreamWriter(os, StandardCharsets.UTF_8);
        Subject s = new Subject(id, name, file, w);
        write(s, header(kind, name, id));
        return s;
    }

    private static void write(Subject s, String line) {
        if (s.stopped != null) return;
        try {
            s.out.write(line);
            s.bytes += line.length();
            s.lines++;
            // every few seconds to the disk: a crash loses little
            if (s.lines % 100 == 0) s.out.flush();
            if (s.bytes >= MAX_BYTES) {
                s.stopped = "the file is full";
                s.out.close();
            }
        } catch (IOException e) {
            s.stopped = e.toString();
        }
    }

    private static void close(Subject s) {
        try {
            if (s.stopped == null) s.out.close();
        } catch (IOException e) {
            // what was written is kept
        }
        if (s.stopped == null) s.stopped = "stopped";
    }

    private static Path dir(MinecraftServer server) {
        return server.getServerDirectory().resolve(DIR);
    }

    /** why no file may be started there now, or null */
    private static @Nullable String full(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        long total;
        try (var all = Files.walk(dir)) {
            total = all.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
        }
        return total >= MAX_DIR ? "the folder " + dir + " holds " + (total >> 20) + " MiB already, past " + (MAX_DIR >> 20) + ": move them off" : null;
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}
