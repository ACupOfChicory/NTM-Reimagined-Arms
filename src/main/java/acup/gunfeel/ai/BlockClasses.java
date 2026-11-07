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

    /** a block covering this much of a side, seen along x or z, hides what is behind it and stops bullets in full */
    static final double COVERS = 0.6;
    /** a part this thin or thinner along the look is seen through (a fence's outline is a quarter thick, a stone wall's three eighths) */
    static final double THICK = 0.3;

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
        double volume = 0, face = 0;
        if (lava) move = Voxel.TALL;
        else if (shape.isEmpty()) move = Voxel.OPEN;
        else {
            double top = shape.max(Direction.Axis.Y);
            for (AABB box : shape.toAabbs()) volume += box.getXsize() * box.getYsize() * box.getZsize();
            // what covers a side is what is drawn, not what is bumped into (a fence's collision is a solid board
            // a block and a half high, its look a post and two rails)
            VoxelShape look;
            try {
                look = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            } catch (RuntimeException e) {
                look = shape;
            }
            double fx = 0, fz = 0;
            for (AABB box : look.toAabbs()) {
                // only parts thicker than THICK along the look count (a pane, bars or a fence are seen through), and
                // only their height within the block (a fence's post stands taller)
                double h = Math.max(0, Math.min(1, box.maxY) - Math.max(0, box.minY));
                if (box.getXsize() >= THICK) fx += h * box.getZsize();
                if (box.getZsize() >= THICK) fz += h * box.getXsize();
            }
            // how much of a side it covers, seen along x or along z, whichever more (a row of sandbags half a block
            // thick covers a whole side; a lone sandbag's post half of one)
            face = Math.min(1, Math.max(fx, fz));
            move = top <= 0.2 ? Voxel.OPEN : top <= 0.5 ? Voxel.LOW : top <= 1.0 ? Voxel.SOLID : Voxel.TALL;
        }
        if (door && state.hasProperty(BlockStateProperties.OPEN) && state.getValue(BlockStateProperties.OPEN)) move = Voxel.OPEN;
        boolean blocks = move == Voxel.SOLID || move == Voxel.TALL;
        int protect = 0;
        boolean glass = false;
        if (blocks && !lava && !BulletRules.through(state)) {
            var material = BulletRules.material(state).material();
            glass = material.glass();
            protect = switch (material) {
                case NONE, GLASS, SOFT -> 0;
                case WOOD, TOUGH_GLASS -> 1;
                case SOIL, STONE, METAL, MACHINE, REINFORCED_GLASS -> 2;
                case CONCRETE, STEEL, LAMINATE, UNBREAKABLE -> 3;
            };
            // a pane, a post, a fence: covering little of a side, it stops less
            if (face < COVERS) protect = Math.max(0, protect - 1);
        }
        // hides: an opaque block, leaves, or a solid one with a model of its own that stops bullets (sandbags, NTM's
        // machines: the game does not take their models as hiding); glass of any kind, bars, panes and fences do not
        boolean sight = blocks && face >= COVERS && (state.canOcclude() || state.is(BlockTags.LEAVES) || protect >= 1 && !glass);
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
