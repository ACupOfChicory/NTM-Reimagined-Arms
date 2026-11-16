package acup.gunfeel.ai;

import java.util.concurrent.locks.LockSupport;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * A stand's server in sped-up time (the game's own /tick sprint): it ticks as fast as it can instead of 20 times a
 * second. What the AI hands its background threads (paths, the map's cells) still gets at least a real tick's time:
 * at the end of each tick the server waits until those threads are idle and every chunk a stage forced ticks its mobs,
 * at most until a real tick (50 ms) from the tick's start. A tick of the stand so sees the same background work done
 * as it would in real time, or more; the work's own cost (us a query, a look) is measured on its own and does not
 * change.
 * Only for stands that count ticks; a stand that measures ms a tick (the AI bench) stays in real time.
 */
public final class StandSprint {
    private static final long REAL_TICK = 50_000_000L;
    private static long tickStart;
    private static long started, ticks, waitedNanos;
    private static boolean on;

    private StandSprint() {
    }

    /** sped up (sprint) or at 20 ticks a second; either way the run's ticks and time are counted for info() */
    public static void start(MinecraftServer server, boolean sprint) {
        if (started != 0) return;
        on = sprint;
        started = System.nanoTime();
        if (sprint) server.tickRateManager().requestGameToSprint(Integer.MAX_VALUE);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ServerTickEvent.Pre.class, e -> tickStart = System.nanoTime());
        // the last listener of the tick: after the stages and the mobs have asked for their background work
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ServerTickEvent.Post.class, e -> {
            ticks++;
            if (!on) return;
            long t0 = System.nanoTime(), until = tickStart + REAL_TICK;
            while ((AiThreads.busy() || loading(e.getServer())) && System.nanoTime() < until) LockSupport.parkNanos(20_000L);
            waitedNanos += System.nanoTime() - t0;
        });
    }

    /**
     * whether a chunk a stage forced is not yet ticking its mobs: the loading runs on the game's own threads, and a
     * stage that forces its field and spawns on it at once would have its mobs stand still for many sped-up ticks
     */
    private static boolean loading(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels())
            for (long c : level.getForceLoadedChunks())
                if (!level.isPositionEntityTicking(ChunkPos.unpack(c).getWorldPosition())) return true;
        return false;
    }

    public static boolean on() {
        return on;
    }

    /** the run so far: ticks, seconds, ticks a second, the share of the time spent waiting for the background and the chunks */
    public static String info() {
        double s = (System.nanoTime() - started) / 1e9;
        return String.format(java.util.Locale.ROOT, "%s: %d ticks in %.0f s, %.0f ticks a second, %.0f%% of it waiting for the AI's threads and the forced chunks",
                on ? "sprint" : "real time", ticks, s, ticks / Math.max(1e-9, s), 100.0 * waitedNanos / 1e9 / Math.max(1e-9, s));
    }
}
