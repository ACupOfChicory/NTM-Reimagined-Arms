package acup.gunfeel.ai.bench;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

// The ground the mob AI's stand (AiBench) runs on: x and z from 0 to 127 (chunks 0..7) on the flat world's floor.
// Rows of cover across the field between the two sides (x = 28 ... 100, along z, staggered pieces 10 long, 2 and 1
// blocks high, gaps of 6 between them) and two plank houses in the middle with doorways and windows. Everything is
// vanilla blocks; build() puts back whatever differs from that, the floor under an explosion included.
// The arena's chunks and one ring around them carry forced tickets: they stay loaded and entity-ticking with no player.
public final class Arena {
    public static final int SIZE = 128;
    static final int FORCED_FROM = -1, FORCED_TO = SIZE / 16; // chunks -1..8 on both axes
    static final int[] ROWS = {28, 44, 56, 72, 84, 100};
    static final int[][] HOUSES = {{64, 32}, {64, 96}}; // centres; 9 by 9, walls 3 high, a roof on top
    // the boom phase's explosions, in turn: vanilla TNT-like blasts of this power at the ground, in the middle the two
    // sides meet in; the floor is 16 blocks of dirt so a crater takes thousands of blocks
    static final int[][] BOOMS = {{64, 18}, {64, 64}, {64, 110}};
    static final float BOOM_POWER = 16F;
    static final int BOOM_REACH = 20; // the cube the blocks are counted in, around a spot
    // the crater phase: NTM's bombs (ExplosionVNT of this size with NTM's standard block breaking), the first where
    // the west side comes in, the second between the sides; and a trench dug across the west side's way, two deep,
    // a block wide, a column a tick from TRENCH_Z on, TRENCH_LENGTH columns
    static final int[][] CRATERS = {{36, 40}, {64, 64}};
    static final float CRATER_SIZE = 12F;
    static final int TRENCH_X = 47, TRENCH_Z = 34, TRENCH_LENGTH = 60, TRENCH_DEPTH = 2;
    // the stand's mobs: AiBench cancels every damage to them
    public static final String TAG = "ntmgunfeel_ai_bench";

    private static final BlockState AIR = Blocks.AIR.defaultBlockState(), PLANKS = Blocks.OAK_PLANKS.defaultBlockState(),
            TALL = Blocks.STONE_BRICKS.defaultBlockState(), LOW = Blocks.COBBLESTONE.defaultBlockState();

    public final ServerLevel level;
    public final int bottom, ground; // the world's lowest y; the y mobs stand on
    final BlockState[] floor; // the world's own column under the ground, from the bottom up
    Mob probe;

    Arena(ServerLevel level) {
        this.level = level;
        for (int cx = FORCED_FROM; cx <= FORCED_TO; cx++)
            for (int cz = FORCED_FROM; cz <= FORCED_TO; cz++) level.setChunkForced(cx, cz, true);
        bottom = level.getMinY();
        List<BlockState> column = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(-8, bottom, -8); // in the ring, never built on
        for (int y = bottom; y < bottom + 64; y++) {
            BlockState s = level.getBlockState(p.setY(y));
            if (s.isAir()) break;
            column.add(s);
        }
        floor = column.toArray(BlockState[]::new);
        ground = bottom + floor.length;
    }

    // ---- what the phases use

