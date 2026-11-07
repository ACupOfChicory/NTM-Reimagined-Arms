package acup.gunfeel.ai;

import acup.gunfeel.BulletRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A block state's {@link Voxel} byte, worked out once per state on the server thread: movement from its collision
 * box, sight from whether it hides what is behind it, protection from the material the bullets meet in it
 * ({@link BulletRules#material}). Starts over when the bullet rules are read again.
 */
public final class BlockClasses {
    private BlockClasses() {
    }

    private static byte[] values = new byte[0];
    private static boolean[] known = new boolean[0];
    private static int rulesGeneration = Integer.MIN_VALUE;

    /** server thread only */
    public static byte of(BlockState state) {
        int generation = BulletRules.generation();
        if (generation != rulesGeneration) {
            rulesGeneration = generation;
            values = new byte[0];
            known = new boolean[0];
        }
        int id = Block.BLOCK_STATE_REGISTRY.getId(state);
        if (id < 0) return classify(state);
        if (id >= values.length) {
            int size = Math.max(id + 1, Math.max(1024, values.length * 2));
            values = java.util.Arrays.copyOf(values, size);
            known = java.util.Arrays.copyOf(known, size);
        }
        if (!known[id]) {
            values[id] = classify(state);
            known[id] = true;
        }
        return values[id];
    }

    static byte classify(BlockState state) {
        FluidState fluid = state.getFluidState();
        boolean water = !fluid.isEmpty() && fluid.is(FluidTags.WATER);
        boolean lava = !fluid.isEmpty() && fluid.is(FluidTags.LAVA);
        boolean door = state.is(BlockTags.DOORS) || state.is(BlockTags.FENCE_GATES) || state.is(BlockTags.TRAPDOORS)
                || state.getBlock().getClass().getSimpleName().contains("Door");
        boolean climb = state.is(BlockTags.CLIMBABLE);
        VoxelShape shape;
        try {
            shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            // a block that needs its world to tell its shape: a stone wall, as unknown
            return Voxel.UNKNOWN;
        }
        int move;
        double volume = 0;
        if (lava) move = Voxel.TALL;
        else if (shape.isEmpty()) move = Voxel.OPEN;
        else {
            double top = shape.max(Direction.Axis.Y);
            for (AABB box : shape.toAabbs()) volume += box.getXsize() * box.getYsize() * box.getZsize();
            move = top <= 0.2 ? Voxel.OPEN : top <= 0.5 ? Voxel.LOW : top <= 1.0 ? Voxel.SOLID : Voxel.TALL;
        }
        if (door && state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN)) move = Voxel.OPEN;
        boolean blocks = move == Voxel.SOLID || move == Voxel.TALL;
        boolean sight = blocks && volume >= 0.5 && (state.canOcclude() || state.is(BlockTags.LEAVES));
        int protect = 0;
        if (blocks && !lava && !BulletRules.through(state)) {
            protect = switch (BulletRules.material(state).material()) {
                case NONE, GLASS, SOFT -> 0;
                case WOOD, TOUGH_GLASS -> 1;
                case SOIL, STONE, METAL, MACHINE, REINFORCED_GLASS -> 2;
                case CONCRETE, STEEL, LAMINATE, UNBREAKABLE -> 3;
            };
            // a pane, a door, a fence: thin, it stops less
            if (volume < 0.5) protect = Math.max(0, protect - 1);
        }
        return Voxel.of(move, sight, protect, water, climb, door);
    }

    /** for the tests and /armsai: the name of a voxel byte */
    public static String describe(int v) {
        String move = switch (Voxel.move(v)) {
            case Voxel.OPEN -> "open";
            case Voxel.SOLID -> "solid";
            case Voxel.TALL -> "tall";
            default -> "low";
        };
        return move + (Voxel.sight(v) ? " sight" : "") + " protect" + Voxel.protect(v)
                + ((v & Voxel.WATER) != 0 ? " water" : "") + ((v & Voxel.CLIMB) != 0 ? " climb" : "")
                + ((v & Voxel.DOOR) != 0 ? " door" : "");
    }
}
