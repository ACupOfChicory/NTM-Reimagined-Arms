package acup.gunfeel.ai.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import acup.gunfeel.ai.AiBot;
import acup.gunfeel.ai.AiBots;
import acup.gunfeel.ai.AiTerrain;
import acup.gunfeel.ai.TacticalMap;
import net.minecraft.world.entity.Mob;

/**
 * The crater phase of the AI stand (aicrater): the land changes under a fight. Two of NTM's bombs go off, one where a
 * side stands and one between the sides, then a trench is dug across a side's way; for each of the three it writes
 * what the change cost and how the mobs took it: the tick it fell in and the worst after, how long the tactical map
 * and the terrain layers were behind the blocks, how many mobs walked a path searched on the older map, how many
 * searches it set off, and how many mobs then stood where they were for five seconds wanting to go, sat in the hole
 * or gave their goal up.
 */
final class CraterWatch {
    static final String HEADER = String.join("\t", "фаза", "повтор", "событие", "тик", "x", "z", "разрушено", "мс_события", "из_них_хуки_блоков_мс", "вызовов_хука",
            "max_+1..+20_мс", "среднее_20_до_мс", "устаревших_секций_20_до", "устаревших_секций_max", "очередь_карты_20_до", "очередь_карты_max", "тиков_до_свежей_карты", "слои_свежи_доля_до", "тиков_до_свежих_слоёв",
            "на_старом_пути_20_до", "на_старом_пути_max", "бот_тиков_на_старом_пути", "поисков_ИИ_100_до", "поисков_ИИ_+1..+100", "путей_оставлено_+1..+100", "провалов_поиска_+1..+200",
            "прыжков_на_месте_+1..+200", "застряло_5с_max", "в_яме_max", "в_яме_на_+200", "из_них_хотят_идти", "из_них_без_выхода", "отказались_на_+200");

    /** the measured ticks the bombs go off at, and the trench's first; a column of it a tick */
    static final int[] BOMBS = {60, 200};
    static final int DIG = 340;
    /** how long after a change the mobs are watched, and a mob wanting to go that made no block of way this long is stuck */
    static final int WATCH = 200, STUCK = 100;

    private final Arena arena;
    private final List<Mob> mobs;
    private final AiBot[] bots;
    private final double[] anchorX, anchorZ;
    private final int[] movedAt;
    private final int cap;
    // by measured tick
    private final short[] staleSections, stalePaths, stuck, pit, pitWants, pitNoWay, failed, queue;
    private final long[] hook, hookCalls;
    private final boolean[] layersFresh;
    private final int[] searches, failures, jumps, kept;
    private final int[] broken = new int[BOMBS.length];

    CraterWatch(Arena arena, List<Mob> mobs, int cap) {
        this.arena = arena;
        this.mobs = mobs;
        this.cap = cap;
        bots = new AiBot[mobs.size()];
        anchorX = new double[mobs.size()];
        anchorZ = new double[mobs.size()];
        movedAt = new int[mobs.size()];
        for (int i = 0; i < bots.length; i++) {
            bots[i] = AiBots.get(mobs.get(i));
            anchorX[i] = mobs.get(i).getX();
            anchorZ[i] = mobs.get(i).getZ();
        }
        staleSections = new short[cap];
        stalePaths = new short[cap];
        stuck = new short[cap];
        pit = new short[cap];
        pitWants = new short[cap];
        pitNoWay = new short[cap];
        queue = new short[cap];
        hook = new long[cap];
        hookCalls = new long[cap];
        TacticalMap.costOn = true;
        failed = new short[cap];
        layersFresh = new boolean[cap];
        searches = new int[cap];
        failures = new int[cap];
        jumps = new int[cap];
        kept = new int[cap];
    }

    /** before measured tick n: its change, in the tick, so its time is the tick's */
    void pre(int n) {
        for (int j = 0; j < BOMBS.length; j++)
            if (BOMBS[j] == n) {
                int before = arena.blocksAround(Arena.CRATERS[j]);
                arena.crater(j);
                broken[j] = before - arena.blocksAround(Arena.CRATERS[j]);
            }
        if (n >= DIG && n < DIG + Arena.TRENCH_LENGTH) arena.dig(n - DIG);
    }

