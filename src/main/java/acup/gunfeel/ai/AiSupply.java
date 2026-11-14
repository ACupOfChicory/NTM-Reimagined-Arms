package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ReaimTimes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.core.BlockPos;

/**
 * The squad's supplier (stage 5, R1; the owner, 2026-11-14: "a role of the one that hands out supplies"). A soldier
 * with the role (AiRole.SUPPLIER: by word, or one of those spawned by number) carries a stock besides its own:
 * magazines, grenades, smoke grenades. Now and then it looks who of its squad is short: less than a magazine besides
 * the gun's, or no grenade, or no smoke grenade. It goes to the one most in need within REACH, while it is not pinned
 * down itself, no one it sees is near, and the other is not out in sight of one: runs up, hands a portion over, and
 * is a rifleman again. One it has given to is not given to again for AGAIN ticks; a run takes RUN_MAX ticks at most.
 * It fills its own pouches from the stock too. The stock is not endless and is kept on the mob.
 * No word goes between them: the supplier looks at what the others have, as one of a squad sees who is out.
 */
public final class AiSupply {
    /** the server settings: suppliers hand out; what one carries to hand out */
    public static final String KEY = "aiSupply", MAGS_KEY = "aiSupplyMagazines", GRENADES_KEY = "aiSupplyGrenades", SMOKES_KEY = "aiSupplySmokes";
    static final int MAGS = 12, GRENADES = 6, SMOKES = 3;
    /** where the stock is kept on the mob */
    static final String STOCK_KEY = "armsaiSupply";
    /** off only for the stands' "before" */
    static boolean on = true;
    /** a supplier looks who is short this often; a run takes this long at most; one given to is left alone this long; after a run given up, the next look; ticks */
    static final int EVERY = 10, RUN_MAX = 300, AGAIN = 400, GIVEN_UP = 100;
    /** it goes to one this near at most; hands over this near; a foe it sees nearer than this keeps it where it is; blocks */
    static final double REACH = 24, HAND = 2.2, FOE_NEAR = 12;
    /** magazines in one portion */
    static final int PORTION = 2;
    /** the stands: runs begun, runs given up, magazines, grenades and smoke grenades handed over */
    public static int runs, gaveUp, mags, grenades, smokes;
    /** the stands: a supplier's looks that found no one to go to, by why: it is not free itself, it has no squad, none is short, one short is out in sight of them, too far, given to lately */
    public static final int[] idle = new int[6];

    private AiSupply() {
    }

    public static boolean on() {
        return on && ReaimTimes.flag(KEY, true);
    }

    private static int key(String key, int def) {
        return Math.max(0, Math.min(64, Math.round(ReaimTimes.number(key, def))));
    }

    /** the supplier's stock {magazines, grenades, smoke grenades}: read off the mob, or what a new one carries */
    static int[] stock(AiBot bot) {
        if (bot.supply == null) {
            int[] kept = bot.mob.getPersistentData().getIntArray(STOCK_KEY).orElse(null);
            bot.supply = kept != null && kept.length == 3 ? kept.clone() : new int[] {key(MAGS_KEY, MAGS), key(GRENADES_KEY, GRENADES), key(SMOKES_KEY, SMOKES)};
            if (kept == null || kept.length != 3) keep(bot);
        }
        return bot.supply;
    }

    private static void keep(AiBot bot) {
        if (bot.supply != null) bot.mob.getPersistentData().putIntArray(STOCK_KEY, bot.supply.clone());
    }

    /** what this one is short of that the stock has: magazines (2), a grenade (1), a smoke grenade (0.5); 0: nothing */
    static double need(AiBot b, int[] stock) {
        double need = 0;
        AiAmmo ammo = b.gunner.ammo;
        if (stock[0] > 0 && !ammo.infinite() && b.gunner.capacity() > 0 && ammo.reserve() < b.gunner.capacity()) need += 2;
        if (stock[1] > 0 && b.grenades <= 0 && AiGrenades.stock() > 0) need += 1;
        if (stock[2] > 0 && b.smokes <= 0 && AiSmoke.stock() > 0) need += 0.5;
        return need;
    }

    /**
     * A supplier, this tick: true while it runs to one of its squad with supplies (it does nothing else then); false:
     * it fights as a rifleman. Called by its brain after what keeps it alive (a grenade to run from, its own throw).
     */
    static boolean step(AiSoldier s, AiBot bot, ServerLevel level, long tick) {
        if (!on() || AiRole.of(bot) != AiRole.SUPPLIER) return false;
        int[] stock = stock(bot);
        AiBot to = bot.supplyTo;
        if (to == null) {
            if ((tick + bot.mob.getId()) % EVERY != 0 || tick < bot.supplyNext) return false;
            // its own pouches first, where it stands
            if (need(bot, stock) > 0 && tick - bot.suppliedAt >= AGAIN) hand(s, bot, bot, stock, tick);
            if (!free(bot, tick)) {
                idle[0]++;
                return false;
            }
            to = pick(bot, stock, tick);
            if (to == null) return false;
            bot.supplyTo = to;
            bot.supplyAt = tick;
            runs++;
            s.note(bot, tick, "supplies: goes to #" + to.mob.getId());
        }
        // the one it goes to is down or needs nothing now, the way is too long, it is pinned down or one is on it: given up
        if (!AiSquads.live(to) || need(to, stock) <= 0 || tick - bot.supplyAt > RUN_MAX || !free(bot, tick) || bot.mover.failed() && bot.mover.goalIs(cell(to))) {
            bot.supplyTo = null;
            bot.supplyNext = tick + GIVEN_UP;
            gaveUp++;
            s.note(bot, tick, "supplies: gives the run up");
            return false;
        }
        double dx = to.mob.getX() - bot.mob.getX(), dz = to.mob.getZ() - bot.mob.getZ(), dy = to.mob.getY() - bot.mob.getY();
        if (dx * dx + dz * dz <= HAND * HAND && Math.abs(dy) < 2) {
            hand(s, bot, to, stock, tick);
            bot.supplyTo = null;
            return false;
        }
        bot.gunner.fire(false);
        long goal = cell(to);
        if (!bot.mover.goalIs(goal)) {
            bot.mover.costs(FireLines.of(level, bot.mob));
            bot.mover.goTo(goal, 1.5, AiSoldier.RUN);
        }
        return true;
    }

