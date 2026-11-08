package acup.gunfeel.gas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeGrid;

/**
 * Ground under the gas for long becomes a poisoned biome of its own (server key {@code gasPoison}, on by
 * default), for good, as NTM's nuclear crater: nothing grows there, the plants on it die and the grass and leaves turn a
 * sick yellow-brown. The biome is {@code ntmgunfeel:poisoned} (its colours in its json); it is put into the chunk as NTM
 * puts its crater ({@code BiomeUtil.paintCraterBiome}): the chunk's biomes refilled with it where it goes and sent to the
 * players again.
 *
 * <p>How long is long. A biome cell is 4 by 4 blocks across; the server adds up the gas on its floor, the density of
 * every floor cell under the gas times the ticks it lay there times the gas's {@link #weight}, averaged over the 16
 * columns. The cell turns at {@link #THRESHOLD}. What is gathered halves every {@link #HALF_LIFE} ticks (10 minutes), so
 * a grenade now and then at one place never turns it. All four gases do it, each as hard as it is on plants and soil
 * (one magazine is 8 grenades fired a second apart; the sums in GasPoisonTest):
 *
 * <ul>
 * <li>mustard, weight 2: a persistent agent, its liquid soaks the soil and stays there for weeks. One magazine turns it
 * (about 2650), one grenade gives a sixth;
 * <li>chlorine, 0.6: gone with its cloud, but it bleaches and kills leaves (the 2005 Graniteville spill bleached the
 * plants for about a kilometre). Two magazines (about 2750), one is 1380;
 * <li>acid, 0.7: the acid mist burns the leaves and sours the soil. Two magazines (about 2410), one is 1210;
 * <li>phosgene, 0.5: water breaks it down to carbon dioxide and a little hydrochloric acid, plants suffer it least.
 * Three magazines (about 2480), two are 1660.
 * </ul>
 *
 * Gases fired at one place add up. The sums are kept while the server runs (a restart forgets them, not the biome).
 */
