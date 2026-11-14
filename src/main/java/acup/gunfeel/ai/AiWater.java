package acup.gunfeel.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * An AI mob in water: wading (on the bed, its head out: it walks and fights as on land, slower) or swimming (afloat
 * over water too deep to stand in, or its eyes under). Swimming it does not fire, throw or lie down; its paths keep
 * out of such water where they can ({@link PathSearch}: a swim costs {@link PathSearch#SWIM} a step, and under fire
 * none is begun). Server thread.
 */
public final class AiWater {
    private AiWater() {
    }

    /** how far under its feet a bed it stands on is looked for, blocks: a mob bobs up some way off the bed of a ford */
    static final double BED = 1.2;

    /** its suppression (a share) from which it counts as under fire; hurt by a mob this many ticks ago or less, too */
    static final float FIRE = 0.25F;
    static final int HURT = 60;

    /** under fire: it begins no swim (pinned down, suppressed a quarter, or hurt by a mob lately) */
    static boolean underFire(AiBot bot, long tick) {
        Mob m = bot.mob;
        return bot.prone.pinned(tick) || bot.prone.suppressed() >= FIRE
                || m.getLastHurtByMob() != null && m.tickCount - m.getLastHurtByMobTimestamp() <= HURT;
    }

    /** how far round a swimming mob a cell to get out at is looked for, blocks across; and down (up: a step, one) */
    static final int SHORE = 10, SHORE_DOWN = 2;

    /**
     * The nearest cell across where a mob swimming here gets out of the water (on the bank, or on the bed of a ford),
     * no more than a step over the surface it swims at and not `not` (one it could not get to): a BlockPos long, or
     * Long.MIN_VALUE if none is within {@link #SHORE}
     */
    static long ashore(TacticalMap map, Mob mob, long not) {
        int bx = mob.getBlockX(), by = mob.getBlockY(), bz = mob.getBlockZ();
        long best = Long.MIN_VALUE;
        double bestD = Double.MAX_VALUE;
        for (int sx = (bx - SHORE) >> 4; sx <= (bx + SHORE) >> 4; sx++)
            for (int sz = (bz - SHORE) >> 4; sz <= (bz + SHORE) >> 4; sz++)
                for (int sy = (by - SHORE_DOWN) >> 4; sy <= (by + 1) >> 4; sy++) {
                    SectionCells c = map.cells(net.minecraft.core.SectionPos.asLong(sx, sy, sz));
                    if (c == null) continue;
                    for (int i = 0; i < c.count(); i++) {
                        if ((c.flags(i) & SectionCells.SWIM) != 0) continue;
                        int x = (sx << 4) + c.x(i), y = (sy << 4) + c.y(i), z = (sz << 4) + c.z(i);
                        if (y > by + 1 || y < by - SHORE_DOWN) continue;
                        double dx = x + 0.5 - mob.getX(), dz = z + 0.5 - mob.getZ(), d = dx * dx + dz * dz;
                        if (d > SHORE * SHORE || d >= bestD) continue;
                        long cell = BlockPos.asLong(x, y, z);
                        if (cell == not) continue;
                        bestD = d;
                        best = cell;
                    }
                }
        return best;
    }

    /** afloat: in water, its eyes under, or no bed within {@link #BED} under its feet */
    public static boolean swims(Mob mob) {
        if (!mob.isInWater()) return false;
        if (mob.isEyeInFluid(FluidTags.WATER)) return true;
        Level level = mob.level();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (double d : new double[] {0.2, BED}) {
            p.set(mob.getBlockX(), (int) Math.floor(mob.getY() - d), mob.getBlockZ());
            BlockState s = level.getBlockState(p);
            if (!s.getFluidState().is(FluidTags.WATER) || !s.getCollisionShape(level, p).isEmpty()) return false;
        }
        return true;
    }
}
