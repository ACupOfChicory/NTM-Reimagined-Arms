package acup.gunfeel;

import static acup.gunfeel.host.HurtNow.PIERCE_DT;
import static acup.gunfeel.host.HurtNow.PIERCE_DR;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.BodyControl;
import acup.gunfeel.host.HurtNow;
import acup.gunfeel.host.Hurts;
import acup.gunfeel.host.Worlds;

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

    private static final Map<Kit, Map<UUID, State>> STATES = new java.util.EnumMap<>(Kit.class);
    private static final Map<Body, Long> IN_RADON = new WeakHashMap<>();

    static {
        for (Kit k : Kit.values()) STATES.put(k, new HashMap<>());
    }

    private record Decided(int source, long tick, boolean off) {
    }

    private static final Map<UUID, Decided> DECIDED = new HashMap<>();

    static final class ControlPort {
        static final BodyControl CONTROL = CoreServices.load(BodyControl.class, BodyControl.MISSING);
    }

    static boolean absorbsOf(Kit kit, Body b, Object hurt, float amount) {
        return absorbsOf(kit, b, hurt, amount, BalanceFinalBase::on);
    }

    static boolean absorbsOf(Kit kit, Body b, Object hurt, float amount, BooleanSupplier balanceOn) {
        return absorbsOf(kit, b, hurt, amount, NowPort.NOW, BalanceArmorBase.HurtPort.HURTS, Worlds::of, ControlPort.CONTROL, balanceOn);
    }

    static boolean absorbsOf(Kit kit, Body b, Object hurt, float amount, HurtNow now, Hurts hurts,
            Worlds.Port worlds, BodyControl control, BooleanSupplier balanceOn) {
        // a soldier is not called off what nothing may be (the void, /kill): a player has its own ways out, it has none
        if (!b.player() && hurts.is(hurt, Hurts.BYPASSES_INVULNERABILITY)) return false;
        long tick = worlds.world(b.level()).gameTime();
        if (radonOf(b, tick)) {
            // the set's tick takes them off too, but not before this hit
            if (kit == Kit.EUPHEMIUM) dropSetEffects(b, control);
            return false;
        }
        // the gas's damage takes the pool HP for HP, as a shot (Fable №2, 2.4)
        boolean gas = now.gas() && balanceOn.getAsBoolean();
        // DNS and FAU: shots and blasts only; euphemium calls off every hit, its pool drained by melee as before
        String cat = kit == Kit.EUPHEMIUM ? BalanceArmorBase.categoryAnyOf(hurts, hurt) : BalanceArmorBase.categoryOf(hurts, hurt);
        if (kit != Kit.EUPHEMIUM && cat == null && !gas) return false;
        State s = stateOf(kit, b, tick);
        Pool p = pool(kit);
        float drain = gas ? amount * (float) BalanceFinalRule.HP / b.maxHealth() : (cat == null ? 0F : drainOf(now, hurts, kit, b, hurt, amount, cat, now.schemeDrain(b, hurt)));
        // only a shot, an IR beam, a blast or the gas holds the window: for euphemium cat takes melee and the rest too
        boolean keeps = gas || (kit == Kit.EUPHEMIUM ? BalanceArmorBase.categoryOf(hurts, hurt) != null : cat != null);
        Phase before;
        boolean off;
        synchronized (EuphImmunityBase.class) {
            before = phase(s, tick);
            off = hit(s, tick, drain, gas || cat != null, keeps, p, keeps && !gas ? now.reach(hurt) : 0);
        }
        if (before == Phase.POOL && phase(s, tick + 1) == Phase.WINDOW) {
            if (kit == Kit.EUPHEMIUM) dropSetEffects(b, control);
            control.tellBar(b, kit.message, p.window() / 20);
        }
        return off;
    }

    static void dropSetEffects(Body b) {
        dropSetEffects(b, ControlPort.CONTROL);
    }

    static void dropSetEffects(Body b, BodyControl control) {
        int regen = b.effectAmplifier(Body.EFFECT_REGENERATION), res = b.effectAmplifier(Body.EFFECT_RESISTANCE);
        if (regen >= 100) control.removeEffect(b, Body.EFFECT_REGENERATION);
        if (res >= 100) control.removeEffect(b, Body.EFFECT_RESISTANCE);
    }

    static boolean earlyOf(Kit kit, Body b, Object hurt, float amount, BooleanSupplier balanceOn) {
        return earlyOf(kit, b, hurt, amount, NowPort.NOW, BalanceArmorBase.HurtPort.HURTS, Worlds::of, ControlPort.CONTROL, balanceOn);
    }

    static boolean earlyOf(Kit kit, Body b, Object hurt, float amount, HurtNow now, Hurts hurts,
            Worlds.Port worlds, BodyControl control, BooleanSupplier balanceOn) {
        boolean off = absorbsOf(kit, b, hurt, amount, now, hurts, worlds, control, balanceOn);
        synchronized (EuphImmunityBase.class) {
            DECIDED.put(b.uuid(), new Decided(System.identityHashCode(hurt), worlds.world(b.level()).gameTime(), off));
        }
        return off;
    }

    static boolean acceptedOf(Kit kit, Body b, Object hurt, float amount, BooleanSupplier balanceOn) {
        return acceptedOf(kit, b, hurt, amount, NowPort.NOW, BalanceArmorBase.HurtPort.HURTS, Worlds::of, ControlPort.CONTROL, balanceOn);
    }

    static boolean acceptedOf(Kit kit, Body b, Object hurt, float amount, HurtNow now, Hurts hurts,
            Worlds.Port worlds, BodyControl control, BooleanSupplier balanceOn) {
        Decided d;
        synchronized (EuphImmunityBase.class) {
            d = DECIDED.get(b.uuid());
        }
        if (d != null && d.source() == System.identityHashCode(hurt) && d.tick() == worlds.world(b.level()).gameTime()) return d.off();
        return absorbsOf(kit, b, hurt, amount, now, hurts, worlds, control, balanceOn);
    }

    /** the kit each player's pool was last of (balance.immune.shared) */
    private static final Map<UUID, Kit> LAST_KIT = new HashMap<>();

    /**
     * balance.immune.shared (true; false: X884): one pool a player for the three kits. The kit put on takes where the
     * last one was (carry), not a fresh pool of its own: DNS emptied, then FAU on, was a full pool of 45 at once.
     */
    static boolean shared() {
        return ReaimTimes.flag("balance.immune.shared", true);
    }

    static synchronized State stateOf(Kit kit, Body b, long now) {
        return stateOf(kit, b, now, EuphImmunityBase::shared);
    }

    /** the same with the shared key's read given (core's tests) */
    static synchronized State stateOf(Kit kit, Body b, long now, BooleanSupplier shared) {
        UUID id = b.uuid();
        Map<UUID, State> own = STATES.get(kit);
        if (shared.getAsBoolean()) {
            Kit was = LAST_KIT.put(id, kit);
            State from = was == null || was == kit ? null : STATES.get(was).get(id);
            if (from != null) own.put(id, carry(from, pool(was), pool(kit), now));
        } else {
            // off: no kit remembered, so turning it back on carries nothing stale
            LAST_KIT.remove(id);
        }
        return own.computeIfAbsent(id, u -> new State(pool(kit).size(), now));
    }

    /** the tomb's radon gas touched the entity this tick */
    static synchronized void inRadonOf(Body b, long now) {
        IN_RADON.put(b, now);
    }

    static synchronized boolean radonOf(Body b, long now) {
        Long t = IN_RADON.get(b);
        return t != null && now - t <= 1;
    }

    /** the set's Regeneration and Resistance must not be on the wearer now: the window, or the radon gas */
    static boolean vulnerableOf(Body b) {
        return vulnerableOf(b, Worlds::of);
    }

    static boolean vulnerableOf(Body b, Worlds.Port worlds) {
        long now = worlds.world(b.level()).gameTime();
        if (radonOf(b, now)) return true;
        State s;
        synchronized (EuphImmunityBase.class) {
            s = STATES.get(Kit.EUPHEMIUM).get(b.uuid());
        }
        return s != null && phase(s, now) == Phase.WINDOW;
    }

    /** the stand: the wearer's pool now and the phase */
    static synchronized String describeOf(Kit kit, Body b) {
        return describeOf(kit, b, Worlds::of);
    }

    static synchronized String describeOf(Kit kit, Body b, Worlds.Port worlds) {
        State s = STATES.get(kit).get(b.uuid());
        if (s == null) return "нет";
        long now = worlds.world(b.level()).gameTime();
        return phase(s, now) + " " + Math.round(poolAt(s, now, pool(kit)));
    }

    /** the stand: the pool now (NaN: no state, or not in the pool's phase) */
    static synchronized float poolOf(Kit kit, Body b) {
        return poolOf(kit, b, Worlds::of);
    }

    static synchronized float poolOf(Kit kit, Body b, Worlds.Port worlds) {
        State s = STATES.get(kit).get(b.uuid());
        if (s == null) return Float.NaN;
        long now = worlds.world(b.level()).gameTime();
        return phase(s, now) == Phase.POOL ? poolAt(s, now, pool(kit)) : Float.NaN;
    }

    static synchronized void forgetOf(Body b) {
        for (Map<UUID, State> m : STATES.values()) m.remove(b.uuid());
        DECIDED.remove(b.uuid());
        LAST_KIT.remove(b.uuid());
    }

    static synchronized void clearAll() {
        for (Map<UUID, State> m : STATES.values()) m.clear();
        IN_RADON.clear();
        DECIDED.clear();
        LAST_KIT.clear();
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

    /** the version's current hit, asked the first time one is */
    static final class NowPort {
        static final HurtNow NOW = CoreServices.load(HurtNow.class, HurtNow.MISSING);
    }

    /**
     * what a hit of category {@code cat} (not null) takes from the pool of {@code kit} worn by {@code b}: EuphImmunity.drain
     * after the final scheme's own drain {@code scheme} (NaN: not the scheme's), which the version works out first. The
     * hurt under way read by HurtNow (the head, the shot's floor, NTM's piercing), each where and as often as the old
     * code read it
     */
    static float drainOf(Kit kit, Body b, Object hurt, float amount, String cat, float scheme) {
        return drainOf(NowPort.NOW, BalanceArmorBase.HurtPort.HURTS, kit, b, hurt, amount, cat, scheme);
    }

    static float drainOf(HurtNow now, Hurts hurts, Kit kit, Body b, Object hurt, float amount, String cat, float scheme) {
        return drainOf(now, hurts, kit, b, hurt, amount, cat, scheme, null);
    }

    /** the same with the reads given (core's tests); {@code powered} null: the game's (poweredChest) */
    static float drainOf(HurtNow now, Hurts hurts, Kit kit, Body b, Object hurt, float amount, String cat, float scheme, PoweredOf powered) {
        if (!Float.isNaN(scheme)) {
            float click = ReaimTimes.number("damageCapClick", 0.6F);
            return Float.isFinite(click) && click > 0F && !now.onHead(b) ? Math.min(scheme, click * b.maxHealth()) : scheme;
        }
        float[] st = drainSet(kit);
        // NTM's accepted damage takes an electric hit on a powered chestplate five times (EntityDamageUtil)
        if (kit != Kit.EUPHEMIUM && hurts.is(hurt, Hurts.NTM_ELECTRIC) && (powered == null ? poweredChest(b) : powered.of(b.worn(Body.CHEST))))
            amount *= BalanceArmorBase.electricPowered(5F);
        float s = BalanceArmorBase.CAT_ENERGY.equals(cat) ? st[0] * Math.min(1F, ReaimTimes.number("balanceEnShare", 0.75F)) : st[0];
        boolean squeeze = !BalanceArmorBase.CAT_EXPLOSION.equals(cat) || amount <= ReaimTimes.number("balanceExplRawLimit", 60F) || BalanceArmorBase.bulletBlastNow();
        // a weapon's blast as BalanceArmor takes it (balanceBlastPower, set around the blast's hurt)
        float power = BalanceArmorBase.CAT_EXPLOSION.equals(cat) && squeeze ? BalanceArmorBase.BLAST_POWER.get() : 1F;
        // by the kit's tier (8, 9): the power itself, a rocket's direct hit too
        int tier = kit == Kit.EUPHEMIUM ? 9 : 8;
        if (power > 1F) power = BalanceArmorBase.pieceBlastPower(tier, amount, power);
        // the gun table's floor of the shot's pair, as BalanceArmor takes it
        boolean expl = BalanceArmorBase.CAT_EXPLOSION.equals(cat);
        float floor = !expl || BalanceArmorBase.bulletBlastNow() ? BalanceGunsBase.floorOf(now.floorExtra(hurt, expl), tier) : 1F;
        float drain = Math.min(amount, BalanceArmorRule.full(new BalanceArmorRule.Hit(amount * power, now.ntmPierce(b, PIERCE_DT), now.ntmPierce(b, PIERCE_DR), squeeze), s, st[1],
                BalanceArmorRule.Tune.DEFAULT, floor));
        float click = ReaimTimes.number("damageCapClick", 0.6F);
        if (Float.isFinite(click) && click > 0F) drain = Math.min(drain, click * b.maxHealth());
        return drain;
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

    // ---------------------------------------------------------------- the suits worn (ArmorSuits)

    /** the game's suits, asked the first time one is */
    static final class SuitsPort {
        static final acup.gunfeel.host.ArmorSuits SUITS = CoreServices.load(acup.gunfeel.host.ArmorSuits.class, acup.gunfeel.host.ArmorSuits.MISSING);
        static final PoweredOf POWERED = new PoweredOf(SUITS);
    }

    /** the kit's pool is on: the balance's feature ({@code balanceOn}, asked first) and the kit in the mode pool */
    static boolean onOf(Kit kit, java.util.function.BooleanSupplier balanceOn) {
        return balanceOn.getAsBoolean() && kit.mode().equals("pool");
    }

    /** DNS's own immunity is off (26.2: dnsFormula), with the balance asked first. */
    static boolean dnsFormulaOf(java.util.function.BooleanSupplier balanceOn) {
        return balanceOn.getAsBoolean() && !Kit.DNS.mode().equals("ntm");
    }

    /**
     * the full set of DNS or FAU (charged, as NTM counts it) with its pool on, or null; a soldier's (not a player's)
     * euphemium too. {@code balanceOn}: the balance's feature, asked first and again by the kit found (onOf)
     */
    static Kit kitOfWorn(acup.gunfeel.host.Body b, java.util.function.BooleanSupplier balanceOn) {
        return kitOfWorn(b, balanceOn, SuitsPort.SUITS);
    }

    static Kit kitOfWorn(acup.gunfeel.host.Body b, java.util.function.BooleanSupplier balanceOn, acup.gunfeel.host.ArmorSuits a) {
        if (!balanceOn.getAsBoolean()) return null;
        if (a.fullSet(b, acup.gunfeel.host.ArmorSuits.DNS)) return onOf(Kit.DNS, balanceOn) ? Kit.DNS : null;
        if (a.fullSet(b, acup.gunfeel.host.ArmorSuits.DIGAMMA)) return onOf(Kit.FAU, balanceOn) ? Kit.FAU : null;
        if (!b.player() && a.fullSet(b, acup.gunfeel.host.ArmorSuits.EUPHEMIUM)) return onOf(Kit.EUPHEMIUM, balanceOn) ? Kit.EUPHEMIUM : null;
        return null;
    }

    /** {@code b}'s chestplate is a powered NTM armour piece (NTM's accepted damage takes an electric hit on it five times) */
    static boolean poweredChest(acup.gunfeel.host.Body b) {
        return SuitsPort.POWERED.of(b.worn(acup.gunfeel.host.Body.CHEST));
    }

    /**
     * ArmorSuits.powered by the item's number, asked once an item: the answer hangs on the item's kind alone, the same
     * for its number while the game runs. A race is harmless: a cell 0 is "not asked yet" (asked again), 1 no, 2 yes (a
     * byte written whole, never torn); a row grown is a copy put in place under the lock, a reader of the new row before
     * its copy is done sees 0 and asks again, never a wrong answer.
     */
    static final class PoweredOf {
        private final acup.gunfeel.host.ArmorSuits suits;
        private byte[] row = new byte[0];

        PoweredOf(acup.gunfeel.host.ArmorSuits suits) {
            this.suits = suits;
        }

        boolean of(int item) {
            byte[] r = row;
            byte got = item >= 0 && item < r.length ? r[item] : 0;
            if (got == 0) {
                got = suits.powered(item) ? (byte) 2 : (byte) 1;
                remember(item, got);
            }
            return got == 2;
        }

        private synchronized void remember(int item, byte got) {
            byte[] r = row;
            if (item >= r.length) row = r = java.util.Arrays.copyOf(r, Math.max(item + 1, r.length * 2 + 16));
            r[item] = got;
        }
    }
}
