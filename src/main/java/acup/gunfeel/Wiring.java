package acup.gunfeel;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.compat.CompatVersions;
import acup.gunfeel.host.BlockTable;
import acup.gunfeel.host.HostVersions;
import acup.gunfeel.host.NtmFeature;
import acup.gunfeel.host.Ports;
import acup.gunfeel.host.WorldView;
import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeGrid;
import acup.gunfeel.smoke.SmokeMask;
import acup.gunfeel.water.LiquidPath;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

/**
 * 26.2's wiring: the one place that says which of 26.2's classes core's ports are. Core finds it as its root
 * (META-INF/services/acup.gunfeel.host.Ports, {@link CoreServices}) and takes each port from it once; a system built
 * per level or per player gets its ports here, into its constructor. Nothing here does anything itself: making an
 * implementation loads none of the game's classes, so core may ask while its own classes are still being set up.
 */
public final class Wiring implements Ports {
    private final Map<Class<?>, Object> ports = new HashMap<>();

    // Each system's ports in its own block: a new port is a line in its system's block (notes/ports-base/RULES.md).
    public Wiring() {
        // ---- core's nodes: folders, the test log, the settings' rereads, the versions
        put(GameDirs.class, new FmlGameDirs());
        put(TestLog.Printer.class, new TestLogPrint());
        put(Config.Reread.class, new PistolHoldReread());
        put(ReaimTimes.Reload.class, new ServerTablesReload());
        put(ServerSettings.Follow.class, new RemoteShapes());
        put(HostVersions.class, new CompatVersions());
        // ---- arms and recoil
        put(MainHand.class, new GameMainHand());
        put(GunAnimState.class, new NtmAnimState());
        // ---- ballistics and balance (the world comes per level: world below)
        put(BlockTable.class, new BlockStates());
        // ---- water (its world comes per level: liquids below)
        // ---- other: hitboxes, GL-54, HUD, tacz, NTM's guns
        put(acup.gunfeel.host.GamePause.class, new ClientPause());
        put(acup.gunfeel.host.NtmGuns.class, new acup.gunfeel.ai.AiGunner.Guns());
    }

    // one implementation each: a second for the same port is a mistake of this file
    private <T> void put(Class<T> type, T implementation) {
        if (ports.put(type, implementation) != null) throw new IllegalStateException("two implementations of " + type.getName());
    }

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

    // ---- the systems' ports per level or per player, given into their constructors

    // smoke
    /** The smoke's walls in a level: which cells of a cloud are closed to smoke ({@link SmokeMask}). */
    public static Function<SmokeClouds.Cloud, SmokeGrid.Mask> smokeWalls(Level level) {
        return c -> SmokeMask.of(level, c);
    }

    // ballistics and balance
    /** The blocks of a level (or of any blocks) for core, by the game's one table ({@link BlockStates}). */
    public static WorldView world(BlockGetter level) {
        return new LevelWorld(level);
    }

    // water
    /** The liquids of a level (or of any blocks) for the water's path ({@link LiquidPath#trace}). */
    public static LiquidPath.Fluids liquids(BlockGetter level) {
        return acup.gunfeel.water.LevelFluids.of(level);
    }
}
