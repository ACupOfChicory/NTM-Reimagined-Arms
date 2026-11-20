package acup.gunfeel.ai;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.ReaimTimes;
import acup.gunfeel.LevelMap;
import acup.gunfeel.GameMath;
import acup.gunfeel.PosLong;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.EntityView;

/**
 * The squad's supplier (stage 5, R1; the owner, 2026-11-14: "a role of the one that hands out supplies"). A soldier
 * with the role (AiRole.SUPPLIER: by word, or one of those spawned by number) carries a stock besides its own:
 * magazines, grenades, smoke grenades. Now and then it looks who of its squad is short: a magazine or less besides
 * the gun's, or no grenade, or no smoke grenade. It goes to the one most in need within REACH, while it is not pinned
 * down itself, no one it sees is near, and the other is not out in sight of one: runs up, hands a portion over, and
 * is a rifleman again. One it has given to is not given to again for AGAIN ticks; a run takes RUN_MAX ticks at most.
 * It fills its own pouches from the stock too. The stock is not endless and is kept on the mob. A supplier down, its
 * stock lies where it fell for LEFT_KEEP ticks: one of its side within REACH goes for it and is the supplier from then.
 * No word goes between them: the supplier looks at what the others have, as one of a squad sees who is out.
 */
public class AiSupplyBase {
    interface Staff<B extends AiBotBase, S> {
        /** 26.2: the stock kept on the mob. */
        int @Nullable [] readStock(B b);
        /** 26.2: keep the stock on the mob. */
        void writeStock(B b, int[] stock);
        /** 26.2: the recipient. */
        @Nullable B target(B b);
        /** 26.2: set the recipient. */
        void target(B b, @Nullable B to);
        /** 26.2: the fallen stock being fetched. */
        @Nullable Left fetching(B b);
        /** 26.2: set the fallen stock being fetched. */
        void fetching(B b, @Nullable Left left);
        /** 26.2: the level's entities. */
        EntityView entities(Object level);
        /** 26.2: a soldier with a cover spot, in any mode. */
        boolean hasSpot(B b);
        /** 26.2: take on the supplier's role. */
        void assignSupplier(B b);
        /** 26.2: a soldier's grenade limit. */
        int grenadeStock();
        /** 26.2: a soldier's smoke limit. */
        int smokeStock();
        /** 26.2: hold or fire the gun. */
        void fire(B b, boolean firing);
        /** 26.2: avoid the squad's lines of fire. */
        PathSearch.Cost fireLines(Object level, B b);
        /** 26.2: the soldier's running speed. */
        double runSpeed();
        /** 26.2: keep the recipient's ammunition. */
        void keepAmmo(B b);
        /** 26.2: keep the recipient's grenades. */
        void keepGrenades(B b);
        /** 26.2: keep the recipient's smoke grenades. */
        void keepSmokes(B b);
        /** 26.2: record the soldier's thought. */
        void note(S s, B b, long tick, String text);
    }


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

    static boolean outEnabled() {
        return outOn && ReaimTimes.flag(OUT_KEY, true);
    }

    static float supplierShare() {
        return Math.max(0F, Math.min(1F, ReaimTimes.number(SHARE_KEY, SHARE)));
    }

