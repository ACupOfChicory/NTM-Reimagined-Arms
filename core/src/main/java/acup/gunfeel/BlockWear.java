package acup.gunfeel;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * What bullets did to blocks not broken yet, by a break rule that wears them (BulletRuleText.Break.worn): per place
 * in a level ({@code L}, the version's; the place as the version packs it in a long), the hits or damage so far, the
 * game tick the last came in and the block it was done to; once that block is broken or replaced (by hand, a piston,
 * a command) the wear and its cracks go, and wear nothing added to for {@link #FORGET_TICKS} goes. The version shows
 * the cracks ({@link Cracks}) and tells what is gone ({@link Gone}); whether a bullet may harm the block at all
 * (unbreakable, protected, too weak) it asks before.
 */
public final class BlockWear<L> {
    /** 20 s at 20 TPS; game ticks, so a paused or slowed server does not forget it sooner */
    public static final long FORGET_TICKS = 400L;

    /** a hit that breaks the block now ({@link #hit}) */
    public static final int BREAKS = -1;

    /** The version's cracks on a block: stage 0..9, -1 none. */
    public interface Cracks<L> {
        void show(L level, long pos, int stage);
    }

    /** The version's world for the sweep: the place unloaded or another block there now, and a level's game tick. */
    public interface Gone<L> {
        boolean gone(L level, long pos, Object block);

        long time(L level);
    }

    private record Spot<L>(L level, long pos) {
    }

    private record Wear(float done, long at, Object block) {
    }

    private final Map<Spot<L>, Wear> wear = new HashMap<>();
    private int ticks;

    /**
     * One hit of {@code damage} on the block ({@code block}: the version's block, by identity) at the place, by its
     * rule, at game tick {@code now}, after wear too old anywhere is forgotten (its cracks off): {@link #BREAKS}, its
     * cracks off; else it stands, its wear so far in {@code out[0]} of {@code out[1]} needed, and the cracks to show for
     * it (0..9), which the caller shows itself.
     */
    public int hit(L level, long pos, Object block, float damage, float hardness, BulletRuleText.Break rule, long now, Cracks<L> cracks,
            float[] out) {
        forget(now, cracks);
        if (rule.worn()) {
            Spot<L> spot = new Spot<>(level, pos);
            Wear w = wear.get(spot);
            if (w != null && w.block() != block) w = null;
            float add = rule.wear(damage);
            float need = rule.need(hardness);
            float done = (w == null ? 0F : w.done()) + add;
            if (need > 0F && done < need) {
                wear.put(spot, new Wear(done, now, block));
                out[0] = done;
                out[1] = need;
                return BulletRuleText.Break.cracks(done, need);
            }
            wear.remove(spot);
            cracks.show(level, pos, -1);
        }
        return BREAKS;
    }

    private void forget(long now, Cracks<L> cracks) {
        Iterator<Map.Entry<Spot<L>, Wear>> it = wear.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Spot<L>, Wear> e = it.next();
            if (now - e.getValue().at() < FORGET_TICKS) continue;
            cracks.show(e.getKey().level(), e.getKey().pos(), -1);
            it.remove();
        }
    }

    /** every few server ticks: wear on a block that is no longer there (broken, replaced, its chunk gone) goes with its cracks */
    public void tick(Gone<L> world, Cracks<L> cracks) {
        if (wear.isEmpty() || ++ticks % 5 != 0) return;
        Iterator<Map.Entry<Spot<L>, Wear>> it = wear.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Spot<L>, Wear> e = it.next();
            L level = e.getKey().level();
            long pos = e.getKey().pos();
            boolean gone = world.gone(level, pos, e.getValue().block());
            if (!gone && world.time(level) - e.getValue().at() < FORGET_TICKS) continue;
            cracks.show(level, pos, -1);
            it.remove();
        }
    }

    /** dev: every block as if no bullet had hit it yet */
    public void clear() {
        wear.clear();
    }

    /** dev: the wear as it is now, to go back to ({@link #back}) */
    public Object now() {
        return new HashMap<>(wear);
    }

    /** dev: back to the wear as it was ({@link #now}) */
    @SuppressWarnings("unchecked")
    public void back(Object was) {
        wear.clear();
        wear.putAll((Map<Spot<L>, Wear>) was);
    }
}
