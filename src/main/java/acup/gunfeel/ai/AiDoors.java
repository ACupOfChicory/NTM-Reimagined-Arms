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
 * Doors on an AI mob's way. A shut door, gate or trapdoor (a hatch) that a hand opens (wooden doors, fence gates,
 * wooden trapdoors and NTM's steel one; not iron doors, not NTM's doors) is a cell of the map like any other (CellBuilder; the voxel still hides and stops as the shut door
 * does), a path goes through it (PathSearch), and the mob opens it as it comes up to it ({@link #ahead}). It is left
 * open.
 */
public final class AiDoors {
    /** the server setting: AI mobs open doors and gates on their way */
    public static final String KEY = "aiDoors";
    /** off only for the stands' "before" */
    static boolean on = true;
    /** within this of a shut door on its path, blocks, it opens it */
    static final double REACH = 2.0;
    /** its hand goes to the door and back in this many ticks (AiPose.DOOR) */
    static final int HAND_TICKS = 10;
    /** the stands: doors and gates opened */
    public static int opened;

    private AiDoors() {
    }

    /** every server tick (AiBots.tick) */
    static void tick() {
        PathSearch.doors = on && ReaimTimes.flag(KEY, true);
    }

    /** a door or a gate a hand opens, shut or open */
    public static boolean byHand(BlockState state) {
        return state.getBlock() instanceof DoorBlock d && d.type().canOpenByHand() || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock t && ((acup.gunfeel.mixin.mc.TrapDoorAccess) t).gunfeel$type().canOpenByHand();
    }

    /** a shut door in the next steps of its path, near enough: opened */
    static void ahead(ServerLevel level, Mob mob) {
        if (!PathSearch.doors) return;
        Path p = mob.getNavigation().getPath();
        if (p == null || p.isDone()) return;
        int from = p.getNextNodeIndex();
        for (int i = from; i < Math.min(p.getNodeCount(), from + 2); i++) {
            BlockPos at = p.getNodePos(i);
            // the way goes down: a hatch shut under its feet (it stands on it) is lifted (the next point under the
            // hatch may be further than REACH: a shaft's top rung)
            // (straight down: the next point in its own column, a ladder or open air under the hatch)
            if (at.getY() < mob.getBlockY() && at.getX() == mob.getBlockX() && at.getZ() == mob.getBlockZ()) {
                BlockPos under = BlockPos.containing(mob.getX(), mob.getY() - 0.1, mob.getZ());
                if (level.getBlockState(under).getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock && shaftUnder(level, under)) open(level, mob, under);
            }
            // the way goes up out of a shaft: a hatch shut over its head is lifted
            if (at.getY() > mob.getBlockY() && mob.onClimbable())
                for (int up = 1; up <= 2; up++) {
                    BlockPos over = mob.blockPosition().above(up);
                    if (level.getBlockState(over).getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock) open(level, mob, over);
                }
            if (at.distToCenterSqr(mob.getX(), mob.getY() + 0.5, mob.getZ()) > REACH * REACH) continue;
            open(level, mob, at);
            // a hatch over the top rung of a ladder: at the head of the cell (climbing up to it, or going down into it)
            BlockPos over = at.above();
            if (level.getBlockState(over).getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock && shaftUnder(level, over)) open(level, mob, over);
        }
    }

    /** under a hatch a shaft goes down: a ladder (or vines) or open air, not a floor it is a lid on for show */
    private static boolean shaftUnder(ServerLevel level, BlockPos hatch) {
        BlockState below = level.getBlockState(hatch.below());
        return below.is(net.minecraft.tags.BlockTags.CLIMBABLE) || below.getCollisionShape(level, hatch.below()).isEmpty();
    }

    /** the shut door or gate a hand opens here: opened by the mob; whether there was one */
    static boolean open(ServerLevel level, Mob mob, BlockPos at) {
        if (!PathSearch.doors) return false;
        BlockState state = level.getBlockState(at);
        if (!byHand(state)) return false;
        if (state.getBlock() instanceof DoorBlock d) {
            if (state.getValue(DoorBlock.OPEN)) return false;
            d.setOpen(mob, level, state, at, true);
        } else if (state.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock t) {
            // a hatch: lifted as a hand lifts it
            if (state.getValue(net.minecraft.world.level.block.TrapDoorBlock.OPEN)) return false;
            level.setBlock(at, state.setValue(net.minecraft.world.level.block.TrapDoorBlock.OPEN, true), 2);
            level.playSound(null, at, ((acup.gunfeel.mixin.mc.TrapDoorAccess) t).gunfeel$type().trapdoorOpen(), net.minecraft.sounds.SoundSource.BLOCKS, 1F,
                    level.getRandom().nextFloat() * 0.1F + 0.9F);
            level.gameEvent(mob, GameEvent.BLOCK_OPEN, at);
        } else {
            if (state.getValue(FenceGateBlock.OPEN)) return false;
            level.setBlock(at, state.setValue(FenceGateBlock.OPEN, true), 10);
            level.levelEvent(null, 1008, at, 0);
            level.gameEvent(mob, GameEvent.BLOCK_OPEN, at);
        }
        opened++;
        AiBot bot = AiBots.get(mob);
        if (bot != null) bot.gesture(AiPose.DOOR, HAND_TICKS, level.getGameTime());
        return true;
    }
}
