package acup.gunfeel.ai.imitation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

/**
 * A ground to fight on for the recordings, 64 by 64 blocks, set down in front of the player who asks (/armsai
 * course), on the ground he stands on, the way he faces. From near to far: a field with low cover (one block, stone
 * bricks; a row of hay that hides and stops nothing) and high (two, stone); low ground, two pits and ridges; four
 * houses' walls, three high, with doors and windows, and alleys between. A strip along the right side is open field
 * all the way. It clears the air above it (seven blocks) and lays grass under it. Where it went is kept in the world's
 * folder, and /armsai course clear takes it away (air above the grass; what stood there before is not put back). It
 * is not set down over anything built: a block with contents (a chest) or a placed block that is no ground, stone,
 * sand, plant or tree in its room refuses it.
 */
public final class FightCourse {
    static final int DEPTH = 64, HALF = 32, HEIGHT = 7;
    /** it starts this far in front of the player */
    static final int AHEAD = 4;
    static final String FILE = "ntm-gunfeel-course.txt";

    private FightCourse() {
    }

    /** where it lies: its level, its first row's middle at y, and the way it runs */
    record Place(String level, int x, int y, int z, Direction forward) {
        BlockPos at(int u, int w, int dy) {
            Direction right = forward.getClockWise();
            return new BlockPos(x + forward.getStepX() * u + right.getStepX() * w, y + dy, z + forward.getStepZ() * u + right.getStepZ() * w);
        }
    }

    public static String build(ServerPlayer p) throws IOException {
        ServerLevel level = p.level();
        if (!p.isCreative()) return "in creative only: it changes the ground";
        Place old = read(level);
        if (old != null) return "a course stands already in " + old.level + " at " + old.x + " " + old.y + " " + old.z + ": /armsai course clear first";
        Direction f = p.getDirection();
        BlockPos feet = p.blockPosition();
        Place at = new Place(name(level), feet.getX() + f.getStepX() * AHEAD, feet.getY(), feet.getZ() + f.getStepZ() * AHEAD, f);
        String built = built(level, at);
        if (built != null) return "not here: " + built + " in the way; pick open ground";
        // written first: whatever it lays, clear can take away
        Files.writeString(file(level), at.level + " " + at.x + " " + at.y + " " + at.z + " " + f.getSerializedName() + "\n");
        lay(level, at);
        return "course set down from " + at.x + " " + at.y + " " + at.z + ", " + DEPTH + " blocks " + f.getSerializedName() + ", " + 2 * HALF + " across";
    }