    /** after measured tick n */
    void record(int n) {
        if (n >= cap) return;
        TacticalMap map = TacticalMap.peek(arena.level);
        AiTerrain terrain = AiTerrain.peek(arena.level);
        staleSections[n] = (short) (map == null ? 0 : map.staleCount());
        queue[n] = (short) (map == null ? 0 : Math.min(Short.MAX_VALUE, map.queued() + map.inFlight()));
        hook[n] = TacticalMap.hookNanos;
        hookCalls[n] = TacticalMap.hookCalls;
        layersFresh[n] = terrain == null || terrain.settled();
        int s = 0, f = 0, j = 0, k = 0;
        for (int i = 0; i < bots.length; i++) {
            AiBot bot = bots[i];
            Mob m = mobs.get(i);
            if (bot == null || m.isRemoved()) continue;
            s += bot.mover.searches;
            f += bot.mover.failures;
            j += bot.mover.stuckJumps;
            k += bot.mover.kept;
            if (map != null && bot.mover.stale(map)) stalePaths[n]++;
            if (m.getY() <= arena.ground - 1.5) {
                pit[n]++;
                if (bot.mover.wants()) pitWants[n]++;
                if (bot.mover.noWay()) pitNoWay[n]++;
            }
            if (bot.mover.failed()) failed[n]++;
            double dx = m.getX() - anchorX[i], dz = m.getZ() - anchorZ[i];
            if (!bot.mover.wants() || dx * dx + dz * dz >= 1) {
                anchorX[i] = m.getX();
                anchorZ[i] = m.getZ();
                movedAt[i] = n;
            } else if (n - movedAt[i] >= STUCK) stuck[n]++;
        }
        searches[n] = s;
        failures[n] = f;
        jumps[n] = j;
        kept[n] = k;
    }

    /** the phase's rows, n ticks measured */
    String rows(String phase, int round, long[] nanos, int n) {
        TacticalMap.costOn = false;
        StringBuilder b = new StringBuilder();
        for (int j = 0; j < BOMBS.length; j++)
            row(b, phase, round, "бомба" + (j + 1), BOMBS[j], BOMBS[j], Arena.CRATERS[j][0], Arena.CRATERS[j][1], broken[j], nanos, n);
        row(b, phase, round, "траншея", DIG, DIG + Arena.TRENCH_LENGTH - 1, Arena.TRENCH_X, Arena.TRENCH_Z,
                Arena.TRENCH_LENGTH * Arena.TRENCH_DEPTH, nanos, n);
        return b.toString();
    }

