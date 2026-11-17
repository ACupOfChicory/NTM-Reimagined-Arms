package acup.gunfeel.nuke;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.hbm.config.VersatileConfig;
import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.explosion.vanillant.interfaces.IBlockMutator;
import com.hbm.explosion.vanillant.standard.BlockAllocatorStandard;
import com.hbm.explosion.vanillant.standard.BlockProcessorNoDamage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A small nuclear blast's wasteland, breaking nothing: what its rays reach dies as under a real nuke (grass to dead
 * earth, leaves to dead leaves, plants and mushrooms gone, uranium ore to schrabidium or scorched), but no wood burns
 * and no block is blown away (owner 2026-11-17). The table is NTM's (ExplosionNukeGeneric.wasteDest,
 * RadiationWorldHandler.decayBlock, the fallout table) without its burnt wood, doors blown out and coal to diamond.
 * <p>
 * Use: {@link #apply(ExplosionVNT)} on the blast before {@code explode()}.
 */
public final class MiniWasteMutator implements IBlockMutator {

    /** the rays' resolution, as the Fat Mine's */
    public static final int RESOLUTION = 64;
    /** sand to trinitite: NTM's 1 in 20 */
    public static final int TRINITITE_ONE_IN = 20;

    /** removed (set to air) */
    public static final String AIR = "minecraft:air";
    /** uranium ore: NTM's transmuteUraniumOre, to the first (1 in VersatileConfig.getSchrabOreChance) or the second */
    static final Map<String, List<String>> URANIUM = Map.of(
            "hbm:ore_uranium", List.of("hbm:ore_schrabidium", "hbm:ore_uranium_scorched"),
            "hbm:ore_uranium_deepslate", List.of("hbm:ore_schrabidium", "hbm:ore_uranium_scorched"),
            "hbm:ore_nether_uranium", List.of("hbm:ore_nether_schrabidium", "hbm:ore_nether_uranium_scorched"),
            "hbm:ore_gneiss_uranium", List.of("hbm:ore_gneiss_schrabidium", "hbm:ore_gneiss_uranium_scorched"));
    /** block to block, always */
    static final Map<String, String> TO = Map.ofEntries(
            Map.entry("minecraft:grass_block", "hbm:waste_earth"),
            Map.entry("minecraft:podzol", "hbm:waste_earth"),
            Map.entry("minecraft:mycelium", "hbm:waste_mycelium"),
            Map.entry("minecraft:clay", "minecraft:terracotta"),
            Map.entry("minecraft:mossy_cobblestone", "minecraft:coal_ore"),
            Map.entry("hbm:leaves_layer", AIR),
            Map.entry("minecraft:short_grass", AIR),
            Map.entry("minecraft:tall_grass", AIR),
            Map.entry("minecraft:fern", AIR),
            Map.entry("minecraft:large_fern", AIR),
            Map.entry("minecraft:brown_mushroom", AIR),
            Map.entry("minecraft:red_mushroom", AIR),
            Map.entry("minecraft:brown_mushroom_block", AIR),
            Map.entry("minecraft:red_mushroom_block", AIR),
            Map.entry("minecraft:snow", AIR));
    /** block tag to block (the first that holds) */
    static final List<Map.Entry<String, String>> TAGS = List.of(
            Map.entry("minecraft:leaves", "hbm:waste_leaves"),
            Map.entry("minecraft:flowers", AIR),
            Map.entry("minecraft:saplings", AIR),
            Map.entry("minecraft:bee_growables", AIR));
    /** sand to trinitite, 1 in TRINITITE_ONE_IN */
    static final Map<String, String> TRINITITE = Map.of(
            "minecraft:sand", "hbm:waste_trinitite",
            "minecraft:red_sand", "hbm:waste_trinitite_red");

    /**
     * What the block {@code id} (in the tags {@code tagged} holds) turns into: a block id ({@link #AIR}: removed), or
     * null for left as it is. {@code roll}: 0 for the sure one of a chance (schrabidium, trinitite), else not it.
     */
    public static String waste(String id, Predicate<String> tagged, int roll) {
        if (id.equals("hbm:waste_leaves") || id.equals("hbm:waste_earth") || id.equals("hbm:waste_mycelium")) return null;
        List<String> u = URANIUM.get(id);
        if (u != null) return roll == 0 ? u.get(0) : u.get(1);
        String s = TRINITITE.get(id);
        if (s != null) return roll == 0 ? s : null;
        String to = TO.get(id);
        if (to != null) return to;
        for (Map.Entry<String, String> t : TAGS)
            if (tagged.test(t.getKey())) return t.getValue();
        return null;
    }

    /** sets the blast's block allocator and a processor that breaks nothing but lays this waste */
    public static ExplosionVNT apply(ExplosionVNT vnt) {
        vnt.setBlockAllocator(new BlockAllocatorStandard(RESOLUTION));
        vnt.setBlockProcessor(new BlockProcessorNoDamage().withBlockEffect(new MiniWasteMutator()));
        return vnt;
    }

    @Override
    public void mutatePre(ExplosionVNT vnt, BlockState state, BlockPos pos) {
        Level level = vnt.world;
        if (level == null || level.isClientSide()) return;
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        int roll;
        if (URANIUM.containsKey(id)) roll = level.getRandom().nextInt(Math.max(1, VersatileConfig.getSchrabOreChance()));
        else if (TRINITITE.containsKey(id)) roll = level.getRandom().nextInt(TRINITITE_ONE_IN);
        else roll = 1;
        String to = waste(id, tag -> state.is(TagKey.create(Registries.BLOCK, Identifier.parse(tag))), roll);
        if (to == null) return;
        Block b = to.equals(AIR) ? Blocks.AIR : BuiltInRegistries.BLOCK.getValue(Identifier.parse(to));
        if (b == Blocks.AIR && !to.equals(AIR)) return; // a block this NTM has not
        level.setBlock(pos, b.defaultBlockState(), 3);
    }

    @Override
    public void mutatePost(ExplosionVNT vnt, BlockPos pos) {
    }
}
