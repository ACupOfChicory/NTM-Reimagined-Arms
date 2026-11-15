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
 * It fills its own pouches from the stock too. The stock is not endless and is kept on the mob. A supplier down, its
 * stock lies where it fell for LEFT_KEEP ticks: one of its side within REACH goes for it and is the supplier from then.
 * No word goes between them: the supplier looks at what the others have, as one of a squad sees who is out.
 */
public final class AiSupply {
    /** the server settings: suppliers hand out; what one carries to hand out */
    public static final String KEY = "aiSupply", MAGS_KEY = "aiSupplyMagazines", GRENADES_KEY = "aiSupplyGrenades", SMOKES_KEY = "aiSupplySmokes";
    static final int MAGS = 12, GRENADES = 6, SMOKES = 3;
    /** the server setting: the share of the mobs spawned by number with no role named that are suppliers */
    public static final String SHARE_KEY = "aiSupplyShare";
    static final float SHARE = 0.17F;
    /** the server setting: a squad with no round left besides what is in its guns, and no supplier with any, falls back */
    public static final String OUT_KEY = "aiSupplyOut";
    /** a squad out of rounds: the confidence of each of it falls by this each time squads are made (AiSquads.EVERY) till it falls back */
    static final float OUT = 0.1F;
    /** a squad out of rounds loses heart no lower than this (back to LOW soon once supplied) */
    static final float OUT_FLOOR = 0.1F;
    /** the stands: times a squad was found out of rounds, by team; off only for the stands' "before" */
    public static final java.util.Map<String, Integer> outs = new java.util.HashMap<>();
    static boolean outOn = true;

    static float share() {
        return Math.max(0F, Math.min(1F, ReaimTimes.number(SHARE_KEY, SHARE)));
    }

    /**
     * A squad as squads are made (AiSquads): out of rounds when, in a fight (one of it knows of a foe), none of it alive
     * has a round besides what is in its gun and none of its suppliers has a magazine to hand out. Then each of it loses
     * heart (AiConfidence), down to OUT_FLOOR, and, kept so, holds back and falls back; a supplier that comes, or a
     * stock taken up, and they take heart again. A squad at rest with empty pouches is left as it is.
     */
    static void squad(java.util.List<AiBot> bots, long tick) {
        if (!on() || !outOn || !ReaimTimes.flag(OUT_KEY, true)) return;
        int alive = 0;
        boolean fight = false;
        for (AiBot b : bots) {
            if (!AiSquads.live(b)) continue;
            alive++;
            AiAmmo ammo = b.gunner.ammo;
            if (ammo.infinite() || ammo.reserve() > 0 || b.gunner.capacity() <= 0) return;
            if (AiRole.of(b) == AiRole.SUPPLIER && stock(b)[0] > 0) return;
            fight |= b.memory.focus(b.mob.getX(), b.mob.getZ()) != null;
        }
        if (alive == 0 || !fight) return;
        outs.merge(AiSquads.team(bots.get(0)), 1, Integer::sum);
        for (AiBot b : bots) {
            if (!AiSquads.live(b)) continue;
            float v = b.confidence.value(tick);
            if (v > OUT_FLOOR) b.confidence.add(-Math.min(OUT, v - OUT_FLOOR), tick);
        }
    }

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

    /** a fallen supplier's stock lies where it fell this long; who may take it is looked for this often; ticks */
    static final int LEFT_KEEP = 2400, HEIR_EVERY = 40;
    /** the stands: stocks left by fallen suppliers, stocks taken up */
    public static int left, taken;

    /** a stock is let lie for good after this many have given a run for it up */
    static final int GIVE_UPS = 3;

    /**
     * A fallen supplier's stock where it fell: of which side, since when; the ids of its squad then (they go for it
     * first); who goes for it (an id: no mob is held), since when; who gave a run for it up.
     */
    static final class Left {
        final double x, y, z;
        final int[] stock;
        final String team;
        final long tick;
        final int[] squad;
        int heir = -1;
        long heirAt;
        final it.unimi.dsi.fastutil.ints.IntArrayList failed = new it.unimi.dsi.fastutil.ints.IntArrayList();

        Left(double x, double y, double z, int[] stock, String team, long tick, int[] squad) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.stock = stock;
            this.team = team;
            this.tick = tick;
            this.squad = squad;
        }

        boolean ofSquad(int id) {
            for (int i : squad) if (i == id) return true;
            return false;
        }

