package acup.gunfeel;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

// port/continuum: a stand-in for Structures (NTM NEXT's multiblock cores, doors and lifts; Continuum folds its cores
// and names its block entities apart). The cells of a machine are what the client captured of its drawn model
// (MeshShapes, MixinHbmVisual: the machine mesh system), which this node does not have yet: no machine is known, bullets
// and holes meet them as NTM has them. NTM's wooden trench blocks are known (WoodBoxes, main's, as on 26.2).
public final class Structures {
    private Structures() {
    }

    public static long @Nullable [] cells(Level level, BlockPos pos, BlockState state) {
        return WoodBoxes.cells(level, pos, state);
    }

    public static @Nullable BlockHitResult solid(Level level, BlockPos pos, BlockState state, Vec3 from, Vec3 to) {
        return null;
    }

    // no machine's cells are read from what is drawn yet (MeshShapes then finds none)
    public static boolean taken(BlockState coreState) {
        return false;
    }

    public static boolean atRest(Level level, BlockPos core, BlockState coreState) {
        return true;
    }

    public static int layer(BlockState coreState, int dy) {
        return dy;
    }

    public static boolean rendered(BlockState coreState) {
        return true;
    }

    // as src/main/java Structures: turns the core's facing to north
    public static Rotation toNorth(Direction facing) {
        return switch (facing) {
            case EAST -> Rotation.COUNTERCLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.CLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    public static String key(BlockState coreState, int dx, int dy, int dz) {
        return PassableClip.key(coreState) + "@" + dx + "," + dy + "," + dz;
    }

    // as src/main/java Structures: a cell (x, z) of a block turned by r, as BlockPos.rotate turns the block: {x, z}
    public static int[] turn(int x, int z, Rotation r) {
        return switch (r) {
            case CLOCKWISE_90 -> new int[] {15 - z, x};
            case CLOCKWISE_180 -> new int[] {15 - x, 15 - z};
            case COUNTERCLOCKWISE_90 -> new int[] {z, 15 - x};
            default -> new int[] {x, z};
        };
    }

    static void clear() {
    }

    static String debug(Level level, BlockPos pos, BlockState state) {
        return "";
    }
}
