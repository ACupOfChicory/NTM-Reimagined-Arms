package acup.gunfeel.ai;

import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The AI check's terrain layers on real blocks (AiTerrain): a stone plain in the air with a ridge across it, a pond dug
 * in it and a grove on it. The layers are read off the game's heightmaps as the server ticks; checked: the ground, the
 * water, the crowns, the ridge a height that ends a look, the open side seen far over. Then the land changes: a block
 * under the ground (nothing copied), an explosion on the plain and a gap broken in the ridge (the chunks copied once
 * the blocks have stopped changing, the look goes through the gap). With the ticks and the time it took.
 */
final class AiCheckTerrain {
    static final int X0 = 2000, Z0 = 0, SIZE = 64, LIMIT = 1200;
    /** the ridge, four high, right across; the gap broken in it */
    static final int RIDGE_X0 = 2040, RIDGE_X1 = 2043, RIDGE_H = 4, GAP_Z0 = 24, GAP_Z1 = 36;
    /** the pond, two deep; the grove, its crowns at y + 3 and y + 4 */
    static final int POND_X0 = 2008, POND_X1 = 2013, POND_Z0 = 40, POND_Z1 = 45, GROVE_X0 = 2008, GROVE_X1 = 2019, GROVE_Z0 = 8, GROVE_Z1 = 19;
    /** where the looks are taken from: the plain between the grove and the ridge; where the explosion goes off */
    static final int AT_X = 2026, AT_Z = 30, BOOM_X = 2026, BOOM_Z = 52;

    private final AiCheck check;
    private final ServerLevel level;
    private final int y;
    private int phase, ticks, waited;
    private long copies, derives, marks;
    private AiTerrain terrain;

    AiCheckTerrain(AiCheck check, ServerLevel level, int y) {
        this.check = check;
        this.level = level;
        this.y = y;
    }

