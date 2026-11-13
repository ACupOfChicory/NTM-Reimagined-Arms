package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

/**
 * The AI check's doors (AiDoors, stage 4.5a): an AI mob with no mind of its own is sent into a room of stone, roofed,
 * whose one way in is shut: a wooden door, a fence gate, an iron door, NTM's metal door (a door of the game's own kind that a hand opens). Each with aiDoors off (as before: the shut door
 * is a wall) and on. Counted: whether it got in and when, the doors it opened.
 */
final class AiCheckDoors {
    static final int X0 = 780, Z0 = 4, SIZE = 32, RUN = 300;
    /** the room's middle; its walls 3 off, the way in in the south wall; the mob starts 9 south */
    static final int RX = 796, RZ = 16, WALL = 3, FROM = 9;
    static final String[] NAMES = { "a wooden door", "a fence gate", "an iron door", "NTM's metal door" };

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private boolean started;
    private int round = -1, ticks, reachedAt, openedAt;
    private long caveAt = -1;
    private Mob mob;
    /** per round: {way in, aiDoors, reached at (-1: not), opened} */
    private final List<int[]> rounds = new ArrayList<>();

    AiCheckDoors(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    boolean tick() {
        if (caveAt >= 0) {
            if (level.getGameTime() - caveAt < 40) return false;
            TacticalMap map = TacticalMap.of(level);
            boolean cave = AiRooms.of(map, BlockPos.asLong(RX, y, RZ)) == null;
            AiBlockKinds.shell = false;
            boolean before = AiRooms.of(map, BlockPos.asLong(RX, y, RZ)) != null;
            AiBlockKinds.shell = true;
            check.out("     doors, the room of the rock's stone: " + (cave ? "no building" : "a building") + "; with aiRoomsShell off: " + (before ? "a building" : "no building")
                    + "; the wall's kind " + map.kind(RX - WALL, y, RZ) + ", the roof's " + map.kind(RX, y + 3, RZ) + ", the floor's " + map.kind(RX, y - 1, RZ));
            check.check("the same room walled and roofed with the rock's stone is a cave, no building; with aiRoomsShell off it is one, as before", cave && before);
            return true;
        }
        if (!started) {
            started = true;
            for (int cx = X0 >> 4; cx <= (X0 + SIZE) >> 4; cx++)
                for (int cz = Z0 >> 4; cz <= (Z0 + SIZE) >> 4; cz++) level.setChunkForced(cx, cz, true);
            AiBots.awake = true;
            start(0);
            return false;
        }
        AiBot bot = AiBots.get(mob);
        if (bot == null) {
            check.check("doors: the walker is an AI mob", false);
            return finish();
        }
        ticks++;
        bot.gunner.fire(false);
        if (reachedAt < 0 && bot.mover.arrived() && Math.abs(mob.getX() - (RX + 0.5)) < 2 && Math.abs(mob.getZ() - (RZ + 0.5)) < 2) reachedAt = ticks;
        if (ticks < RUN) return false;
        rounds.add(new int[] { round / 2, AiDoors.on ? 1 : 0, reachedAt, AiDoors.opened - openedAt });
        mob.discard();
        mob = null;
        if (round + 1 >= NAMES.length * 2) return finish();
        start(round + 1);
        return false;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS);
    }

