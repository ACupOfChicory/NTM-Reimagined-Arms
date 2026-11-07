package acup.gunfeel.ai.bench;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.gamerules.GameRules;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

// The mob AI's server stand (a dev run only: ./gradlew runBenchAi -PnoAssets, see build.gradle.kts).
// -Dntmgunfeel.benchAi=<file>: once the world is up the server builds an arena on its flat world (Arena), keeps the
// arena's chunks loaded and ticking with forced tickets and no player, and runs the phases below in rounds (every
// phase once, then every phase again: ABAB, so drift and noise show between the rounds). A phase is set up on a
// cleared arena, warmed up for 10 s and measured for 30 s (at least 600 ticks) by the server's own time of each tick,
// what MinecraftServer.tickServer takes from its start to the end of the tick (what the game's tick chart and MSPT
// tools show; the wait for the next tick is not in it). One line per measurement goes to the file, one per explosion
// of the boom phase to <file>-boom.tsv, then the server stops. Without the property nothing is registered.
// -Dntmgunfeel.benchAiPhases=a,b runs only those phases, -Dntmgunfeel.benchAiRounds=<n> that many rounds (3).
public final class AiBench {
    public static final String PROPERTY = "ntmgunfeel.benchAi";

    // A phase: its name, the mobs it should have and what puts them on the cleared arena (Arena's spawn, grid,
    // fbiPairs, command); booms are the measured ticks at which an explosion goes off (Arena.BOOMS, in turn).
    // -PbenchAiPhases=empty,ai100 runs just those.
    public record Phase(String name, int mobs, Consumer<Arena> prep, int... booms) {
    }

    // fbi100: two sides of 50 FBI going at each other (Arena.fbiPairs); fbi100nogun: the same with empty hands (what
    // the shooting costs: NTM's bullets and the mod's); fbi100idle: 100 FBI with no target, their own
    // wandering; dummy100: 100 of NTM's test dummy (a Mob with no goals); boom: three explosions by fbi100's fight
    // (vanilla ServerLevel.sendBlockUpdated asks every navigating mob of the level about every block whose collision
    // changed: research §1.6); boomidle: the same by fbi100idle's wanderers (Lithium asks only mobs with a path);
    // boom0: the same explosions on the empty arena, the explosion's own cost; ai100: the mod's AI mobs, two teams of
    // 50 FBI going at each other as fbi100's (Arena.aiPairs); aiboom: the same with fbi100's explosions
    static final List<Phase> PHASES = List.of(
            new Phase("empty", 0, a -> {
            }),
            new Phase("fbi100", 100, a -> a.fbiPairs(100)),
            new Phase("fbi100nogun", 100, a -> a.fbiPairs(100, false)),
            new Phase("fbi100idle", 100, a -> a.grid("hbm:entity_fbi", 100)),
            new Phase("dummy100", 100, a -> a.grid("hbm:entity_test_dummy", 100)),
            new Phase("boom", 100, a -> a.fbiPairs(100), 150, 300, 450),
            new Phase("boomidle", 100, a -> a.grid("hbm:entity_fbi", 100), 150, 300, 450),
            new Phase("boom0", 0, a -> {
            }, 150, 300, 450),
            new Phase("ai100", 100, a -> a.aiPairs(100)),
            new Phase("aiboom", 100, a -> a.aiPairs(100), 150, 300, 450));

    static final int WARM_TICKS = 200, MEASURE_TICKS = 600, LOOK_EVERY = 20, AFTER = 20;
    static final long WARM_NANOS = 10_000_000_000L, MEASURE_NANOS = 30_000_000_000L, STALL_NANOS = 180_000_000_000L;
    static final String[] OPTIMIZERS = {"lithium", "ferritecore", "scalablelux"};

    private enum Step { CHUNKS, PROBE, PREP, WARM, MEASURE }

    private static Path file, boomFile;
    private static MinecraftServer server;
    private static ServerLevel level;
    private static Arena arena;
    private static List<Phase> phases;
    private static int rounds, round = 1, phase;
    private static Step step = Step.CHUNKS;
    private static int stepTicks;
    private static long stepStart, runStart;
    private static volatile long lastTick;
    private static String lithium, sim;

