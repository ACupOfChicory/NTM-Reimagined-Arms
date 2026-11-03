package acup.gunfeel;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraftforge.oredict.OreDictionary;

// port: written anew for 1.7.10 after 26.2's BlockFactsOf (0762086): the same BlockFacts, so Materials (shared with
// 26.2 as it is) decides a block's material from the same things. 1.7.10 has no block states and no tags:
//  - id: the block's 26.2 name (BlockIds), so the rules and materials written for 26.2 meet it;
//  - sound: the name of its SoundType constant as 26.2 names its own (soundTypeMetal "metal", soundTypeCloth
//    "wool"; 1.7.10 gives metal and glass the name "stone"), NTM's ModSoundType by the sounds it plays;
//  - tags: the 26.2 tags Materials and the rules look at, from the Ore Dictionary (plankWood, logWood, blockGlass,
//    paneGlass, block<Metal>...), the block's class with its material (a wooden BlockFence is #minecraft:wooden_fences)
//    and its harvest tool (shovel: #minecraft:mineable/shovel);
//  - hardness, blast (getExplosionResistance: 1.7.10's resistance / 5, the scale of 26.2's), tool (the material's),
//    collision (the boxes of the block where it was met: 1.7.10 has no shape without a world), block entity,
//    drawn as a model (render type 0 and up; -1 is a renderer of its own or nothing).
// Called once per block and meta (BulletRules caches it), with the world and the place the block was first met.
public final class BlockFactsOf {
    private BlockFactsOf() {
    }

    public static BlockFacts of(Block block, int meta, World world, int x, int y, int z) {
        String raw = Mc.name(block);
        List<String> classes = new ArrayList<String>();
        for (Class<?> c = block.getClass(); c != null && c != Block.class && c != Object.class; c = c.getSuperclass()) classes.add(c.getSimpleName());
        boolean full;
        double volume;
        try {
            List<AxisAlignedBB> boxes = new ArrayList<AxisAlignedBB>();
            if (world != null) {
                AxisAlignedBB around = new AxisAlignedBB(x - 1, y - 1, z - 1, x + 2, y + 2, z + 2);
                Mc.collision(world, x, y, z, around, boxes, null);
            }
            double v = 0;
            boolean cube = boxes.size() == 1;
            for (AxisAlignedBB b : boxes) {
                v += (b.maxX - b.minX) * (b.maxY - b.minY) * (b.maxZ - b.minZ);
                cube &= b.minX == x && b.minY == y && b.minZ == z && b.maxX == x + 1 && b.maxY == y + 1 && b.maxZ == z + 1;
            }
            full = cube;
            volume = world == null ? 1 : v;
        } catch (RuntimeException e) {
            // a block that needs more of the world than it has here: as a full block
            full = true;
            volume = 1;
        }
        float hardness;
        try {
            hardness = world == null ? Mc.hardness(block, null, 0, 0, 0) : Mc.hardness(block, world, x, y, z);
        } catch (RuntimeException e) {
            hardness = 1F;
        }
        float blast;
        try {
            blast = block.getExplosionResistance(null);
        } catch (RuntimeException e) {
            blast = 0F;
        }
        boolean tile;
        try {
            tile = block.hasTileEntity(Mc.state(block, meta));
        } catch (RuntimeException e) {
            tile = false;
        }
        Material m = Mc.material(block);
        return new BlockFacts(BlockIds.id(raw, meta), classes, sound(block.getSoundType()), hardness, blast, !m.isToolNotRequired(), full, volume,
                tile, Mc.renderType(block) >= 0, tags(block, meta));
    }

    private static final Map<SoundType, String> SOUNDS = new IdentityHashMap<SoundType, String>();

    static {
        // port: 1.12.2's SoundType constants (GROUND is gravel, PLANT grass)
        SOUNDS.put(SoundType.STONE, "stone");
        SOUNDS.put(SoundType.WOOD, "wood");
        SOUNDS.put(SoundType.GROUND, "gravel");
        SOUNDS.put(SoundType.PLANT, "grass");
        SOUNDS.put(SoundType.METAL, "metal");
        SOUNDS.put(SoundType.GLASS, "glass");
        SOUNDS.put(SoundType.CLOTH, "wool");
        SOUNDS.put(SoundType.SAND, "sand");
        SOUNDS.put(SoundType.SNOW, "snow");
        SOUNDS.put(SoundType.LADDER, "ladder");
        SOUNDS.put(SoundType.ANVIL, "anvil");
        SOUNDS.put(SoundType.SLIME, "slime");
    }

