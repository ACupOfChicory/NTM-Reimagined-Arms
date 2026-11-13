package acup.gunfeel.ai;

import java.util.List;

import acup.gunfeel.BulletRules;
import acup.gunfeel.ReaimTimes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a block says of the place it bounds (stage 4.5b): ground as the world made it, a block someone put there, or a
 * sign that someone lives there (a chest, a bed, a machine: any block that keeps something, but for those of the
 * wild). AiRooms reads a room's shell by these: a cave is not a building, a bunker is, a cellar dug out of stone with
 * a chest in it is. Which blocks are which is the server's to say (the settings below: block ids, #tags, ~patterns,
 * as in the bullet rules), none is named in the code. Server thread.
 */
public final class AiBlockKinds {
    private AiBlockKinds() {
    }

    public static final byte NEUTRAL = 0, NATURAL = 1, BUILT = 2, MARKER = 3;

    /** the server setting: a place under a roof is a building by its shell (false: any roof makes one, as before) */
    public static final String KEY = "aiRoomsShell";
    /** the server settings: the ground as the world makes it; signs of a dwelling besides the blocks that keep something; blocks that keep something and are no such sign */
    public static final String NATURAL_KEY = "aiRoomsNatural", DWELLING_KEY = "aiRoomsDwelling", WILD_KEY = "aiRoomsWild";
    static final String NATURAL_DEFAULT = "#minecraft:base_stone_overworld #minecraft:base_stone_nether #minecraft:dirt #minecraft:sand #minecraft:leaves"
            + " #minecraft:logs #minecraft:snow #minecraft:ice #minecraft:terracotta #minecraft:nylium #minecraft:coral_blocks #c:ores #c:gravels #c:sandstone/blocks"
            + " #c:end_stones #c:netherracks #c:obsidians minecraft:clay minecraft:calcite minecraft:dripstone_block minecraft:pointed_dripstone minecraft:moss_block"
            + " minecraft:magma_block minecraft:soul_sand minecraft:soul_soil minecraft:basalt minecraft:blackstone minecraft:smooth_basalt minecraft:amethyst_block"
            + " minecraft:budding_amethyst ~minecraft:(.*_)?sculk(_.*)? ~hbm:(stone_resource|stone_biome|ore|cluster|glyphid_base|basalt|sellafield|waste_).*";
    static final String DWELLING_DEFAULT = "#minecraft:beds #c:workbenches";
    static final String WILD_DEFAULT = "minecraft:spawner minecraft:trial_spawner minecraft:vault #minecraft:beehives ~minecraft:(.*_)?sculk(_.*)? minecraft:suspicious_sand"
            + " minecraft:suspicious_gravel ~hbm:glyphid.*";

    /** off only for the stands' "before" */
    static boolean on = true;
    /** the setting, for AiRooms on any thread */
    static volatile boolean shell = true;
    private static byte[] values = new byte[0];
    private static boolean[] known = new boolean[0];
    private static String naturalOf = "", dwellingOf = "", wildOf = "";
    private static List<String> natural = List.of(), dwelling = List.of(), wild = List.of();

    /** every server tick (AiBots.tick) */
    static void tick() {
        shell = on && ReaimTimes.flag(KEY, true);
        refresh();
    }

    /** the settings looked at again (TacticalMap, before a section is read) */
    static void refresh() {
        String n = ReaimTimes.text(NATURAL_KEY, NATURAL_DEFAULT), d = ReaimTimes.text(DWELLING_KEY, DWELLING_DEFAULT), w = ReaimTimes.text(WILD_KEY, WILD_DEFAULT);
        if (n.equals(naturalOf) && d.equals(dwellingOf) && w.equals(wildOf)) return;
        naturalOf = n;
        dwellingOf = d;
        wildOf = w;
        natural = List.of(n.trim().split("\\s+"));
        dwelling = List.of(d.trim().split("\\s+"));
        wild = List.of(w.trim().split("\\s+"));
        values = new byte[0];
        known = new boolean[0];
    }

    public static byte of(BlockState state) {
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
        if (state.isAir() || !state.getFluidState().isEmpty()) return NEUTRAL;
        if (BulletRules.blockMatches(dwelling, state)) return MARKER;
        // a block that keeps something: someone lives there, unless it is the wild's (a spawner: nothing either way)
        if (state.hasBlockEntity()) return BulletRules.blockMatches(wild, state) ? NEUTRAL : MARKER;
        if (BulletRules.blockMatches(natural, state)) return NATURAL;
        // a block a mob bumps into that the world did not put there; a door (a way, not a wall), a torch, grass: nothing
        byte v = BlockClasses.of(state);
        return Voxel.open(v) || (v & Voxel.DOOR) != 0 ? NEUTRAL : BUILT;
    }
}
