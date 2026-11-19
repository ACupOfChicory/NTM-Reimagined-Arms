package acup.gunfeel;

/**
 * What EuphImmunity knows without the game: the pool of euphemium, DNS and FAU (its numbers by the keys, a wearer's
 * state, the phase, the pool at a tick, a hit's drain and the window it opens or holds, the state carried to another
 * kit) and the three kits with their keys and the set a hit drains their pool by.
 * EuphImmunity extends it on each version and takes the hit off the game (the damage source, the set worn, the
 * player's state by id): its callers name these members through it, EuphImmunity.Kit, EuphImmunity.hit and so on.
 */
public class EuphImmunityBase {
    EuphImmunityBase() {
    }

    /**
     * the pool's numbers; {@code hold}: ticks a weapon's hit in the window keeps it open for (0: the window is its own
     * length, X873)
     */
    public record Pool(float size, int window, int recover, int hold) {
        public static final Pool DEFAULT = new Pool(150F, 300, 1200, 200);
    }

    /** ticks after the last hit that took from the pool before it comes back (balance.immune.refillPause's default) */
    public static final int REFILL_PAUSE = 100;

    /**
     * balance.immune.refillPause (5 s, as X875): seconds after the last hit that took from the pool before it comes back,
     * for the three sets. Henry and Lincoln, whose reload is longer, wait it out by their reload's hold (holdOf) instead
     */
    static int refillPause() {
        float v = ReaimTimes.number("balance.immune.refillPause", REFILL_PAUSE / 20F);
        return Float.isFinite(v) && v >= 0F ? Math.round(v * 20F) : REFILL_PAUSE;
    }
    /** the DNS-class set the pool counts by */
    public static final float POOL_S = 186F, POOL_TAU = 7.5F;

    /** where one wearer is: the pool left (as at {@code at}), the window's end, the recovery's end */
    public static final class State {
        public float pool;
        public long at, lastHit = Long.MIN_VALUE / 2, windowStart = Long.MIN_VALUE / 2, windowEnd = Long.MIN_VALUE / 2, recoverEnd = Long.MIN_VALUE / 2;
        /** the last hit's own refill pause, ticks (0: balance.immune.refillPause's) */
        public int pause;

        public State(float pool, long now) {
            this.pool = pool;
            this.at = now;
        }
    }

    public enum Phase {
        POOL, WINDOW, RECOVER
    }

    public static Phase phase(State s, long now) {
        // the tick of the hit that emptied the pool is still the pool's: its other pellets are called off too
        if (now >= s.windowStart && now < s.windowEnd) return Phase.WINDOW;
        if (now >= s.windowEnd && now < s.recoverEnd) return Phase.RECOVER;
        return Phase.POOL;
    }

    /** the pool as it is at {@code now} (filling during the recovery, coming back after a pause) */
    public static float poolAt(State s, long now, Pool p) {
        switch (phase(s, now)) {
            case WINDOW:
                return 0F;
            case RECOVER:
                return p.recover() <= 0 ? p.size() : p.size() * (now - s.windowEnd) / p.recover();
            default:
                break;
        }
        float pool = s.pool;
        // a recovery that ended since: full
        if (s.recoverEnd > s.at && now >= s.recoverEnd) {
            pool = p.size();
            return pool;
        }
        long from = Math.max(s.at, s.lastHit + Math.max(refillPause(), s.pause));
        if (now > from && p.recover() > 0) pool = Math.min(p.size(), pool + p.size() * (now - from) / p.recover());
        else if (p.recover() <= 0) pool = p.size();
        return Math.min(p.size(), pool);
    }

    /** ticks a gun's hit holds the window and the refill pause past the hold */
    public static final int RELOAD_SLACK = 20;

    /**
     * the hold of a hit whose gun takes {@code reach} ticks from a shot to the next one after a reload from empty (0:
     * not a gun's): the pool's hold, or the reach and {@link #RELOAD_SLACK} when that is longer (0 when the hold is 0)
     */
    public static int holdOf(Pool p, int reach) {
        if (p.hold() <= 0) return 0;
        return reach > p.hold() ? reach + RELOAD_SLACK : p.hold();
    }