public final class GasPoison {
    public static final String KEY = "gasPoison";
    public static final ResourceKey<Biome> BIOME = ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("ntmgunfeel", "poisoned"));
    /** Density times ticks, averaged over a biome cell's floor, at which it turns. */
    public static final float THRESHOLD = 2000F;
    public static final int HALF_LIFE = 12000;
    /** Ticks between two sums. */
    public static final int EVERY = 20;
    /** A floor cell with less in it adds nothing. */
    public static final float LEAST = 0.05F;
    /** Blocks under and over the floor the biome goes. */
    public static final int BELOW = 4, ABOVE = 12;

    // one biome cell's sum: where the floor was last, when it was last added to, what it holds
    private static final class Dose {
        float sum;
        long at;
        int floorY;
        boolean done;
    }

    private static final Map<Level, Map<Long, Dose>> LEVELS = new WeakHashMap<>();

    private GasPoison() {
    }

    public static boolean enabled() {
        return acup.gunfeel.ReaimTimes.flag(KEY, true);
    }

    /** How hard a gas is on the ground, what its density counts for in the sum (the class's comment). */
    public static float weight(GasKind g) {
        return switch (g) {
            case MUSTARD -> 2.0F;
            case CHLORINE -> 0.6F;
            case ACID -> 0.7F;
            case PHOSGENE -> 0.5F;
        };
    }

    static long key(int qx, int qz) {
        return (long) qx << 32 | qz & 0xFFFFFFFFL;
    }

    /** Every server tick; the sums every {@link #EVERY}. */
    public static void tick(ServerLevel level) {
        if (level.getGameTime() % EVERY != 0 || !enabled()) return;
        SmokeClouds all = SmokeClouds.get(level);
        Map<Long, Dose> doses = LEVELS.get(level);
        if (all != null && !all.isEmpty()) {
            long now = level.getGameTime();
            Map<Long, Dose> turned = null;
            for (SmokeClouds.Cloud c : all.clouds()) {
                GasKind g = GasKind.of(c.kind());
                if (g == null) continue;
                float w = weight(g);
                for (int i : c.grid.floorCells(LEAST)) {
                    int x = c.baseX() + i % SmokeGrid.SX, z = c.baseZ() + i / SmokeGrid.SX % SmokeGrid.SZ, y = c.baseY() + i / (SmokeGrid.SX * SmokeGrid.SZ);
                    // gas on water soaks nothing
                    if (!level.getFluidState(new BlockPos(x, y - 1, z)).isEmpty()) continue;
                    if (doses == null) LEVELS.put(level, doses = new HashMap<>());
                    long k = key(QuartPos.fromBlock(x), QuartPos.fromBlock(z));
                    Dose d = doses.computeIfAbsent(k, q -> new Dose());
                    add(d, Math.min(1F, c.grid.density(i)) * w * EVERY / 16F, now);
                    d.floorY = y;
                    if (!d.done && d.sum >= THRESHOLD) {
                        d.done = true;
                        if (turned == null) turned = new HashMap<>();
                        turned.put(k, d);
                    }
                }
            }
            // a cell whose chunk could not be painted now is tried again with the next sum
            if (turned != null) for (Map.Entry<Long, Dose> e : turned.entrySet()) if (!paint(level, Map.of(e.getKey(), e.getValue()))) e.getValue().done = false;
        }
        // the ones that faded out are dropped now and then
        if (doses != null && level.getGameTime() % 1200 == 0) {
            long now = level.getGameTime();
            doses.values().removeIf(d -> !d.done && decayed(d.sum, now - d.at) < 1F);
            if (doses.isEmpty()) LEVELS.remove(level);
        }
    }

    static float decayed(float sum, long ticks) {
        return ticks <= 0 ? sum : (float) (sum * Math.pow(0.5, ticks / (double) HALF_LIFE));
    }

    private static void add(Dose d, float amount, long now) {
        d.sum = decayed(d.sum, now - d.at) + amount;
        d.at = now;
    }

    public static void forget(Level level) {
        LEVELS.remove(level);
    }

    // the biome cells that turned, chunk by chunk, as NTM paints its crater
    private static boolean paint(ServerLevel level, Map<Long, Dose> turned) {
        Holder<Biome> biome = level.registryAccess().lookupOrThrow(Registries.BIOME).get(BIOME).orElse(null);
        if (biome == null) return false;
        Map<Long, List<long[]>> byChunk = new HashMap<>();
        for (Map.Entry<Long, Dose> e : turned.entrySet()) {
            int qx = (int) (e.getKey() >> 32), qz = (int) (long) e.getKey();
            int cx = QuartPos.toBlock(qx) >> 4, cz = QuartPos.toBlock(qz) >> 4;
            byChunk.computeIfAbsent(new ChunkPos(cx, cz).pack(), k -> new ArrayList<>())
                    .add(new long[] {qx, qz, QuartPos.fromBlock(e.getValue().floorY - BELOW), QuartPos.fromBlock(e.getValue().floorY + ABOVE)});
        }
        List<ChunkAccess> sent = new ArrayList<>();
        for (Map.Entry<Long, List<long[]>> e : byChunk.entrySet()) {
            ChunkPos at = ChunkPos.unpack(e.getKey());
            int cx = at.x(), cz = at.z();
            ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            List<long[]> cells = e.getValue();
            chunk.fillBiomesFromNoise((x, y, z, sampler) -> {
                for (long[] q : cells) if (q[0] == x && q[1] == z && y >= q[2] && y <= q[3]) return biome;
                return chunk.getNoiseBiome(x, y, z);
            }, level.getChunkSource().randomState().sampler());
            chunk.markUnsaved();
            sent.add(chunk);
            for (long[] q : cells) wither(level, (int) q[0], (int) q[1], QuartPos.toBlock((int) q[2]), QuartPos.toBlock((int) q[3]) + 3);
        }
        if (!sent.isEmpty()) level.getChunkSource().chunkMap.resendBiomesForChunks(sent);
        return !sent.isEmpty();
    }

    // the plants of a biome cell that turned die: gone, now and then a dead bush in their place
    private static void wither(ServerLevel level, int qx, int qz, int y0, int y1) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int bx = QuartPos.toBlock(qx), bz = QuartPos.toBlock(qz);
        for (int y = y1; y >= y0; y--) for (int dz = 0; dz < 4; dz++) for (int dx = 0; dx < 4; dx++) {
            p.set(bx + dx, y, bz + dz);
            BlockState s = level.getBlockState(p);
            // not under water: kelp and sea grass are left, the water with them
            if (plant(s) && s.getFluidState().isEmpty()) die(level, p.immutable());
        }
    }

    // what dies on poisoned ground: the plants (VegetationBlock: flowers, grass, ferns, saplings, crops, mushrooms and fungi,
    // lily pads), sugar cane, cacti, vines, kelp and the like and bamboo; not the dead bush, the leaves or the trees' wood
    public static boolean plant(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof net.minecraft.world.level.block.DryVegetationBlock) return false;
        return b instanceof net.minecraft.world.level.block.VegetationBlock || b instanceof net.minecraft.world.level.block.SugarCaneBlock
                || b instanceof net.minecraft.world.level.block.CactusBlock || b instanceof net.minecraft.world.level.block.VineBlock
                || b instanceof net.minecraft.world.level.block.GrowingPlantBlock || b instanceof net.minecraft.world.level.block.BambooStalkBlock;
    }

    static void die(ServerLevel level, BlockPos pos) {
        BlockState bush = Blocks.DEAD_BUSH.defaultBlockState();
        boolean leave = Math.floorMod(pos.getX() * 31 + pos.getZ() * 17 + pos.getY(), 4) == 0;
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        if (leave && bush.canSurvive(level, pos)) level.setBlock(pos, bush, Block.UPDATE_ALL);
    }

    /** Whether a block stands in the poisoned biome (its own biome cell, not the blended one the game draws). */
    public static boolean poisoned(Level level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return false;
        return level.getChunkAt(pos).getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()), QuartPos.fromBlock(pos.getZ())).is(BIOME);
    }

    /**
     * A random tick (MixinPoisonTick): whether it is taken away. A plant on poisoned ground dies instead of growing, and grass
     * does not spread from it.
     */
    public static boolean randomTick(BlockState s, ServerLevel level, BlockPos pos) {
        boolean plant = plant(s);
        if (!plant && !(s.getBlock() instanceof net.minecraft.world.level.block.SpreadingSnowyBlock)) return false;
        if (!enabled() || !poisoned(level, pos)) return false;
        if (plant && s.getFluidState().isEmpty()) die(level, pos);
        return true;
    }

    /** What bone meal would make grow: a plant, grass, anything bonemealable. */
    public static boolean grows(BlockState s) {
        return plant(s) || s.getBlock() instanceof net.minecraft.world.level.block.SpreadingSnowyBlock
                || s.getBlock() instanceof net.minecraft.world.level.block.BonemealableBlock;
    }

    // dev and tests: the sum of the biome cell a block is in, now
    public static float sumAt(Level level, int x, int z) {
        Map<Long, Dose> doses = LEVELS.get(level);
        Dose d = doses == null ? null : doses.get(key(QuartPos.fromBlock(x), QuartPos.fromBlock(z)));
        return d == null ? 0F : decayed(d.sum, level.getGameTime() - d.at);
    }
}