        long cell() {
            return BlockPos.asLong(Mth.floor(x), Mth.floor(y + 0.2), Mth.floor(z));
        }
    }

    private static final java.util.Map<ServerLevel, java.util.List<Left>> LEFT = new java.util.WeakHashMap<>();

    private AiSupply() {
    }

    public static void forget(ServerLevel level) {
        LEFT.remove(level);
    }

    /** an AI mob is down: a supplier's stock stays where it fell, for one of its side to take up */
    static void fell(ServerLevel level, AiBot bot, long tick) {
        if (!on() || AiRole.of(bot) != AiRole.SUPPLIER) return;
        int[] stock = stock(bot);
        if (stock[0] + stock[1] + stock[2] <= 0) return;
        java.util.List<Left> all = LEFT.computeIfAbsent(level, l -> new java.util.ArrayList<>());
        all.removeIf(l -> tick - l.tick > LEFT_KEEP);
        if (all.size() >= 32) all.remove(0);
        AiSquads.Squad sq = AiSquads.of(bot);
        int[] squad = sq == null ? new int[0] : sq.bots.stream().filter(b -> b != bot).mapToInt(b -> b.mob.getId()).toArray();
        all.add(new Left(bot.mob.getX(), bot.mob.getY(), bot.mob.getZ(), stock.clone(), AiSquads.team(bot), tick, squad));
        left++;
    }

    /**
     * A fallen supplier's stock within reach: one of its side goes for it and is the supplier from then on (one that
     * is a supplier already adds it to its own). One goes for a stock at a time; one of the fallen one's squad first,
     * while any of it lives; none goes with a foe in sight (the stock lies where they shot it), nor one that gave a run
     * for it up. True while it is on its way.
     */
    private static boolean fetch(AiSoldier s, AiBot bot, ServerLevel level, long tick) {
        Left mine = bot.supplyFetch;
        // the base of fire keeps its place, a medic its kits: neither goes for one
        if (mine == null && ((tick + bot.mob.getId()) % HEIR_EVERY != 0 || tick < bot.supplyNext || AiRole.of(bot) == AiRole.BASE || AiRole.of(bot) == AiRole.MEDIC)) return false;
        java.util.List<Left> all = LEFT.get(level);
        if (mine == null) {
            if (all == null || all.isEmpty() || !free(bot, tick) || seesFoe(bot)) return false;
            String team = AiSquads.team(bot);
            int id = bot.mob.getId();
            for (java.util.Iterator<Left> it = all.iterator(); it.hasNext();) {
                Left l = it.next();
                if (tick - l.tick > LEFT_KEEP || l.failed.size() >= GIVE_UPS) {
                    it.remove();
                    continue;
                }
                AiBot heir = l.heir < 0 ? null : bot(level, l.heir);
                boolean gone = heir == null || !AiSquads.live(heir) || heir.supplyFetch != l || tick - l.heirAt > RUN_MAX;
                if (!gone || !l.team.equals(team) || l.failed.contains(id) || bot.mob.distanceToSqr(l.x, l.y, l.z) > REACH * REACH) continue;
                // its squad first: another goes only when none of it is left
                if (!l.ofSquad(id) && squadLives(level, l)) continue;
                l.heir = id;
                l.heirAt = tick;
                bot.supplyFetch = mine = l;
                s.note(bot, tick, "supplies: goes for a fallen supplier's stock");
                break;
            }
            if (mine == null) return false;
        }
        if (all == null || !all.contains(mine) || tick - mine.heirAt > RUN_MAX || !free(bot, tick) || bot.mover.failed() && bot.mover.goalIs(mine.cell())) {
            bot.supplyFetch = null;
            bot.supplyNext = tick + GIVEN_UP;
            mine.failed.add(bot.mob.getId());
            mine.heir = -1;
            return false;
        }
        double dx = mine.x - bot.mob.getX(), dz = mine.z - bot.mob.getZ();
        if (dx * dx + dz * dz <= HAND * HAND && Math.abs(mine.y - bot.mob.getY()) < 2) {
            all.remove(mine);
            bot.supplyFetch = null;
            int[] stock = AiRole.of(bot) == AiRole.SUPPLIER ? stock(bot) : new int[3];
            for (int i = 0; i < 3; i++) stock[i] = Math.min(64, stock[i] + mine.stock[i]);
            bot.supply = stock;
            if (AiRole.of(bot) != AiRole.SUPPLIER) AiRole.assign(bot, AiRole.SUPPLIER);
            keep(bot);
            taken++;
            s.note(bot, tick, "supplies: takes up a fallen supplier's stock, has " + stock[0] + "/" + stock[1] + "/" + stock[2]);
            return false;
        }
        bot.gunner.fire(false);
        if (!bot.mover.goalIs(mine.cell())) {
            bot.mover.costs(FireLines.of(level, bot.mob));
            bot.mover.goTo(mine.cell(), 1.5, AiSoldier.RUN);
        }
        return true;
    }

    private static @Nullable AiBot bot(ServerLevel level, int id) {
        net.minecraft.world.entity.Entity e = level.getEntity(id);
        return e == null ? null : AiBots.get(e);
    }

    /** one of the fallen one's squad is alive still */
    private static boolean squadLives(ServerLevel level, Left l) {
        for (int i : l.squad) {
            AiBot b = bot(level, i);
            if (b != null && AiSquads.live(b)) return true;
        }
        return false;
    }

    /** it sees one of them now, at any distance */
    private static boolean seesFoe(AiBot bot) {
        TargetMemory.Contact c = bot.memory.focus(bot.mob.getX(), bot.mob.getZ());
        return c != null && c.state == TargetMemory.State.VISIBLE;
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
        if (!on()) return false;
        // a fallen supplier's stock first: anyone of its side, a supplier on no run
        if ((AiRole.of(bot) != AiRole.SUPPLIER || bot.supplyTo == null) && fetch(s, bot, level, tick)) return true;
        if (AiRole.of(bot) != AiRole.SUPPLIER) return false;
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
    static boolean free(AiBot bot, long tick) {
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
            // another supplier of the squad is on its way to that one already
            boolean served = false;
            for (AiBot o : squad.bots) served |= o != bot && o.supplyTo == b && AiSquads.live(o);
            if (served) continue;
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
    static boolean exposed(AiBot b) {
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