    private static long cell(AiBot b) {
        return BlockPos.asLong(Mth.floor(b.mob.getX()), Mth.floor(b.mob.getY() + 0.2), Mth.floor(b.mob.getZ()));
    }

    /** not pinned down, not lying, not reloading, and none it sees near */
    private static boolean free(AiBot bot, long tick) {
        if (bot.prone.pinned(tick) || bot.prone.lying()) return false;
        TargetMemory.Contact c = bot.memory.focus(bot.mob.getX(), bot.mob.getZ());
        return c == null || c.state != TargetMemory.State.VISIBLE || bot.mob.distanceToSqr(c.x, c.y, c.z) >= FOE_NEAR * FOE_NEAR;
    }

    /** the one of its squad most in need within reach, not given to lately, not out in sight of one; the nearer of two alike */
    private static @Nullable AiBot pick(AiBot bot, int[] stock, long tick) {
        AiSquads.Squad squad = AiSquads.of(bot);
        if (squad == null) {
            idle[1]++;
            return null;
        }
        AiBot best = null;
        double most = 0, bestD = Double.MAX_VALUE;
        int why = 2;
        for (AiBot b : squad.bots) {
            if (b == bot || !AiSquads.live(b)) continue;
            double need = need(b, stock);
            if (need <= 0) continue;
            double d = b.mob.distanceToSqr(bot.mob);
            if (tick - b.suppliedAt < AGAIN) {
                why = Math.max(why, 5);
                continue;
            }
            if (d > REACH * REACH) {
                why = Math.max(why, 4);
                continue;
            }
            if (exposed(b)) {
                why = Math.max(why, 3);
                continue;
            }
            if (need > most || need == most && d < bestD) {
                most = need;
                bestD = d;
                best = b;
            }
        }
        if (best == null) idle[why]++;
        return best;
    }

    /** it sees one of them and is not at a place of cover: to run up to it is to run into that one's fire */
    private static boolean exposed(AiBot b) {
        TargetMemory.Contact c = b.memory.focus(b.mob.getX(), b.mob.getZ());
        if (c == null || c.state != TargetMemory.State.VISIBLE) return false;
        return !(b.brain instanceof AiSoldier s && s.spot() != null && b.mover.arrived());
    }

    /** a portion out of the stock to this one: magazines for its gun, grenades and a smoke grenade up to what a soldier carries */
    private static void hand(AiSoldier s, AiBot bot, AiBot to, int[] stock, long tick) {
        StringBuilder what = new StringBuilder();
        AiAmmo ammo = to.gunner.ammo;
        if (stock[0] > 0 && !ammo.infinite() && to.gunner.capacity() > 0 && ammo.reserve() < to.gunner.capacity()) {
            int n = Math.min(PORTION, stock[0]);
            ammo.give(to.gunner.capacity() * n);
            AiAdapter.keep(to);
            stock[0] -= n;
            mags += n;
            what.append(n).append(" magazines");
        }
        if (stock[1] > 0 && to.grenades <= 0 && AiGrenades.stock() > 0) {
            int n = Math.min(AiGrenades.stock(), stock[1]);
            to.grenades += n;
            AiAdapter.keepGrenades(to);
            stock[1] -= n;
            grenades += n;
            what.append(what.length() == 0 ? "" : ", ").append(n).append(" grenades");
        }
        if (stock[2] > 0 && to.smokes <= 0 && AiSmoke.stock() > 0) {
            int n = Math.min(AiSmoke.stock(), stock[2]);
            to.smokes += n;
            AiAdapter.keepSmokes(to);
            stock[2] -= n;
            smokes += n;
            what.append(what.length() == 0 ? "" : ", ").append(n).append(" smoke grenades");
        }
        to.suppliedAt = tick;
        keep(bot);
        if (what.length() > 0) s.note(bot, tick, "supplies: " + what + (to == bot ? " for itself" : " to #" + to.mob.getId()) + "; left " + stock[0] + "/" + stock[1] + "/" + stock[2]);
    }

    /** the stock as shown over a supplier: magazines/grenades/smoke grenades */
    static String shown(AiBot bot) {
        int[] st = stock(bot);
        return st[0] + "/" + st[1] + "/" + st[2];
    }
}