    private void set(int x, int yy, int z, BlockState st) {
        level.setBlock(new BlockPos(x, yy, z), st, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    private void force(boolean on) {
        int r = TerrainCore.DERIVED + TerrainCore.AROUND;
        for (int cx = (AT_X >> 4) - r; cx <= (AT_X >> 4) + r; cx++)
            for (int cz = (AT_Z >> 4) - r; cz <= (AT_Z >> 4) + r; cz++) level.setChunkForced(cx, cz, on);
    }

    private boolean loaded() {
        int r = TerrainCore.DERIVED + TerrainCore.AROUND;
        for (int cx = (AT_X >> 4) - r; cx <= (AT_X >> 4) + r; cx++)
            for (int cz = (AT_Z >> 4) - r; cz <= (AT_Z >> 4) + r; cz++) if (level.getChunkSource().getChunkNow(cx, cz) == null) return false;
        return true;
    }

    private void build() {
        BlockState stone = Blocks.STONE.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int x = X0; x < X0 + SIZE; x++)
            for (int z = Z0; z < Z0 + SIZE; z++) {
                for (int d = 1; d <= 3; d++) set(x, y - d, z, stone);
                boolean ridge = x >= RIDGE_X0 && x <= RIDGE_X1;
                for (int h = 0; h < 8; h++) set(x, y + h, z, ridge && h < RIDGE_H ? stone : air);
            }
        for (int x = POND_X0; x <= POND_X1; x++)
            for (int z = POND_Z0; z <= POND_Z1; z++) {
                set(x, y - 1, z, Blocks.WATER.defaultBlockState());
                set(x, y - 2, z, Blocks.WATER.defaultBlockState());
            }
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        for (int x = GROVE_X0; x <= GROVE_X1; x++)
            for (int z = GROVE_Z0; z <= GROVE_Z1; z++) {
                set(x, y + 3, z, leaves);
                set(x, y + 4, z, leaves);
                if ((x - GROVE_X0) % 6 == 2 && (z - GROVE_Z0) % 6 == 2) for (int h = 0; h < 3; h++) set(x, y + h, z, Blocks.OAK_LOG.defaultBlockState());
            }
    }

    private void mark() {
        TerrainCore c = terrain.core();
        copies = c.copies;
        derives = c.derives;
        marks = c.marks;
        waited = 0;
    }

    boolean tick() {
        ticks++;
        if (ticks > LIMIT) {
            check.check("terrain: done within a minute (stopped in phase " + phase + ")", false);
            force(false);
            return true;
        }
        switch (phase) {
            case 0 -> {
                force(true);
                phase = 1;
            }
            case 1 -> {
                if (!loaded()) return false;
                check.out(String.format(Locale.ROOT, "     terrain: %d chunks round the plain loaded in %d ticks", 19 * 19, ticks));
                build();
                terrain = AiTerrain.of(level);
                terrain.want(AT_X, AT_Z);
                mark();
                phase = 2;
            }
            case 2 -> {
                waited++;
                if (!terrain.core().settled() || waited < 3) return false;
                first();
                // a block under the ground: a cave dug
                mark();
                set(AT_X, y - 3, AT_Z, Blocks.AIR.defaultBlockState());
                set(AT_X + 1, y - 3, AT_Z, Blocks.AIR.defaultBlockState());
                phase = 3;
            }
            case 3 -> {
                if (++waited < 30) return false;
                TerrainCore c = terrain.core();
                check.check("terrain: blocks dug under the ground are no change of the land (nothing marked, nothing copied)",
                        c.marks == marks && c.copies == copies && c.derives == derives);
                // an explosion on the plain, a gap broken in the ridge
                mark();
                level.explode(null, BOOM_X + 0.5, y - 0.5, BOOM_Z + 0.5, 8F, Level.ExplosionInteraction.TNT);
                for (int x = RIDGE_X0; x <= RIDGE_X1; x++)
                    for (int z = GAP_Z0; z <= GAP_Z1; z++)
                        for (int h = 0; h < RIDGE_H; h++) set(x, y + h, z, Blocks.AIR.defaultBlockState());
                phase = 4;
            }
            case 4 -> {
                waited++;
                if (!terrain.core().settled() || waited < 3) return false;
                second();
                force(false);
                return true;
            }
            default -> {
            }
        }
        return false;
    }

    private void first() {
        TerrainCore c = terrain.core();
        check.out(String.format(Locale.ROOT, "     terrain: read in %d ticks; %s", waited, c.info()));
        int tiles = 0, derived = 0, r = TerrainCore.DERIVED + TerrainCore.AROUND;
        for (int cx = (AT_X >> 4) - r; cx <= (AT_X >> 4) + r; cx++)
            for (int cz = (AT_Z >> 4) - r; cz <= (AT_Z >> 4) + r; cz++) {
                if (c.tile(TerrainTile.key(cx, cz)) != null) tiles++;
                if (c.derived(TerrainTile.key(cx, cz)) != null) derived++;
            }
        check.check("terrain: every chunk within a look's reach copied (" + tiles + " of " + 19 * 19 + "), the middle 7 x 7 worked out (" + derived + ")",
                tiles == 19 * 19 && derived == 7 * 7);
        check.check("terrain: the plain's ground is its floor's top, no water, open sky", terrain.ground(AT_X, AT_Z) == y && terrain.water(AT_X, AT_Z) == 0
                && terrain.canopy(AT_X, AT_Z) == 0);
        check.check("terrain: the ridge stands " + RIDGE_H + " over it", terrain.ground(RIDGE_X0 + 1, AT_Z) == y + RIDGE_H);
        check.check("terrain: the pond is 2 of water over its bed", terrain.water(POND_X0 + 2, POND_Z0 + 2) == 2 && terrain.ground(POND_X0 + 2, POND_Z0 + 2) == y - 2);
        check.check("terrain: the grove's crowns reach 5 over the ground, all of it under them", terrain.canopy(GROVE_X0 + 3, GROVE_Z0 + 3) == 5
                && terrain.crowned(GROVE_X0 + 5, GROVE_Z0 + 5) == 1F && terrain.ground(GROVE_X0 + 3, GROVE_Z0 + 3) == y);
        check.check("terrain: a trunk is ground, not crown", terrain.ground(GROVE_X0 + 2, GROVE_Z0 + 2) == y + 3);
        int towards = terrain.openTowards(AT_X, AT_Z, AT_X + 50, AT_Z), away = terrain.openTowards(AT_X, AT_Z, AT_X - 50, AT_Z);
        int height = terrain.height(AT_X, AT_Z), ridge = terrain.height(RIDGE_X0 + 1, AT_Z);
        check.out(String.format(Locale.ROOT, "     terrain: from the plain one sees %d blocks towards the ridge, %d away from it, %d all round; the plain stands %+d over the land, the ridge %+d; in the grove one sees %d",
                towards, away, terrain.open(AT_X, AT_Z), height, ridge, terrain.open(GROVE_X0 + 5, GROVE_Z0 + 5)));
        check.check("terrain: a look from the plain ends at the ridge and goes far the other way", towards >= 8 && towards <= 20 && away >= 64);
        check.check("terrain: the plain in the air is a height over the land below, the ridge higher", height >= TerrainDerived.HIGH && ridge > height);
    }

    private void second() {
        TerrainCore c = terrain.core();
        int crater = terrain.ground(BOOM_X, BOOM_Z);
        check.out(String.format(Locale.ROOT, "     terrain: an explosion and a gap in the ridge: %d changes marked, %d chunks copied again, %d worked out again, fresh %d ticks after; the crater's ground %d under the plain; %s",
                c.marks - marks, c.copies - copies, c.derives - derives, waited, y - crater, c.info()));
        check.check("terrain: the changes of a blast are marked and its chunks copied once each when it is over", c.marks > marks && c.copies - copies >= 1 && c.copies - copies <= 6);
        check.check("terrain: the layers are fresh within 3 seconds of the blast", waited <= 60);
        check.check("terrain: the crater is under the plain, the gap in the ridge is level with it", crater < y && terrain.ground(RIDGE_X0 + 1, AT_Z) == y);
        int towards = terrain.openTowards(AT_X, AT_Z, AT_X + 50, AT_Z);
        check.check("terrain: the look goes through the gap now (" + towards + " blocks)", towards >= 36);
    }
}