    // the 26.2 name of a sound type; a mod's own by the sounds it plays ("hbm:step.metalBlock": metal), else "?"
    static String sound(SoundType s) {
        if (s == null) return "?";
        String known = SOUNDS.get(s);
        if (known != null) return known;
        String all;
        try {
            all = (s.getBreakSound().getRegistryName() + " " + s.getStepSound().getRegistryName() + " " + s.getPlaceSound().getRegistryName()).toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "?";
        }
        if (all.contains("metal") || all.contains("pipe") || all.contains("anvil")) return "metal";
        if (all.contains("glass")) return "glass";
        if (all.contains("wood") || all.contains("ladder")) return "wood";
        if (all.contains("gravel")) return "gravel";
        if (all.contains("sand")) return "sand";
        if (all.contains("snow")) return "snow";
        if (all.contains("cloth") || all.contains("wool")) return "wool";
        if (all.contains("grass")) return "grass";
        if (all.contains("stone")) return "stone";
        return "?";
    }

    // 26.2's tags that Materials and the bullet rules look at
    static TreeSet<String> tags(Block block, int meta) {
        TreeSet<String> tags = new TreeSet<String>();
        Material m = Mc.material(block);
        boolean wood = m == Material.WOOD;
        if (block instanceof net.minecraft.block.BlockLeaves && m == Material.LEAVES) tags.add("minecraft:leaves");
        if (block instanceof net.minecraft.block.BlockLog) tags.add("minecraft:logs");
        if (block == net.minecraft.init.Blocks.PLANKS) tags.add("minecraft:planks");
        if (block instanceof net.minecraft.block.BlockFence && wood) tags.add("minecraft:wooden_fences");
        if (block instanceof net.minecraft.block.BlockFenceGate) tags.add("minecraft:fence_gates");
        if (block instanceof net.minecraft.block.BlockDoor && wood) tags.add("minecraft:wooden_doors");
        if (block instanceof net.minecraft.block.BlockTrapDoor && wood) tags.add("minecraft:wooden_trapdoors");
        if (block instanceof net.minecraft.block.BlockButtonWood) tags.add("minecraft:wooden_buttons");
        if (block instanceof net.minecraft.block.BlockPressurePlate && wood) tags.add("minecraft:wooden_pressure_plates");
        if (block instanceof net.minecraft.block.BlockSign) tags.add("minecraft:all_signs");
        if (block instanceof net.minecraft.block.BlockWoodSlab) tags.add("minecraft:wooden_slabs");
        if (block instanceof net.minecraft.block.BlockStairs && wood) tags.add("minecraft:wooden_stairs");
        if (block instanceof net.minecraft.block.BlockCarpet) tags.add("minecraft:wool_carpets");
        else if (m == Material.CLOTH && block instanceof net.minecraft.block.BlockColored) tags.add("minecraft:wool");
        if (m == Material.GLASS) {
            if (block instanceof net.minecraft.block.BlockPane) tags.add("c:glass_panes");
            else if (block instanceof net.minecraft.block.BlockGlass || block instanceof net.minecraft.block.BlockStainedGlass) tags.add("c:glass_blocks");
        }
        try {
            if ("shovel".equals(block.getHarvestTool(Mc.state(block, meta)))) tags.add("minecraft:mineable/shovel");
        } catch (RuntimeException e) {
            // no tool
        }
        Item item = Item.getItemFromBlock(block);
        if (item != null) {
            int[] ids;
            try {
                ids = OreDictionary.getOreIDs(new ItemStack(item, 1, meta));
            } catch (RuntimeException e) {
                ids = new int[0];
            }
            for (int id : ids) {
                String t = oreTag(OreDictionary.getOreName(id));
                if (t != null) tags.add(t);
            }
        }
        return tags;
    }

    // an Ore Dictionary name as the 26.2 tag of the same blocks
    static String oreTag(String ore) {
        if (ore == null) return null;
        if (ore.equals("plankWood")) return "minecraft:planks";
        if (ore.equals("logWood")) return "minecraft:logs";
        if (ore.equals("slabWood")) return "minecraft:wooden_slabs";
        if (ore.equals("stairWood")) return "minecraft:wooden_stairs";
        if (ore.equals("treeLeaves")) return "minecraft:leaves";
        if (ore.startsWith("blockGlass")) return "c:glass_blocks";
        if (ore.startsWith("paneGlass")) return "c:glass_panes";
        if (ore.startsWith("block") && ore.length() > 5 && Character.isUpperCase(ore.charAt(5)))
            return "c:storage_blocks/" + snake(ore.substring(5));
        return null;
    }

    // "RedCopper" -> "red_copper"
    static String snake(String s) {
        StringBuilder b = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) b.append('_');
                b.append(Character.toLowerCase(c));
            } else b.append(c);
        }
        return b.toString();
    }
}