    /**
     * A hit of {@code drain} (what it would do to the DNS-class set) at {@code now}; true: it is called off. An emptied
     * pool opens the window from the next tick, the hit itself is still called off. {@code keeps}: a shot, beam or
     * blast, which holds the window open (euphemium's melee drains, not holds); {@code reach}: the gun's shot and reload
     * from empty, ticks ({@link #holdOf})
     */
    public static boolean hit(State s, long now, float drain, boolean weapon, boolean keeps, Pool p, int reach) {
        Phase ph = phase(s, now);
        int hold = keeps ? holdOf(p, reach) : 0;
        if (ph == Phase.WINDOW) {
            // a weapon's hit keeps the window open (balance.immune.hold): a gun too weak to kill in 15 s does not pay
            // a whole round of invulnerability and a new pool for it, as long as it keeps hitting
            if (hold > 0 && now + hold > s.windowEnd) {
                s.windowEnd = now + hold;
                s.recoverEnd = s.windowEnd + p.recover();
            }
            return false;
        }
        if (ph == Phase.RECOVER || !weapon) return true;
        float pool = poolAt(s, now, p) - Math.max(0F, drain);
        s.at = now;
        s.lastHit = now;
        // a gun the hold does not wait out: its reload does not give the pool back either
        s.pause = hold > p.hold() ? hold : 0;
        if (pool <= 0F) {
            s.pool = 0F;
            s.windowStart = now + 1;
            s.windowEnd = s.windowStart + p.window();
            s.recoverEnd = s.windowEnd + p.recover();
        } else {
            s.pool = pool;
        }
        return true;
    }

    /**
     * the state of another kit's pool {@code from} (its numbers {@code a}) as kit {@code b}'s at {@code now}: the same
     * share of the full pool, the same window and recovery
     */
    public static State carry(State from, Pool a, Pool b, long now) {
        float share = a.size() > 0F ? poolAt(from, now, a) / a.size() : 1F;
        State s = new State(Math.max(0F, Math.min(1F, share)) * b.size(), now);
        s.lastHit = from.lastHit;
        s.pause = from.pause;
        s.windowStart = from.windowStart;
        s.windowEnd = from.windowEnd;
        s.recoverEnd = from.recoverEnd;
        return s;
    }

    public static Pool pool(Kit kit) {
        Pool d = new Pool(BalanceFinalBase.poolOf(kit), Pool.DEFAULT.window(), Pool.DEFAULT.recover(), Pool.DEFAULT.hold());
        float size = ReaimTimes.number(kit.key + ".pool", d.size());
        float window = ReaimTimes.number(kit.key + ".window", d.window() / 20F);
        float recover = ReaimTimes.number(kit.key + ".recover", d.recover() / 20F);
        // one key for the three sets
        float hold = ReaimTimes.number("balance.immune.hold", d.hold() / 20F);
        return new Pool(Float.isFinite(size) && size > 0F ? size : d.size(), Float.isFinite(window) && window >= 0F ? Math.round(window * 20F) : d.window(),
                Float.isFinite(recover) && recover >= 0F ? Math.round(recover * 20F) : d.recover(),
                Float.isFinite(hold) && hold >= 0F ? Math.round(hold * 20F) : d.hold());
    }

    /** the rating and tier a hit on the kit takes from its pool by: euphemium a DNS-class set, DNS and FAU their own */
    static float[] drainSet(Kit kit) {
        if (kit == Kit.EUPHEMIUM) return new float[] {POOL_S, POOL_TAU};
        BalanceArmorBase.SetOf def = BalanceArmorBase.SETS.get(kit.set);
        float s = BalanceArmorBase.rating(kit.set);
        return new float[] {s > 0F ? s : def.s(), BalanceArmorBase.tau(kit.set)};
    }

    /** a set with the pool: its keys' prefix, its table name (the drain's rating), its pool by default, its message */
    public enum Kit {
        EUPHEMIUM("balance.immune", "euphemium", 150F, "ntmgunfeel.euph.window"),
        DNS("balance.immune.dns", "dns", 45F, "ntmgunfeel.dns.window"),
        FAU("balance.immune.fau", "fau", 40F, "ntmgunfeel.fau.window");

        public final String key, set, message;
        public final float size;

        Kit(String key, String set, float size, String message) {
            this.key = key;
            this.set = set;
            this.size = size;
            this.message = message;
        }

        /** the mode: euphemium pool | ntm; DNS pool | formula (X858: its rating only) | ntm; FAU pool | formula */
        public String mode() {
            if (this == EUPHEMIUM) return ReaimTimes.text("balance.immune.mode", "pool").trim().toLowerCase(java.util.Locale.ROOT);
            String m = ReaimTimes.text(key + ".mode", "pool").trim().toLowerCase(java.util.Locale.ROOT);
            if (this == FAU && !m.equals("pool")) return "formula";
            return m.equals("ntm") || m.equals("formula") ? m : "pool";
        }
    }
}