    private void start(int r) {
        round = r;
        ticks = 0;
        reachedAt = -1;
        openedAt = AiDoors.opened;
        AiDoors.on = r % 2 == 1;
        int way = r / 2;
        // walls and roof of bricks (blocks put there: a building by its shell, AiBlockKinds), the ground stone
        BlockState stone = Blocks.STONE.defaultBlockState(), brick = Blocks.STONE_BRICKS.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                set(x, y - 1, z, stone);
                int dx = Math.abs(x - RX), dz = Math.abs(z - RZ);
                boolean wall = Math.max(dx, dz) == WALL, in = Math.max(dx, dz) < WALL;
                for (int h = 0; h < 3; h++) set(x, y + h, z, wall ? brick : air);
                // a roof over the room and its walls
                set(x, y + 3, z, wall || in ? brick : air);
            }
        // the way in: the south wall's middle
        int dz = RZ + WALL;
        if (way == 1) {
            set(RX, y, dz, Blocks.OAK_FENCE_GATE.defaultBlockState());
            set(RX, y + 1, dz, air);
        } else {
            Block ntm = BuiltInRegistries.BLOCK.getValue(Identifier.fromNamespaceAndPath("hbm", "door_metal"));
            BlockState door = (way == 0 ? Blocks.OAK_DOOR : way == 2 ? Blocks.IRON_DOOR : ntm).defaultBlockState();
            if (way == 3 && !(ntm instanceof DoorBlock)) check.check("doors: hbm:door_metal is a door", false);
            set(RX, y, dz, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
            set(RX, y + 1, dz, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }
        TacticalMap.of(level).want(RX, y, RZ + 4, 14, 6);
        List<Mob> made = AiSpawn.spawn(level, new Vec3(RX + 0.5, y, RZ + FROM + 0.5), AiSpawn.Kind.DUMMY, 1, null);
        mob = made.get(0);
        mob.setPos(RX + 0.5, y, RZ + FROM + 0.5);
        AiBot bot = AiBots.get(mob);
        if (bot != null) {
            bot.brain = null;
            bot.mover.goTo(BlockPos.asLong(RX, y, RZ), 0.8, AiSoldier.WALK);
        }
    }

    /** the room read off the map (AiRooms, stage 4.5b): one room, one way out with a door, two cells beside it outside */
    private void rooms() {
        TacticalMap map = TacticalMap.of(level);
        long t0 = System.nanoTime();
        AiRooms.Plan p = AiRooms.of(map, BlockPos.asLong(RX, y, RZ));
        long us = (System.nanoTime() - t0) / 1000;
        if (p == null) {
            check.check("doors: the room is read off the map", false);
            return;
        }
        AiRooms.Way out = p.ways.length == 1 ? p.ways[0] : null;
        int beside = out == null ? 0 : out.beside(AiRooms.OUTSIDE).length;
        check.out(String.format(Locale.ROOT, "     doors, the room read off the map: rooms %d, cells %d, ways %d, a door %s, cells beside it outside %d, %d us",
                p.rooms(), p.rooms() > 0 ? p.size[0] : 0, p.ways.length, out != null && out.door, beside, us));
        check.check("the room read off the map: one room of 25 cells, one way out, with a door, two cells beside it outside",
                p.rooms() == 1 && p.size[0] == 25 && out != null && out.door && out.b == AiRooms.OUTSIDE && out.dir == Dirs.S && beside == 2);
        check.check("no building is read where there is no roof", AiRooms.of(map, BlockPos.asLong(RX, y, RZ + FROM)) == null);
        // what the blocks say of a place (AiBlockKinds, by the settings' lists and tags)
        String[] ids = { "minecraft:stone", "minecraft:deepslate", "minecraft:dirt", "minecraft:oak_leaves", "minecraft:iron_ore", "minecraft:stone_bricks", "minecraft:cobblestone",
                "minecraft:oak_planks", "minecraft:glass", "hbm:reinforced_stone", "hbm:brick_concrete", "minecraft:chest", "minecraft:furnace", "minecraft:red_bed",
                "minecraft:spawner", "minecraft:oak_door", "minecraft:torch" };
        byte[] want = { 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 3, 3, 3, 0, 0, 0 };
        String[] kinds = { "none", "natural", "built", "dwelling" };
        StringBuilder sb = new StringBuilder();
        boolean ok = true;
        AiBlockKinds.refresh();
        for (int i = 0; i < ids.length; i++) {
            Block b = BuiltInRegistries.BLOCK.getValue(Identifier.parse(ids[i]));
            byte k = b == Blocks.AIR ? -1 : AiBlockKinds.of(b.defaultBlockState());
            sb.append(' ').append(ids[i]).append('=').append(k < 0 ? "missing" : kinds[k]);
            ok &= k == want[i];
        }
        check.out("     doors, what the blocks say of a place:" + sb);
        check.check("stone, dirt, leaves and ore are the ground; bricks, planks, glass and NTM's concrete are put there; a chest, a furnace and a bed say someone lives there; a spawner, a door and a torch say nothing", ok);
        // the same room with its walls and roof of the rock's stone: a cave, no building; with the setting off, one as before
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = RX - WALL; x <= RX + WALL; x++)
            for (int z = RZ - WALL; z <= RZ + WALL; z++) {
                boolean wall = Math.max(Math.abs(x - RX), Math.abs(z - RZ)) == WALL;
                for (int h = 0; h < 4; h++) if (h == 3 || wall && level.getBlockState(new BlockPos(x, y + h, z)).is(Blocks.STONE_BRICKS)) set(x, y + h, z, stone);
            }
        map.want(RX, y, RZ + 4, 14, 6);
        caveAt = level.getGameTime();
    }

    private boolean finish() {
        AiDoors.on = true;
        if (mob != null) mob.discard();
        boolean before = true, now = true;
        for (int[] r : rounds) {
            check.out(String.format(Locale.ROOT, "     doors, %s, aiDoors %s: %s, opened %d", NAMES[r[0]], r[1] == 1 ? "on" : "off (before)",
                    r[2] < 0 ? "never in" : "in at tick " + r[2], r[3]));
            // an iron door is opened by no hand: never in, either way
            if (r[1] == 0 || r[0] == 2) before &= r[2] < 0 && r[3] == 0;
            else now &= r[2] >= 0 && r[3] == 1;
        }
        if (rounds.size() < NAMES.length * 2) {
            check.check("doors: all rounds ran", false);
            return true;
        }
        check.check("a shut wooden door, fence gate or NTM metal door on its way: it opens it and goes in", now);
        rooms();
        check.check("with aiDoors off, and at an iron door: it stays out and opens nothing", before);
        // the cave's check comes a few ticks later (tick), once the map has read the blocks changed
        return caveAt < 0;
    }
}