    /**
     * A squad as squads are made (AiSquads): out of rounds when, in a fight (one of it knows of a foe), none of it alive
     * has a round besides what is in its gun and none of its suppliers has a magazine to hand out. Then each of it loses
     * heart (AiConfidence), down to OUT_FLOOR, and, kept so, holds back and falls back; a supplier that comes, or a
     * stock taken up, and they take heart again. A squad at rest with empty pouches is left as it is.
     */
    static <B extends AiBotBase, S> void checkSquad(Staff<B, S> staff, java.util.List<B> bots, long tick) {
        if (!enabled() || !outEnabled()) return;
        int alive = 0;
        boolean fight = false;
        for (B b : bots) {
            if (!AiSquadsBase.alive(b)) continue;
            alive++;
            AiAmmo ammo = b.gunner().ammo;
            if (ammo.infinite() || ammo.reserve() > 0 || b.gunner().capacity() <= 0) return;
            if (b.role() == AiRole.SUPPLIER && supplies(staff, b)[0] > 0) return;
            fight |= b.memory.focus(b.body.x(), b.body.z()) != null;
        }
        if (alive == 0 || !fight) return;
        outs.merge(AiSquadsBase.teamOf(bots.get(0)), 1, Integer::sum);
        for (B b : bots) {
            if (!AiSquadsBase.alive(b)) continue;
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
    static class Left {
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
            return PosLong.block(GameMath.floor(x), GameMath.floor(y + 0.2), GameMath.floor(z));
        }
    }

    private static final LevelMap<java.util.List<Left>> LEFT = new LevelMap<>();

    protected AiSupplyBase() {
    }

    public static void forgetLevel(Object level) {
        LEFT.remove(level);
    }

    /** an AI mob is down: a supplier's stock stays where it fell, for one of its side to take up */
    static <B extends AiBotBase, S> void leaveStock(Staff<B, S> staff, Object level, B bot, long tick) {
        if (!enabled() || bot.role() != AiRole.SUPPLIER) return;
        int[] stock = supplies(staff, bot);
        if (stock[0] + stock[1] + stock[2] <= 0) return;
        java.util.List<Left> all = LEFT.computeIfAbsent(level, l -> new java.util.ArrayList<>());
        all.removeIf(l -> tick - l.tick > LEFT_KEEP);
        if (all.size() >= 32) all.remove(0);
        AiSquadsBase.Squad<B> sq = AiSquadsBase.squadOf(bot);
        int[] squad = sq == null ? new int[0] : sq.bots.stream().filter(b -> b != bot).mapToInt(b -> b.body.id()).toArray();
        all.add(new Left(bot.body.x(), bot.body.y(), bot.body.z(), stock.clone(), AiSquadsBase.teamOf(bot), tick, squad));
        left++;
    }

    /**
     * A fallen supplier's stock within reach: one of its side goes for it and is the supplier from then on (one that
     * is a supplier already adds it to its own). One goes for a stock at a time; one of the fallen one's squad first,
     * while any of it lives; none goes with a foe in sight (the stock lies where they shot it), nor one that gave a run
     * for it up. True while it is on its way.
     */
    static <B extends AiBotBase, S> boolean fetchStock(Staff<B, S> staff, S s, B bot, Object level, long tick) {
        Left mine = staff.fetching(bot);
        // the base of fire keeps its place, a medic its kits: neither goes for one
        if (mine == null && ((tick + bot.body.id()) % HEIR_EVERY != 0 || tick < bot.supplyNext || bot.role() == AiRole.BASE || bot.role() == AiRole.MEDIC)) return false;
        java.util.List<Left> all = LEFT.get(level);
        if (mine == null) {
            if (all == null || all.isEmpty() || !supplierFree(staff, bot, tick) || foeVisible(staff, bot)) return false;
            String team = AiSquadsBase.teamOf(bot);
            int id = bot.body.id();
            for (java.util.Iterator<Left> it = all.iterator(); it.hasNext();) {
                Left l = it.next();
                if (tick - l.tick > LEFT_KEEP || l.failed.size() >= GIVE_UPS) {
                    it.remove();
                    continue;
                }
                B heir = l.heir < 0 ? null : findBot(staff, level, l.heir);
                boolean gone = heir == null || !AiSquadsBase.alive(heir) || staff.fetching(heir) != l || tick - l.heirAt > RUN_MAX;
                if (!gone || !l.team.equals(team) || l.failed.contains(id) || distance(bot.body, l.x, l.y, l.z) > REACH * REACH) continue;
                // its squad first: another goes only when none of it is left
                if (!l.ofSquad(id) && squadAlive(staff, level, l)) continue;
                l.heir = id;
                l.heirAt = tick;
                staff.fetching(bot, mine = l);
                staff.note(s, bot, tick, "supplies: goes for a fallen supplier's stock");
                break;
            }
            if (mine == null) return false;
        }
        if (all == null || !all.contains(mine) || tick - mine.heirAt > RUN_MAX || !supplierFree(staff, bot, tick) || bot.mover().failed() && bot.mover().goalIs(mine.cell())) {
            staff.fetching(bot, null);
            bot.supplyNext = tick + GIVEN_UP;
            mine.failed.add(bot.body.id());
            mine.heir = -1;
            return false;
        }
        double dx = mine.x - bot.body.x(), dz = mine.z - bot.body.z();
        if (dx * dx + dz * dz <= HAND * HAND && Math.abs(mine.y - bot.body.y()) < 2) {
            all.remove(mine);
            staff.fetching(bot, null);
            int[] stock = bot.role() == AiRole.SUPPLIER ? supplies(staff, bot) : new int[3];
            for (int i = 0; i < 3; i++) stock[i] = Math.min(64, stock[i] + mine.stock[i]);
            bot.supply = stock;
            if (bot.role() != AiRole.SUPPLIER) staff.assignSupplier(bot);
            saveStock(staff, bot);
            taken++;
            staff.note(s, bot, tick, "supplies: takes up a fallen supplier's stock, has " + stock[0] + "/" + stock[1] + "/" + stock[2]);
            return false;
        }
        staff.fire(bot, false);
        if (!bot.mover().goalIs(mine.cell())) {
            bot.mover().costs(staff.fireLines(level, bot));
            bot.mover().goTo(mine.cell(), 1.5, staff.runSpeed());
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    static <B extends AiBotBase, S> @Nullable B findBot(Staff<B, S> staff, Object level, int id) {
        Body body = staff.entities(level).byId(id);
        return body == null ? null : (B) AiBotsBase.of(body);
    }

    private static double distance(Body b, double x, double y, double z) {
        double dx = b.x() - x, dy = b.y() - y, dz = b.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** one of the fallen one's squad is alive still */
    static <B extends AiBotBase, S> boolean squadAlive(Staff<B, S> staff, Object level, Left l) {
        for (int i : l.squad) {
            B b = findBot(staff, level, i);
            if (b != null && AiSquadsBase.alive(b)) return true;
        }
        return false;
    }

    /** it sees one of them now, at any distance */
    static <B extends AiBotBase, S> boolean foeVisible(Staff<B, S> staff, B bot) {
        TargetMemory.Contact c = bot.memory.focus(bot.body.x(), bot.body.z());
        return c != null && c.state == TargetMemory.State.VISIBLE;
    }

    public static boolean enabled() {
        return on && ReaimTimes.flag(KEY, true);
    }

    static int stockKey(String key, int def) {
        return Math.max(0, Math.min(64, Math.round(ReaimTimes.number(key, def))));
    }

    /** the supplier's stock {magazines, grenades, smoke grenades}: read off the mob, or what a new one carries */
    static <B extends AiBotBase, S> int[] supplies(Staff<B, S> staff, B bot) {
        if (bot.supply == null) {
            int[] kept = staff.readStock(bot);
            bot.supply = kept != null && kept.length == 3 ? kept.clone() : new int[] {stockKey(MAGS_KEY, MAGS), stockKey(GRENADES_KEY, GRENADES), stockKey(SMOKES_KEY, SMOKES)};
            if (kept == null || kept.length != 3) saveStock(staff, bot);
        }
        return bot.supply;
    }

    static <B extends AiBotBase, S> void saveStock(Staff<B, S> staff, B bot) {
        if (bot.supply != null) staff.writeStock(bot, bot.supply.clone());
    }

    /** what this one is short of that the stock has: magazines (2), a grenade (1), a smoke grenade (0.5); 0: nothing */
    static <B extends AiBotBase, S> double supplyNeed(Staff<B, S> staff, B b, int[] stock) {
        double need = 0;
        AiAmmo ammo = b.gunner().ammo;
        // a magazine or none besides the gun's: short (AiCommander.red: its squad would fall back for it)
        if (stock[0] > 0 && !ammo.infinite() && b.gunner().capacity() > 0 && ammo.reserve() <= b.gunner().capacity()) need += 2;
        if (stock[1] > 0 && b.grenades <= 0 && staff.grenadeStock() > 0) need += 1;
        if (stock[2] > 0 && b.smokes <= 0 && staff.smokeStock() > 0) need += 0.5;
        return need;
    }

    /**
     * A supplier, this tick: true while it runs to one of its squad with supplies (it does nothing else then); false:
     * it fights as a rifleman. Called by its brain after what keeps it alive (a grenade to run from, its own throw).
     */
    static <B extends AiBotBase, S> boolean supplyStep(Staff<B, S> staff, S s, B bot, Object level, long tick) {
        if (!enabled()) return false;
        // a fallen supplier's stock first: anyone of its side, a supplier on no run
        if ((bot.role() != AiRole.SUPPLIER || staff.target(bot) == null) && fetchStock(staff, s, bot, level, tick)) return true;
        if (bot.role() != AiRole.SUPPLIER) return false;
        int[] stock = supplies(staff, bot);
        B to = staff.target(bot);
        if (to == null) {
            if ((tick + bot.body.id()) % EVERY != 0 || tick < bot.supplyNext) return false;
            // its own pouches first, where it stands
            if (supplyNeed(staff, bot, stock) > 0 && tick - bot.suppliedAt >= AGAIN) handStock(staff, s, bot, bot, stock, tick);
            if (!supplierFree(staff, bot, tick)) {
                idle[0]++;
                return false;
            }
            to = pickRecipient(staff, bot, stock, tick);
            if (to == null) return false;
            staff.target(bot, to);
            bot.supplyAt = tick;
            runs++;
            staff.note(s, bot, tick, "supplies: goes to #" + to.body.id());
        }
        // the one it goes to is down or needs nothing now, the way is too long, it is pinned down or one is on it: given up
        if (!AiSquadsBase.alive(to) || supplyNeed(staff, to, stock) <= 0 || tick - bot.supplyAt > RUN_MAX || !supplierFree(staff, bot, tick) || bot.mover().failed() && bot.mover().goalIs(botCell(staff, to))) {
            staff.target(bot, null);
            bot.supplyNext = tick + GIVEN_UP;
            gaveUp++;
            staff.note(s, bot, tick, "supplies: gives the run up");
            return false;
        }
        double dx = to.body.x() - bot.body.x(), dz = to.body.z() - bot.body.z(), dy = to.body.y() - bot.body.y();
        if (dx * dx + dz * dz <= HAND * HAND && Math.abs(dy) < 2) {
            handStock(staff, s, bot, to, stock, tick);
            staff.target(bot, null);
            return false;
        }
        staff.fire(bot, false);
        long goal = botCell(staff, to);
        if (!bot.mover().goalIs(goal)) {
            bot.mover().costs(staff.fireLines(level, bot));
            bot.mover().goTo(goal, 1.5, staff.runSpeed());
        }
        return true;
    }

    static <B extends AiBotBase, S> long botCell(Staff<B, S> staff, B b) {
        return PosLong.block(GameMath.floor(b.body.x()), GameMath.floor(b.body.y() + 0.2), GameMath.floor(b.body.z()));
    }

    /** not pinned down, not lying, not reloading, and none it sees near */
    static <B extends AiBotBase, S> boolean supplierFree(Staff<B, S> staff, B bot, long tick) {
        if (bot.prone().pinned(tick) || bot.prone().lying()) return false;
        TargetMemory.Contact c = bot.memory.focus(bot.body.x(), bot.body.z());
        return c == null || c.state != TargetMemory.State.VISIBLE || distance(bot.body, c.x, c.y, c.z) >= FOE_NEAR * FOE_NEAR;
    }

    /** the one of its squad most in need within reach, not given to lately, not out in sight of one; the nearer of two alike */
    static <B extends AiBotBase, S> @Nullable B pickRecipient(Staff<B, S> staff, B bot, int[] stock, long tick) {
        AiSquadsBase.Squad<B> squad = AiSquadsBase.squadOf(bot);
        if (squad == null) {
            idle[1]++;
            return null;
        }
        B best = null;
        double most = 0, bestD = Double.MAX_VALUE;
        int why = 2;
        for (B b : squad.bots) {
            if (b == bot || !AiSquadsBase.alive(b)) continue;
            double need = supplyNeed(staff, b, stock);
            if (need <= 0) continue;
            // another supplier of the squad is on its way to that one already
            boolean served = false;
            for (B o : squad.bots) served |= o != bot && staff.target(o) == b && AiSquadsBase.alive(o);
            if (served) continue;
            double d = AiSquadsBase.distanceSqr(b, bot);
            if (tick - b.suppliedAt < AGAIN) {
                why = Math.max(why, 5);
                continue;
            }
            if (d > REACH * REACH) {
                why = Math.max(why, 4);
                continue;
            }
            if (recipientExposed(staff, b)) {
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
    static <B extends AiBotBase, S> boolean recipientExposed(Staff<B, S> staff, B b) {
        TargetMemory.Contact c = b.memory.focus(b.body.x(), b.body.z());
        if (c == null || c.state != TargetMemory.State.VISIBLE) return false;
        return !(staff.hasSpot(b) && b.mover().arrived());
    }

    /** a portion out of the stock to this one: magazines for its gun, grenades and a smoke grenade up to what a soldier carries */
    static <B extends AiBotBase, S> void handStock(Staff<B, S> staff, S s, B bot, B to, int[] stock, long tick) {
        StringBuilder what = new StringBuilder();
        AiAmmo ammo = to.gunner().ammo;
        if (stock[0] > 0 && !ammo.infinite() && to.gunner().capacity() > 0 && ammo.reserve() <= to.gunner().capacity()) {
            int n = Math.min(PORTION, stock[0]);
            ammo.give(to.gunner().capacity() * n);
            staff.keepAmmo(to);
            stock[0] -= n;
            mags += n;
            what.append(n).append(" magazines");
        }
        if (stock[1] > 0 && to.grenades <= 0 && staff.grenadeStock() > 0) {
            int n = Math.min(staff.grenadeStock(), stock[1]);
            to.grenades += n;
            staff.keepGrenades(to);
            stock[1] -= n;
            grenades += n;
            what.append(what.length() == 0 ? "" : ", ").append(n).append(" grenades");
        }
        if (stock[2] > 0 && to.smokes <= 0 && staff.smokeStock() > 0) {
            int n = Math.min(staff.smokeStock(), stock[2]);
            to.smokes += n;
            staff.keepSmokes(to);
            stock[2] -= n;
            smokes += n;
            what.append(what.length() == 0 ? "" : ", ").append(n).append(" smoke grenades");
        }
        to.suppliedAt = tick;
        saveStock(staff, bot);
        if (what.length() > 0) staff.note(s, bot, tick, "supplies: " + what + (to == bot ? " for itself" : " to #" + to.body.id()) + "; left " + stock[0] + "/" + stock[1] + "/" + stock[2]);
    }

    /** the stock as shown over a supplier: magazines/grenades/smoke grenades */
    static <B extends AiBotBase, S> String shownStock(Staff<B, S> staff, B bot) {
        int[] st = supplies(staff, bot);
        return st[0] + "/" + st[1] + "/" + st[2];
    }
}
