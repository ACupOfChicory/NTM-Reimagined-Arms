package acup.gunfeel.ai;

import acup.gunfeel.ReaimTimes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Doors on an AI mob's way. A shut door or gate that a hand opens (wooden doors, fence gates; not iron doors, not NTM's,
 * not trapdoors) is a cell of the map like any other (CellBuilder; the voxel still hides and stops as the shut door
 * does), a path goes through it (PathSearch), and the mob opens it as it comes up to it ({@link #ahead}). It is left
 * open.
 */
public final class AiDoors {
    /** the server setting: AI mobs open doors and gates on their way */
    public static final String KEY = "aiDoors";
    /** off only for the stands' "before" */
    static boolean on = true;
    /** whether a path may go through a shut door: the setting, for the path search on its own threads */
    static volatile boolean pass = true;
    /** within this of a shut door on its path, blocks, it opens it */
    static final double REACH = 2.0;
    /** the stands: doors and gates opened */
    public static int opened;

    private AiDoors() {
    }

    /** every server tick (AiBots.tick) */
    static void tick() {
        pass = on && ReaimTimes.flag(KEY, true);
    }

    /** a door or a gate a hand opens, shut or open */
    public static boolean byHand(BlockState state) {
        return state.getBlock() instanceof DoorBlock d && d.type().canOpenByHand() || state.getBlock() instanceof FenceGateBlock;
    }

    /** a shut door in the next steps of its path, near enough: opened */
    static void ahead(ServerLevel level, Mob mob) {
        if (!pass) return;
        Path p = mob.getNavigation().getPath();
        if (p == null || p.isDone()) return;
        int from = p.getNextNodeIndex();
        for (int i = from; i < Math.min(p.getNodeCount(), from + 2); i++) {
            BlockPos at = p.getNodePos(i);
            if (at.distToCenterSqr(mob.getX(), mob.getY() + 0.5, mob.getZ()) > REACH * REACH) continue;
            BlockState state = level.getBlockState(at);
            if (!byHand(state)) continue;
            if (state.getBlock() instanceof DoorBlock d) {
                if (state.getValue(DoorBlock.OPEN)) continue;
                d.setOpen(mob, level, state, at, true);
            } else {
                if (state.getValue(FenceGateBlock.OPEN)) continue;
                level.setBlock(at, state.setValue(FenceGateBlock.OPEN, true), 10);
                level.levelEvent(null, 1008, at, 0);
                level.gameEvent(mob, GameEvent.BLOCK_OPEN, at);
            }
            opened++;
        }
    }
}