    public static String clear(ServerPlayer p) throws IOException {
        ServerLevel level = p.level();
        Place at = read(level);
        if (at == null) return "no course in this world";
        if (!p.isCreative()) return "in creative only: it changes the ground";
        if (!at.level.equals(name(level))) return "the course is in " + at.level + ": clear it from there";
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int u = 0; u < DEPTH; u++)
            for (int w = -HALF; w < HALF; w++) {
                set(level, at.at(u, w, -1), grass);
                for (int dy = 0; dy < HEIGHT; dy++) set(level, at.at(u, w, dy), air);
            }
        Files.deleteIfExists(file(level));
        return "course taken away";
    }

    static void lay(ServerLevel level, Place at) {
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int u = 0; u < DEPTH; u++)
            for (int w = -HALF; w < HALF; w++) {
                set(level, at.at(u, w, -2), Blocks.DIRT.defaultBlockState());
                set(level, at.at(u, w, -1), grass);
                for (int dy = 0; dy < HEIGHT; dy++) set(level, at.at(u, w, dy), air);
            }
        BlockState low = Blocks.STONE_BRICKS.defaultBlockState(), high = Blocks.STONE.defaultBlockState(), hay = Blocks.HAY_BLOCK.defaultBlockState(),
                wall = Blocks.BRICKS.defaultBlockState();
        // the field: rows of low cover, three across, and pillars of high
        int[][] lows = {{6, -20}, {6, 4}, {12, -8}, {12, 14}, {18, -24}, {18, -2}, {9, -14}};
        for (int[] r : lows) row(level, at, r[0], r[1], 3, 1, low);
        row(level, at, 15, 4, 3, 1, hay);
        int[][] highs = {{9, 10}, {15, -14}, {20, 18}, {4, -4}};
        for (int[] r : highs) row(level, at, r[0], r[1], 2, 2, high);
        // low ground: two pits a block down, and ridges
        pit(level, at, 25, -20);
        pit(level, at, 32, 2);
        row(level, at, 24, -6, 6, 1, low);
        row(level, at, 39, -18, 6, 1, low);
        row(level, at, 31, 12, 4, 2, high);
        // the houses: walls three high with a door (two high) on two sides and a window (at the eyes of one lying,
        // standing: a block up) on the others
        house(level, at, 44, -26, wall);
        house(level, at, 44, -6, wall);
        house(level, at, 54, -16, wall);
        house(level, at, 54, 6, wall);
        // the strip from w 24 on is left open: the long open field
    }

    /** a row across, n long, h high, from (u, w) */
    private static void row(ServerLevel level, Place at, int u, int w, int n, int h, BlockState s) {
        for (int k = 0; k < n; k++)
            for (int dy = 0; dy < h; dy++) set(level, at.at(u, w + k, dy), s);
    }

    private static void pit(ServerLevel level, Place at, int u0, int w0) {
        for (int u = u0; u < u0 + 6; u++)
            for (int w = w0; w < w0 + 6; w++) {
                set(level, at.at(u, w, -1), Blocks.AIR.defaultBlockState());
                set(level, at.at(u, w, -2), Blocks.GRASS_BLOCK.defaultBlockState());
            }
    }

    /** 7 by 7, its near left corner at (u0, w0) */
    private static void house(ServerLevel level, Place at, int u0, int w0, BlockState s) {
        int n = 7;
        for (int i = 0; i < n; i++)
            for (int dy = 0; dy < 3; dy++) {
                set(level, at.at(u0 + i, w0, dy), s);
                set(level, at.at(u0 + i, w0 + n - 1, dy), s);
                set(level, at.at(u0, w0 + i, dy), s);
                set(level, at.at(u0 + n - 1, w0 + i, dy), s);
            }
        BlockState air = Blocks.AIR.defaultBlockState();
        // doors: the near side and the left
        for (int dy = 0; dy < 2; dy++) {
            set(level, at.at(u0, w0 + 3, dy), air);
            set(level, at.at(u0 + 3, w0, dy), air);
        }
        // windows: the far side and the right
        set(level, at.at(u0 + n - 1, w0 + 2, 1), air);
        set(level, at.at(u0 + n - 1, w0 + 4, 1), air);
        set(level, at.at(u0 + 3, w0 + n - 1, 1), air);
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState s) {
        level.setBlock(pos, s, Block.UPDATE_ALL);
    }

    /** what is built where it would go (a block with contents, a placed block that is no ground or plant), or null */
    static @Nullable String built(ServerLevel level, Place at) {
        for (int u = 0; u < DEPTH; u++)
            for (int w = -HALF; w < HALF; w++)
                for (int dy = -2; dy < HEIGHT; dy++) {
                    BlockPos pos = at.at(u, w, dy);
                    if (level.getBlockEntity(pos) != null) return level.getBlockState(pos).getBlock().getName().getString() + " at " + pos.toShortString();
                    BlockState s = level.getBlockState(pos);
                    if (!s.isAir() && !natural(s)) return s.getBlock().getName().getString() + " at " + pos.toShortString();
                }
        return null;
    }

    /** ground, stone, sand, water, plants and trees: what the course may lay over */
    static boolean natural(BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.DIRT) || s.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD) || s.is(net.minecraft.tags.BlockTags.SAND)
                || s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.FLOWERS)
                || s.is(net.minecraft.tags.BlockTags.REPLACEABLE) || s.is(Blocks.GRAVEL) || s.is(Blocks.SNOW) || s.is(Blocks.CLAY) || s.is(Blocks.WATER)
                || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.CACTUS) || s.is(Blocks.BEDROCK);
    }

    private static String name(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    private static Path file(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve(FILE);
    }

    private static @Nullable Place read(ServerLevel level) throws IOException {
        Path f = file(level);
        if (!Files.isRegularFile(f)) return null;
        String[] s = Files.readString(f).trim().split(" ");
        if (s.length != 5) return null;
        Direction d = Direction.byName(s[4]);
        if (d == null) return null;
        return new Place(s[0], Integer.parseInt(s[1]), Integer.parseInt(s[2]), Integer.parseInt(s[3]), d);
    }
}
