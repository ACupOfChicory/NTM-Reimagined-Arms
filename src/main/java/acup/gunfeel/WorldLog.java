package acup.gunfeel;

import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** The test log (TestLog) by a level's side, and the block's bullet material as a line names it: 26.2's part of it. */
public final class WorldLog {
    private WorldLog() {
    }

    public static boolean on(Level level) {
        return level.isClientSide() ? TestLog.client : TestLog.server;
    }

    // by the level's side, for the bullet the thread works on
    public static void at(Level level, String event, Object... kv) {
        if (on(level)) TestLog.write(level.isClientSide() ? "C" : "S", TestLog.shot(), event, kv);
    }

    // by the level's side, for a given bullet (or -1)
    public static void at(Level level, int shot, String event, Object... kv) {
        if (on(level)) TestLog.write(level.isClientSide() ? "C" : "S", shot, event, kv);
    }

    // the block's bullet material as piercing and effects have it (a multiblock cell: its machine's)
    // as material(level, pos), and a machine's window (MachineGlass) as "window"
    public static String material(Level level, net.minecraft.world.phys.BlockHitResult hit) {
        return hit instanceof ModelSurface.GlassHit ? "window" : material(level, hit.getBlockPos());
    }

    public static String material(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return BulletRules.material(Cells.ruleState(level, pos, state)).material().name().toLowerCase(Locale.ROOT);
    }
}