    // a mob of this type at the first free spot from x along +x, on the ground, kept (no despawn) and with the stand's
    // tag: AiBench turns away all damage to it, so the mobs of a phase stay as many as they were for the whole of it.
    // Not setInvulnerable: in 26.2 Mob.setTarget and getTarget drop a target that is invulnerable (canBeSeenAsEnemy).
    public Mob spawn(String id, int x, int z) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(id))
                .orElseThrow(() -> new IllegalArgumentException("no entity " + id));
        x = free(x, z);
        Entity e = type.spawn(level, new BlockPos(x, ground, z), EntitySpawnReason.COMMAND);
        if (!(e instanceof Mob m)) {
            if (e != null) e.discard();
            throw new IllegalStateException(id + " is not a mob");
        }
        m.setPersistenceRequired();
        m.addTag(TAG);
        return m;
    }

    // n mobs spread over the arena in a square grid
    public void grid(String id, int n) {
        int side = (int) Math.ceil(Math.sqrt(n));
        for (int i = 0; i < n; i++)
            spawn(id, 8 + (SIZE - 16) * (i % side) / side + (SIZE - 16) / side / 2,
                    8 + (SIZE - 16) * (i / side) / side + (SIZE - 16) / side / 2);
    }

    // n FBI agents in two sides at the west and east edges, each the target of the one across from it: they cross the
    // cover rows to get at each other. NTM's FBI only ever targets players (and what hurts it); with no player on the
    // stand its target is set here, and its own AI does the rest: customServerAiStep sends it 16 blocks towards its
    // target every tick, MeleeAttackGoal punches, and NTM's EntityAIFireGun (MobUtil.onEntityLoad gives it to every mob
    // holding an NTM gun: the FBI's heavy revolver or SPAS-12) shoots. No damage reaches them (TAG): an FBI's punch
    // would hurt another one.
    public void fbiPairs(int n) {
        fbiPairs(n, true);
    }

    // the same with no gun in the hand when guns is false: EntityAIFireGun.canUse wants one, so they only walk and punch
    public void fbiPairs(int n, boolean guns) {
        for (int i = 0; i < n / 2; i++) {
            int c = i % 5, j = i / 5, z = 6 + 12 * (j % 10) + 6 * (j / 10 % 2);
            Mob a = spawn("hbm:entity_fbi", 4 + 4 * c, z), b = spawn("hbm:entity_fbi", SIZE - 5 - 4 * c, z);
            if (!guns) for (Mob m : List.of(a, b)) m.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            a.setTarget(b);
            b.setTarget(a);
        }
    }

    // n of the mod's AI mobs (FBI agents with their own NTM guns) in two teams at the west and east edges, as fbiPairs
    // has them: the mod's AI takes them over as they come in (tagged armsai), finds the other team and fights it
    // across the cover rows. Endless rounds (a measurement must not end with empty guns) and no damage (TAG); every
    // AI mob thinks with no player near (AiBots.wakeAll)
    public void aiPairs(int n) {
        acup.gunfeel.ai.AiBots.wakeAll(true);
        var board = level.getScoreboard();
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("hbm:entity_fbi"));
        List<Mob> made = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            int c = i / 2 % 5, j = i / 2 / 5, z = 6 + 12 * (j % 10) + 6 * (j / 10 % 2);
            boolean west = i % 2 == 0;
            // nearer than fbiPairs' edges: between the rows at x 28 and 44 and at 84 and 100, 45 to 65 blocks apart,
            // in sight of each other through the gaps in the rows between (a fight, not a long search)
            int x = free(west ? 31 + 2 * c : SIZE - 32 - 2 * c, z);
            if (!(type.create(level, EntitySpawnReason.COMMAND) instanceof Mob m)) throw new IllegalStateException("no FBI");
            m.snapTo(x + 0.5, ground, z + 0.5, west ? -90F : 90F, 0F);
            m.finalizeSpawn(level, level.getCurrentDifficultyAt(m.blockPosition()), EntitySpawnReason.COMMAND, null);
            m.setPersistenceRequired();
            m.addTag(TAG);
            m.addTag(acup.gunfeel.ai.AiAdapter.TAG);
            String team = west ? "bench_west" : "bench_east";
            var t = board.getPlayerTeam(team);
            board.addPlayerToTeam(m.getScoreboardName(), t != null ? t : board.addPlayerTeam(team));
            level.addFreshEntity(m);
            var bot = acup.gunfeel.ai.AiBots.get(m);
            if (bot == null) throw new IllegalStateException("an FBI tagged armsai is not an AI mob");
            bot.gunner.ammo.setInfinite();
            bot.gunner.loadFree();
            made.add(m);
        }
        // behind the rows they may see nothing of each other at first: each hears the one across from it fire, as
        // fbiPairs gives each FBI the one across as its target, and goes to find them
        long tick = level.getGameTime();
        for (int i = 0; i + 1 < made.size(); i += 2) {
            Mob w = made.get(i), e = made.get(i + 1);
            acup.gunfeel.ai.AiBots.get(w).memory.heard(e.getX(), e.getY(), e.getZ(), tick, true, false);
            acup.gunfeel.ai.AiBots.get(e).memory.heard(w.getX(), w.getY(), w.getZ(), tick, true, false);
        }
    }

    // ---- the stand's own

    AABB box() {
        return new AABB(-16, bottom, -16, SIZE + 16, ground + 64, SIZE + 16);
    }

    // the mobs on the arena but the probe
    List<Mob> mobs() {
        return level.getEntitiesOfClass(Mob.class, box(), m -> m != probe && !m.isRemoved());
    }

    // everything in the level but players and the probe goes: mobs, dropped items, whatever was shot. The whole level,
    // not the arena: the FBI's bullets fly off it into chunks that do not tick and would stay there for good (a run
    // left a thousand of them, and every round was slower than the one before)
    int clear() {
        List<Entity> all = new ArrayList<>();
        for (Entity e : level.getAllEntities()) if (e != probe && !(e instanceof Player)) all.add(e);
        all.forEach(Entity::discard);
        return all.size();
    }

    // the dummy that counts ticks for the whole run, in the far corner
    Mob probe() {
        probe = spawn("hbm:entity_test_dummy", SIZE - 2, SIZE - 2);
        return probe;
    }

    // the arena's forced chunks that the level ticks entities in (ServerLevel's own test: the chunk's entities are
    // ticking and the chunk is in the simulation tracker's entity-ticking range)
    int ticking() {
        int n = 0;
        for (int cx = FORCED_FROM; cx <= FORCED_TO; cx++)
            for (int cz = FORCED_FROM; cz <= FORCED_TO; cz++)
                if (level.isPositionEntityTicking(new BlockPos(cx * 16 + 8, ground, cz * 16 + 8))) n++;
        return n;
    }

    static int forced() {
        return (FORCED_TO - FORCED_FROM + 1) * (FORCED_TO - FORCED_FROM + 1);
    }

    // the highest block over the ground a phase puts (a tree on a mound's top), put back to air by build()
    static final int TOP = 10;
    // the wood phase's mounds (centres, 7 across at the foot, 3 high) and its pond (a corner, 6 by 6, 2 deep)
    static final int[][] MOUNDS = {{50, 64}, {78, 64}};
    static final int MOUND_R = 7, MOUND_H = 3, POND_X = 61, POND_Z = 61, POND = 6;

    static int mound(int x, int z) {
        int h = 0;
        for (int[] m : MOUNDS) {
            double d = Math.hypot(x - m[0], z - m[1]);
            if (d < MOUND_R) h = Math.max(h, 1 + (int) ((MOUND_H - 1) * (1 - d / MOUND_R) + 0.5));
        }
        return h;
    }

    // The same arena as land with something on it, for the terrain layers and the looks through leaves: two mounds
    // between the sides, a pond in the middle, and trees all over, seven blocks apart, staggered: a trunk four high
    // under a crown five across; every third one bushy, leaves down to the ground round its trunk (in the way of a
    // look, where the others' crowns are over the heads). No trunk where the sides come in, in the houses or on the
    // rows. Every block is put back by the next build(). The trees planted.
    public int wood() {
        BlockState dirt = Blocks.DIRT.defaultBlockState(), log = Blocks.OAK_LOG.defaultBlockState(), water = Blocks.WATER.defaultBlockState();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (int x = 0; x < SIZE; x++)
            for (int z = 0; z < SIZE; z++)
                for (int h = 0; h < mound(x, z); h++) level.setBlock(p.set(x, ground + h, z), dirt, flags);
        for (int x = POND_X; x < POND_X + POND; x++)
            for (int z = POND_Z; z < POND_Z + POND; z++)
                for (int d = 1; d <= 2; d++) level.setBlock(p.set(x, ground - d, z), water, flags);
        int trees = 0;
        for (int row = 0, z = 4; z < SIZE - 3; z += 7, row++)
            for (int x = 6 + row % 2 * 3; x < SIZE - 3; x += 7) {
                // where the two sides come in (aiPairs), and a little round it
                if (x >= 29 && x <= 41 || x >= SIZE - 42 && x <= SIZE - 30) continue;
                boolean house = false;
                for (int[] c : HOUSES) house |= Math.abs(x - c[0]) <= 6 && Math.abs(z - c[1]) <= 6;
                if (house || !structure(x, 0, z).isAir() || !structure(x, 1, z).isAir()) continue;
                if (x >= POND_X - 1 && x <= POND_X + POND && z >= POND_Z - 1 && z <= POND_Z + POND) continue;
                int base = ground + mound(x, z);
                boolean bushy = trees % 3 == 2;
                for (int h = 0; h < 6; h++)
                    for (int dx = -2; dx <= 2; dx++)
                        for (int dz = -2; dz <= 2; dz++) {
                            int reach = h < 3 ? (bushy && h > 0 ? 1 : 0) : h < 5 ? 2 : 1;
                            if (Math.abs(dx) > reach || Math.abs(dz) > reach) continue;
                            int ax = x + dx, az = z + dz;
                            if (ax < 0 || az < 0 || ax >= SIZE || az >= SIZE) continue;
                            boolean trunk = dx == 0 && dz == 0 && h < 4;
                            if (!trunk && !level.getBlockState(p.set(ax, base + h, az)).isAir()) continue;
                            level.setBlock(p.set(ax, base + h, az), trunk ? log : leaves, flags);
                        }
                trees++;
            }
        return trees;
    }

    // puts back every block that is not as the arena has it; the blocks changed
    int build() {
        int changed = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = 0; x < SIZE; x++)
            for (int z = 0; z < SIZE; z++)
                for (int y = bottom; y <= ground + TOP; y++) {
                    BlockState want = y < ground ? floor[y - bottom] : structure(x, y - ground, z);
                    if (level.getBlockState(p.set(x, y, z)) == want) continue;
                    level.setBlock(p, want, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    changed++;
                }
        return changed;
    }

    static BlockState structure(int x, int h, int z) {
        for (int[] c : HOUSES) {
            int dx = Math.abs(x - c[0]), dz = Math.abs(z - c[1]);
            if (dx > 4 || dz > 4 || h > 3) continue;
            if (h == 3) return PLANKS;
            if (dx < 4 && dz < 4) return AIR;
            // a doorway two high in the middle of each wall, a window at head height two blocks to one side
            if ((dx == 4 && dz == 0 || dz == 4 && dx == 0) && h < 2) return AIR;
            if ((dx == 4 && z - c[1] == 2 || dz == 4 && x - c[0] == 2) && h == 1) return AIR;
            return PLANKS;
        }
        for (int r = 0; r < ROWS.length; r++) {
            if (x != ROWS[r] || h > 1) continue;
            int from = 2 + r % 2 * 8, k = Math.floorDiv(z - from, 16), in = z - from - 16 * k;
            if (k < 0 || in > 9 || from + 16 * k + 9 > SIZE - 3) continue;
            int height = k % 2 == 0 ? 2 : 1;
            if (h < height) return height == 2 ? TALL : LOW;
        }
        return AIR;
    }

    // the first x from this one along +x (round the arena) where a mob fits
    static int free(int x, int z) {
        for (int i = 0; i < SIZE; i++) {
            int at = Math.floorMod(x + i, SIZE);
            if (structure(at, 0, z).isAir() && structure(at, 1, z).isAir()) return at;
        }
        return x;
    }

    // the boom phase: explosion j goes off
    void boom(int j) {
        int[] s = BOOMS[j % BOOMS.length];
        level.explode(null, s[0] + .5, ground + .5, s[1] + .5, BOOM_POWER, Level.ExplosionInteraction.TNT);
    }

    // the crater phase: NTM bomb j goes off on the ground
    void crater(int j) {
        int[] s = CRATERS[j];
        var bomb = new com.hbm.explosion.vanillant.ExplosionVNT(level, s[0] + .5, ground + .5, s[1] + .5, CRATER_SIZE);
        try {
            // by name: the stand alone uses it, and the mod's list of what it needs of NTM (NtmApiTest) stays as it is
            bomb.getClass().getMethod("makeStandard").invoke(bomb);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("NTM's ExplosionVNT has no makeStandard", e);
        }
        bomb.explode();
    }

    // the crater phase: column i of the trench is dug out, as a player's spade would
    void dig(int i) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int d = 1; d <= TRENCH_DEPTH; d++) level.setBlock(p.set(TRENCH_X, ground - d, TRENCH_Z + i), AIR, Block.UPDATE_ALL);
    }

    // the blocks that are not air in the cube around explosion j's spot, down to the bottom
    int blocksAround(int j) {
        return blocksAround(BOOMS[j % BOOMS.length]);
    }

    int blocksAround(int[] s) {
        int n = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = s[0] - BOOM_REACH; x <= s[0] + BOOM_REACH; x++)
            for (int z = s[1] - BOOM_REACH; z <= s[1] + BOOM_REACH; z++)
                for (int y = bottom; y <= ground + BOOM_REACH; y++)
                    if (!level.getBlockState(p.set(x, y, z)).isAir()) n++;
        return n;
    }
}
