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
        this(client());
    }

    /**
     * The ports of a client ({@code client}: its classes there) or of a dedicated server. A port of the client's is
     * named through a holder of its own called only on the client (RenderPortClient): a class of the client's named
     * here would be loaded on the server too, which has none of them, and the server would not start (ServerLoadTest).
     */
    Wiring(boolean client) {
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
        put(acup.gunfeel.host.BodyControl.class, new EntityBodyControl());
        // ---- water (its world comes per level: liquids below)
        // ---- other: hitboxes, GL-54, HUD, tacz, NTM's guns, items
        put(acup.gunfeel.host.GamePause.class, new ClientPause());
        put(acup.gunfeel.host.NtmGuns.class, new NtmGunsPort());
        put(acup.gunfeel.host.ItemTable.class, new ItemNames());
        put(acup.gunfeel.host.EntityTypeTable.class, new EntityTypeNames());
        put(acup.gunfeel.host.Hurts.class, new HurtsPort());
        put(acup.gunfeel.host.CapNow.class, new DamageCap.Reads());
        put(acup.gunfeel.host.NtmBullets.class, new DamageCap.Reads());
        put(acup.gunfeel.host.ArmorSuits.class, new ArmorSuitsPort());
        put(acup.gunfeel.host.HurtNow.class, new HurtNowPort());
        put(acup.gunfeel.host.FinalShots.class, new BalanceFinal.Reads());
        put(acup.gunfeel.host.Worlds.Port.class, new WorldsPort());
        put(acup.gunfeel.host.RoundTable.class, new RoundNames());
        put(acup.gunfeel.host.PierceShots.class, new PierceSteps.Reads());
        put(acup.gunfeel.host.AmmoCatalog.class, new acup.gunfeel.ammo.NewAmmo.Reads());
        put(acup.gunfeel.host.GunBalance.class, new BalanceGuns.Reads());
        // ---- the client's only (render: the bullet holes)
        if (client) put(acup.gunfeel.host.render.RenderPort.class, RenderPortClient.port());
    }

    // the side FML runs: a client, unless it is a dedicated server; no FML (the tests) a client's, whose classes the
    // tests have
    static boolean client() {
        var fml = net.neoforged.fml.loading.FMLLoader.getCurrentOrNull();
        return fml == null || fml.getDist().isClient();
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
    /** A level for core: its blocks by the game's one table ({@link BlockStates}), its tick, side, ray, shapes and room. */
    public static WorldView world(Level level) {
        return new LevelWorld(level);
    }

    // water
    /** The liquids of a level (or of any blocks) for the water's path ({@link LiquidPath#trace}). */
    public static LiquidPath.Fluids liquids(BlockGetter level) {
        return acup.gunfeel.water.LevelFluids.of(level);
    }
}
