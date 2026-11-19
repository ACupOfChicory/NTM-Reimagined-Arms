package acup.gunfeel;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.host.NtmFeature;
import acup.gunfeel.host.Ports;
import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeGrid;
import acup.gunfeel.smoke.SmokeMask;
import net.minecraft.world.level.Level;

/**
 * 26.2's wiring: the one place that says which of 26.2's classes core's ports are. Core finds it as its root
 * (META-INF/services/acup.gunfeel.host.Ports, {@link CoreServices}) and takes each port from it once; a system built
 * per level or per player gets its ports here, into its constructor. Nothing here does anything itself: making an
 * implementation loads none of the game's classes, so core may ask while its own classes are still being set up.
 */
public final class Wiring implements Ports {
    private final Map<Class<?>, Object> ports = Map.of(
            GameDirs.class, new FmlGameDirs(),
            TestLog.Printer.class, new TestLogPrint(),
            Config.Reread.class, new PistolHoldReread(),
            ReaimTimes.Reload.class, new ServerTablesReload(),
            ServerSettings.Follow.class, new RemoteShapes(),
            MainHand.class, new GameMainHand());

    @Override
    public <T> @Nullable T port(Class<T> type) {
        Object p = ports.get(type);
        return p == null ? null : type.cast(p);
    }

    /** NTM NEXT on 26.2 has all of it. */
    @Override
    public Set<NtmFeature> ntm() {
        return EnumSet.allOf(NtmFeature.class);
    }

    // ---- the systems' ports

    /** The smoke's walls in a level: which cells of a cloud are closed to smoke ({@link SmokeMask}). */
    public static Function<SmokeClouds.Cloud, SmokeGrid.Mask> smokeWalls(Level level) {
        return c -> SmokeMask.of(level, c);
    }
}
