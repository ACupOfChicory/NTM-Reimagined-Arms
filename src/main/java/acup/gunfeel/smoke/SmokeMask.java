package acup.gunfeel.smoke;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Which cells of a cloud are closed to smoke, read from the world (the same on the server and a client): a block whose
 * collision fills the cell, a liquid (smoke over water, not in it), a shut door or trapdoor, a block tagged
 * {@code ntmgunfeel:blocks_smoke} (glass panes and walls: thin, but a row of them is a wall), and a cell in a chunk
 * that is not loaded. A cloud reads all its cells when it is born and a twentieth of them again each step after.
 *
 * <p>A door is any {@link DoorBlock} or {@link TrapDoorBlock} (NTM's metal, office, bunker and red doors and its steel
 * trapdoor are those, but not in the vanilla tags), a block in the tags, and a block of NTM's with a boolean
 * {@code open} of its own: its multiblock doors (sliding, fire, seal, airlock, vault, blast door, silo hatch...) keep
 * one on the core and on every cell, frame included, and a shut one is a thin panel, not a full block.
 */
public final class SmokeMask implements SmokeGrid.Mask {
    /** A cloud reads a twentieth of its cells again each step: all of them once a second. */
    public static final int PARTS = SmokeCloudSet.PARTS;
    public static final TagKey<Block> BLOCKS_SMOKE = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("ntmgunfeel", "blocks_smoke"));

    private final Level level;
    private final int baseX, baseY, baseZ;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    private SmokeMask(Level level, int baseX, int baseY, int baseZ) {
        this.level = level;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
    }

    /** The cloud's cells by the box it was born with (a box that moved downwind asks with its shift added). */
    public static SmokeMask of(Level level, SmokeClouds.Cloud cloud) {
        return new SmokeMask(level, cloud.origin.getX() - SmokeGrid.OX, cloud.origin.getY() - cloud.grid.oy(), cloud.origin.getZ() - SmokeGrid.OZ);
    }

    @Override
    public boolean closed(int x, int y, int z) {
        pos.set(baseX + x, baseY + y, baseZ + z);
        return closed(level, pos);
    }

    /**
     * A roof over the column above the box: the highest block that stops movement or holds a liquid (the heightmap the
     * client has too: leaves count, a canopy keeps the wind off) is above the box's top.
     */
    @Override
    public boolean roofed(int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, baseX + x, baseZ + z) > baseY + SmokeGrid.SY;
    }

    public static boolean closed(Level level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos)) return pos.getY() < level.getMinY();
        if (!level.hasChunkAt(pos)) return true;
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return true;
        if (state.is(BLOCKS_SMOKE) || state.isCollisionShapeFullBlock(level, pos)) return true;
        BooleanProperty open = doorOpen(state);
        return open != null && !state.getValue(open);
    }

    // the "open" each block's doors keep (empty: no door); the tags are read apart, they change with a data pack
    private static final Map<Block, Optional<BooleanProperty>> DOORS = new ConcurrentHashMap<>();

    /** The {@code open} of a door, a trapdoor or one of NTM's doors (see the class), or null for any other block. */
    public static BooleanProperty doorOpen(BlockState state) {
        if ((state.is(BlockTags.DOORS) || state.is(BlockTags.TRAPDOORS)) && state.hasProperty(BlockStateProperties.OPEN)) return BlockStateProperties.OPEN;
        // a plain get first: computeIfAbsent may lock its bin even when the block is there
        Optional<BooleanProperty> known = DOORS.get(state.getBlock());
        if (known == null) known = DOORS.computeIfAbsent(state.getBlock(), SmokeMask::findOpen);
        return known.orElse(null);
    }

    private static Optional<BooleanProperty> findOpen(Block block) {
        Property<?> p = block.getStateDefinition().getProperty("open");
        if (!(p instanceof BooleanProperty open)) return Optional.empty();
        boolean door = block instanceof DoorBlock || block instanceof TrapDoorBlock || "hbm".equals(BuiltInRegistries.BLOCK.getKey(block).getNamespace());
        return door ? Optional.of(open) : Optional.empty();
    }
}