    // the measurement under way
    private static final int CAP = 8 * MEASURE_TICKS;
    private static final long[] nanos = new long[CAP];
    private static final int[] asked = new int[CAP], recomputed = new int[CAP], searches = new int[CAP];
    private static int n;
    private static List<Mob> mobs;
    private static int[] mobTicks;
    private static int probeTicks, looks, withTarget, withPath, bullets;
    private static long gcMillis;
    private static int[] boomBefore, boomAfter, boomPaths;

    private AiBench() {
    }

    public static void registerIfAsked() {
        String f = System.getProperty(PROPERTY);
        if (f == null || f.isBlank()) return;
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, e -> guard(() -> start(e.getServer(), Path.of(f))));
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Pre.class, e -> {
            if (arena != null) guard(AiBench::pre);
        });
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> {
            if (arena != null) guard(AiBench::post);
        });
        // NTM's bullets that come into the level while a phase is measured
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, e -> {
            if (step == Step.MEASURE && !e.getLevel().isClientSide() && e.getEntity() instanceof EntityBulletBaseMK4) bullets++;
        });
        // the stand's mobs take no damage (from each other's punches, the explosions, cramming), so none dies mid-phase
        NeoForge.EVENT_BUS.addListener(LivingIncomingDamageEvent.class, e -> {
            if (e.getEntity().entityTags().contains(Arena.TAG)) e.setCanceled(true);
        });
    }

    private static void guard(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            StringBuilder b = new StringBuilder("# FAIL crashed: " + t);
            for (StackTraceElement s : t.getStackTrace()) b.append("\n#     at ").append(s);
            finish(b.toString(), 1);
        }
    }

    private static void start(MinecraftServer s, Path f) {
        server = s;
        level = s.overworld();
        file = f.toAbsolutePath();
        boomFile = file.resolveSibling(file.getFileName().toString().replaceFirst("\\.tsv$", "") + "-boom.tsv");
        String only = System.getProperty("ntmgunfeel.benchAiPhases", "");
        Set<String> names = Arrays.stream(only.split(",")).map(String::trim).filter(x -> !x.isEmpty()).collect(Collectors.toSet());
        phases = PHASES.stream().filter(p -> names.isEmpty() || names.contains(p.name())).toList();
        if (phases.isEmpty()) throw new IllegalArgumentException("no phase named " + only + "; there are "
                + PHASES.stream().map(Phase::name).toList());
        rounds = Integer.getInteger("ntmgunfeel.benchAiRounds", 3);
        GameRules rules = level.getGameRules();
        rules.set(GameRules.SPAWN_MOBS, false, s);
        rules.set(GameRules.ADVANCE_TIME, false, s);
        rules.set(GameRules.ADVANCE_WEATHER, false, s);
        // the autosave every 5 minutes would land in some measurement and not in others
        s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(), "save-off");
        lithium = ModList.get().isLoaded("lithium") ? "yes" : "no";
        sim = Integer.toString(s.getPlayerList().getSimulationDistance());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "");
            Files.writeString(boomFile, String.join("\t", "фаза", "повтор", "lithium", "sim", "взрыв", "x", "z", "сила", "мобов",
                    "мобов_с_путём", "блоков_до", "блоков_после", "разрушено", "мс_взрыва", "мс_+1", "мс_+2", "мс_+3", "мс_+4",
                    "мс_+5", "max_+1..+20_мс", "среднее_20_до_мс", "спрошено", "пересчётов", "поисков_пути",
                    "спрошено_+1..+20", "поисков_пути_+1..+20") + "\n");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        arena = new Arena(level);
        Runtime r = Runtime.getRuntime();
        line("# AI-стенд сервера: " + version("ntmgunfeel") + ", NTM " + version("hbm") + ", NeoForge " + version("neoforge")
                + ", java " + System.getProperty("java.version") + ", потоков " + r.availableProcessors() + ", куча до "
                + r.maxMemory() / 1048576 + " МБ");
        List<String> loaded = new ArrayList<>();
        for (String id : OPTIMIZERS) if (ModList.get().isLoaded(id)) loaded.add(id + " " + version(id));
        String asked = System.getProperty("ntmgunfeel.benchAiLithium", "false");
        line("# оптимизаторы: запрошены " + ("true".equals(asked) ? "да" : "нет") + ", загружены: "
                + (loaded.isEmpty() ? "нет" : String.join(", ", loaded))
                + ("true".equals(asked) && loaded.size() < OPTIMIZERS.length ? "  WARN загрузились не все" : ""));
        int pause = s instanceof DedicatedServer d ? d.pauseWhenEmptySeconds() : -1;
        line("# simulation-distance " + sim + ", view-distance " + s.getPlayerList().getViewDistance()
                + ", pause-when-empty-seconds " + pause + (pause > 0 ? "  WARN сервер без игроков встанет на паузу" : ""));
        line("# пол арены: " + arena.floor.length + " слоёв (" + Arrays.stream(arena.floor)
                .map(b -> b.getBlock().getDescriptionId().replaceFirst("^block\\.minecraft\\.", ""))
                .collect(Collectors.groupingBy(x -> x, java.util.LinkedHashMap::new, Collectors.counting()))
                + "), мобы стоят на y " + arena.ground);
        line("# фазы " + phases.stream().map(Phase::name).toList() + " по кругу, кругов " + rounds + "; прогрев "
                + WARM_TICKS + " тиков и не меньше " + WARM_NANOS / 1_000_000_000 + " с, замер " + MEASURE_TICKS
                + " тиков и не меньше " + MEASURE_NANOS / 1_000_000_000 + " с");
        runStart = System.nanoTime();
        lastTick = runStart;
        step(Step.CHUNKS);
        Thread watch = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    return;
                }
                if (System.nanoTime() - lastTick > STALL_NANOS)
                    finish("# FAIL сервер не тикал " + STALL_NANOS / 1_000_000_000 + " с (шаг " + step + ")", 1);
            }
        }, "AiBench watchdog");
        watch.setDaemon(true);
        watch.start();
    }

    private static void pre() {
        lastTick = System.nanoTime();
        if (step != Step.MEASURE) return;
        PathCount.asked = PathCount.recomputed = PathCount.searches = 0;
        int j = boomAt(n);
        if (j >= 0) arena.boom(j); // in the tick, so its time is the tick's
    }

    private static void post() {
        long tick = server.getTickTimesNanos()[server.getTickCount() % 100];
        stepTicks++;
        switch (step) {
            case CHUNKS -> {
                int ticking = arena.ticking();
                if (ticking == Arena.forced()) {
                    line("# чанков арены с forceload " + Arena.forced() + ", из них entity-ticking " + ticking + " через "
                            + stepTicks + " тиков (ServerLevel.isPositionEntityTicking); загружено чанков "
                            + level.getChunkSource().getLoadedChunksCount());
                    line("# арена: поставлено блоков " + arena.build());
                    arena.probe();
                    step(Step.PROBE);
                } else if (stepTicks > 1200)
                    finish("# FAIL за 1200 тиков entity-ticking только " + ticking + " чанков из " + Arena.forced(), 1);
            }
            case PROBE -> {
                if (stepTicks < 100) return;
                int ticked = arena.probe.tickCount;
                line("# проба (hbm:entity_test_dummy в углу арены): тиков сервера " + stepTicks + ", тиков пробы " + ticked
                        + (ticked >= stepTicks - 1 ? "" : "  FAIL сущности не тикают"));
                if (ticked < stepTicks - 1) finish("# FAIL проба не тикает", 1);
                line(String.join("\t", "фаза", "повтор", "lithium", "sim", "мобов", "среднее_мс", "p50_мс", "p95_мс",
                        "p99_мс", "max_мс", "сущностей", "чанков", "тиков", "tps", "тики_мобов", "тики_пробы", "с_целью",
                        "с_путём", "поисков_пути_тик", "спрошено_тик", "пуль_с", "gc_мс", "load1", "прим"));
                step(Step.PREP);
            }
            case PREP -> {
                arena.clear();
                arena.build();
                phases.get(phase).prep().accept(arena);
                step(Step.WARM);
            }
            case WARM -> {
                if (stepTicks >= WARM_TICKS && System.nanoTime() - stepStart >= WARM_NANOS) begin();
            }
            case MEASURE -> {
                record(tick);
                if (n >= MEASURE_TICKS && System.nanoTime() - stepStart >= MEASURE_NANOS || n >= CAP) {
                    end();
                    if (++phase == phases.size()) {
                        phase = 0;
                        round++;
                    }
                    if (round > rounds) {
                        line("# сущности уровня в конце по типам (на арене / вне её): " + census());
                        finish("# готово за " + (System.nanoTime() - runStart) / 60_000_000_000L + " мин", 0);
                    }
                    step(Step.PREP);
                }
            }
        }
    }

    private static void step(Step s) {
        step = s;
        stepTicks = 0;
        stepStart = System.nanoTime();
    }

    private static int boomAt(int tick) {
        int[] b = phases.get(phase).booms();
        for (int j = 0; j < b.length; j++) if (b[j] == tick) return j;
        return -1;
    }

    private static void begin() {
        mobs = arena.mobs();
        mobTicks = new int[mobs.size()];
        for (int i = 0; i < mobTicks.length; i++) mobTicks[i] = mobs.get(i).tickCount;
        probeTicks = arena.probe.tickCount;
        looks = withTarget = withPath = bullets = 0;
        gcMillis = gcMillis();
        int b = phases.get(phase).booms().length;
        boomBefore = new int[b];
        boomAfter = new int[b];
        boomPaths = new int[b];
        n = 0;
        PathCount.on = true;
        step(Step.MEASURE);
    }

    // after tick n of the measurement (its explosion, if any, went off before it)
    private static void record(long tick) {
        nanos[n] = tick;
        asked[n] = PathCount.asked;
        recomputed[n] = PathCount.recomputed;
        searches[n] = PathCount.searches;
        if (n % LOOK_EVERY == 0) {
            looks++;
            for (Mob m : mobs) {
                if (m.getTarget() != null) withTarget++;
                if (m.getNavigation().isInProgress()) withPath++;
            }
        }
        int j = boomAt(n);
        if (j >= 0) boomAfter[j] = arena.blocksAround(j);
        j = boomAt(n + 1);
        if (j >= 0) {
            boomBefore[j] = arena.blocksAround(j);
            for (Mob m : mobs) if (m.getNavigation().isInProgress()) boomPaths[j]++;
        }
        n++;
    }

    private static void end() {
        PathCount.on = false;
        Phase p = phases.get(phase);
        long[] sorted = Arrays.copyOf(nanos, n);
        Arrays.sort(sorted);
        double sum = 0;
        long search = 0, ask = 0;
        for (int i = 0; i < n; i++) {
            sum += nanos[i];
            search += searches[i];
            ask += asked[i];
        }
        long mobTicked = 0;
        int alive = 0;
        for (int i = 0; i < mobs.size(); i++) {
            Mob m = mobs.get(i);
            if (m.isRemoved()) continue;
            alive++;
            mobTicked += m.tickCount - mobTicks[i];
        }
        int entities = 0;
        for (var ignored : level.getAllEntities()) entities++;
        double seconds = (System.nanoTime() - stepStart) / 1e9;
        List<String> notes = new ArrayList<>();
        if (mobs.size() != p.mobs()) notes.add("WARN мобов " + mobs.size() + " из " + p.mobs());
        if (alive != mobs.size()) notes.add("WARN к концу осталось " + alive);
        double mobRatio = alive == 0 ? Double.NaN : (double) mobTicked / alive / n;
        if (alive > 0 && mobRatio < .99) notes.add("FAIL мобы тикали не каждый тик");
        double probeRatio = (double) (arena.probe.tickCount - probeTicks) / n;
        if (probeRatio < .99) notes.add("FAIL проба тикала не каждый тик");
        int looked = looks * Math.max(1, mobs.size());
        line(String.join("\t", p.name(), Integer.toString(round), lithium, sim, Integer.toString(mobs.size()),
                ms(sum / n), ms(pct(sorted, .5)), ms(pct(sorted, .95)), ms(pct(sorted, .99)), ms(sorted[n - 1]),
                Integer.toString(entities), Integer.toString(level.getChunkSource().getLoadedChunksCount()),
                Integer.toString(n), f(n / seconds, 2), mobs.isEmpty() ? "-" : f(mobRatio, 3), f(probeRatio, 3),
                mobs.isEmpty() ? "-" : f((double) withTarget / looked, 2), mobs.isEmpty() ? "-" : f((double) withPath / looked, 2),
                f((double) search / n, 2), f((double) ask / n, 1), f(bullets / seconds, 1), Long.toString(gcMillis() - gcMillis), load(),
                notes.isEmpty() ? "-" : String.join("; ", notes)));
        int[] booms = p.booms();
        StringBuilder b = new StringBuilder();
        for (int j = 0; j < booms.length; j++) {
            int t = booms[j];
            if (t >= n) continue;
            int[] at = Arena.BOOMS[j % Arena.BOOMS.length];
            long max = 0;
            int askAfter = 0, searchAfter = 0;
            for (int i = t + 1; i <= Math.min(n - 1, t + AFTER); i++) {
                max = Math.max(max, nanos[i]);
                askAfter += asked[i];
                searchAfter += searches[i];
            }
            double before = 0;
            int from = Math.max(0, t - AFTER);
            for (int i = from; i < t; i++) before += nanos[i];
            List<String> row = new ArrayList<>(List.of(p.name(), Integer.toString(round), lithium, sim, Integer.toString(j + 1),
                    Integer.toString(at[0]), Integer.toString(at[1]), f(Arena.BOOM_POWER, 0), Integer.toString(mobs.size()),
                    Integer.toString(boomPaths[j]), Integer.toString(boomBefore[j]), Integer.toString(boomAfter[j]),
                    Integer.toString(boomBefore[j] - boomAfter[j]), ms(nanos[t])));
            for (int k = 1; k <= 5; k++) row.add(t + k < n ? ms(nanos[t + k]) : "-");
            row.addAll(List.of(ms(max), t > from ? ms(before / (t - from)) : "-", Integer.toString(asked[t]),
                    Integer.toString(recomputed[t]), Integer.toString(searches[t]), Integer.toString(askAfter),
                    Integer.toString(searchAfter)));
            b.append(String.join("\t", row)).append('\n');
        }
        if (!b.isEmpty()) append(boomFile, b.toString());
    }

    // every entity of the level by type, on the arena (its box) and off it
    private static String census() {
        var box = arena.box();
        java.util.Map<String, int[]> by = new java.util.TreeMap<>();
        for (var e : level.getAllEntities())
            by.computeIfAbsent(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString(),
                    k -> new int[2])[box.contains(e.position()) ? 0 : 1]++;
        return by.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()[0] + "/" + e.getValue()[1])
                .collect(Collectors.joining(", "));
    }

    private static double pct(long[] sorted, double q) {
        return sorted[Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(q * sorted.length) - 1))];
    }

    private static String ms(double nanos) {
        return f(nanos / 1e6, 3);
    }

    private static String f(double v, int digits) {
        return String.format(Locale.ROOT, "%." + digits + "f", v);
    }

    private static long gcMillis() {
        long t = 0;
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) t += Math.max(0, g.getCollectionTime());
        return t;
    }

    private static String load() {
        try {
            return Files.readString(Path.of("/proc/loadavg")).split(" ")[0];
        } catch (Exception e) {
            return "-";
        }
    }

    private static String version(String mod) {
        return ModList.get().getModContainerById(mod).map(c -> c.getModInfo().getVersion().toString()).orElse("-");
    }

    private static void line(String s) {
        append(file, s + "\n");
        System.out.println("[AiBench] " + s);
    }

    private static synchronized void append(Path f, String s) {
        try {
            Files.writeString(f, s, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static synchronized void finish(String last, int code) {
        if (file != null) line(last);
        else System.out.println("[AiBench] " + last);
        Runtime.getRuntime().halt(code);
    }
}