    // a change from tick `from` to tick `to` of the measurement: its cost is the worst of its ticks, the rest is counted from its last
    private void row(StringBuilder b, String phase, int round, String name, int from, int to, int x, int z, int brokenBlocks, long[] nanos, int n) {
        if (to >= n) return;
        long event = 0, after = 0;
        for (int i = from; i <= to; i++) event = Math.max(event, nanos[i]);
        for (int i = to + 1; i <= Math.min(n - 1, to + AiBench.AFTER); i++) after = Math.max(after, nanos[i]);
        double before = 0;
        int b0 = Math.max(0, from - AiBench.AFTER);
        for (int i = b0; i < from; i++) before += nanos[i];
        int end = Math.min(n - 1, to + WATCH);
        int staleBefore = 0, queueBefore = 0, pathBefore = 0, freshBefore = 0, queueMax = 0;
        for (int i = b0; i < from; i++) {
            staleBefore = Math.max(staleBefore, staleSections[i]);
            queueBefore = Math.max(queueBefore, queue[i]);
            pathBefore = Math.max(pathBefore, stalePaths[i]);
            if (layersFresh[i]) freshBefore++;
        }
        for (int i = from; i <= Math.min(n - 1, to + WATCH); i++) queueMax = Math.max(queueMax, queue[i]);
        long hookCost = hook[to] - (from > 0 ? hook[from - 1] : 0), hookCount = hookCalls[to] - (from > 0 ? hookCalls[from - 1] : 0);
        int staleMax = 0, pathMax = 0, pathTicks = 0, stuckMax = 0, pitMax = 0;
        // the map is fresh again at the first tick with no stale section: all the change's are built again then (the
        // fight's own changes, a block a tick or so, make a few stale again right after)
        int mapFresh = -1, layers = -1;
        for (int i = from; i <= end; i++) {
            staleMax = Math.max(staleMax, staleSections[i]);
            pathMax = Math.max(pathMax, stalePaths[i]);
            pathTicks += stalePaths[i];
            stuckMax = Math.max(stuckMax, stuck[i]);
            pitMax = Math.max(pitMax, pit[i]);
            if (i < to) continue;
            if (layers < 0 && layersFresh[i]) layers = i - to;
            if (mapFresh < 0 && staleSections[i] == 0) mapFresh = i - to;
        }
        int s100 = Math.min(n - 1, to + 100), p100 = Math.max(0, from - 100);
        b.append(String.join("\t", phase, Integer.toString(round), name, Integer.toString(from), Integer.toString(x), Integer.toString(z),
                Integer.toString(brokenBlocks), ms(event), ms(hookCost), Long.toString(hookCount), ms(after), from > b0 ? ms(before / (from - b0)) : "-", Integer.toString(staleBefore), Integer.toString(staleMax),
                Integer.toString(queueBefore), Integer.toString(queueMax),
                mapFresh < 0 ? ">" + (end - to) : Integer.toString(mapFresh),
                from > b0 ? String.format(Locale.ROOT, "%.2f", freshBefore / (double) (from - b0)) : "-", layers < 0 ? ">" + (end - to) : Integer.toString(layers),
                Integer.toString(pathBefore), Integer.toString(pathMax), Integer.toString(pathTicks), Integer.toString(searches[from] - searches[p100]),
                Integer.toString(searches[s100] - searches[to]), Integer.toString(kept[s100] - kept[to]),
                Integer.toString(failures[end] - failures[to]),
                Integer.toString(jumps[end] - jumps[to]), Integer.toString(stuckMax), Integer.toString(pitMax), Integer.toString(pit[end]), Integer.toString(pitWants[end]), Integer.toString(pitNoWay[end]),
                Integer.toString(failed[end]))).append('\n');
    }

    private static String ms(double nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1e6);
    }

    /** the mobs in a hole now, each with where it is and what its legs are at: for the log */
    List<String> holed() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < bots.length; i++) {
            Mob m = mobs.get(i);
            if (bots[i] == null || m.isRemoved() || m.getY() > arena.ground - 1.5) continue;
            String way = "";
            TacticalMap map = TacticalMap.peek(arena.level);
            if (map != null && !bots[i].mover.idle() && !bots[i].mover.arrived()) {
                long start = acup.gunfeel.ai.PathSearch.snap(map, m.getX(), m.getY(), m.getZ());
                for (int nodes : new int[] {3000, 60000}) {
                    var r = acup.gunfeel.ai.PathSearch.find(map, start, bots[i].mover.goal(), 1.5, nodes, acup.gunfeel.ai.PathSearch.Cost.NONE);
                    way += String.format(Locale.ROOT, " | до %d узлов: клеток %d%s, раскрыто %d, нет секций %d", nodes, r.cells.length,
                            r.reached ? " дошёл" : "", r.expanded, r.missing.length);
                }
                if (start == Long.MIN_VALUE) way += " | нет клетки под ногами";
            }
            out.add(String.format(Locale.ROOT, "%.1f %.1f %.1f (земля %d) %s%s%s", m.getX(), m.getY(), m.getZ(), arena.ground,
                    bots[i].mover.state(), bots[i].mover.arrived() ? " arrived" : "", way));
        }
        return out;
    }

    /** the phase's line in the main table: the worst over the whole measurement */
    String note(int n) {
        int stuckMax = 0, pitMax = 0, pathMax = 0;
        for (int i = 0; i < Math.min(n, cap); i++) {
            stuckMax = Math.max(stuckMax, stuck[i]);
            pitMax = Math.max(pitMax, pit[i]);
            pathMax = Math.max(pathMax, stalePaths[i]);
        }
        List<String> parts = new ArrayList<>();
        parts.add("воронка: застряло на 5 с до " + stuckMax);
        parts.add("в яме до " + pitMax);
        parts.add("на старом пути до " + pathMax);
        return String.join(", ", parts);
    }
}
