package acup.gunfeel;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import com.hbm.interfaces.NtmDamageContext;
import com.hbm.util.DamageResistanceHandler;

import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Euphemium's invulnerability with a pool (stage 2, balance-by-progression.md 21.7 E3; server settings
 * balance.immune.*): a hard kill, not an impossible one.
 *
 * <ul>
 * <li>{@code balance.immune.mode}: {@code pool} (the balance's) or {@code ntm} (every hit on a full euphemium set is
 * called off, as NTM does);
 * <li>{@code balance.immune.pool} (150; X865: 2000): bullets, beams and blasts (PHYS, EN, EXPL) on the full set are still called
 * off, but each takes from the pool what it would have done to a DNS-class set (formula C at S 186, tier 7.5; one hit
 * at most damageCapClick of the wearer's health). The pool comes back over {@code balance.immune.recover} once no
 * hit took from it for 5 s. Other damage (fire, falling, gas, radiation, nuclear blasts) is called off as NTM does and
 * takes nothing;
 * <li>{@code balance.immune.window} (15 s): the emptied pool opens a window: every hit goes through, to the set's
 * rating (balanceS.euphemium, 280) and the damage cap, and the set's Regeneration and Resistance (NTM: 128) are gone
 * for it, or the window would give nothing;
 * <li>{@code balance.immune.hold} (10 s; 0: X873): a weapon's hit in the window keeps it open at least this long
 * after it, for the three sets (DNS and FAU too): a gun that does not kill in 15 s goes on as long as it keeps hitting,
 * instead of a whole round of invulnerability and a new pool (the TTK had no pair between 60 and 120 s);
 * <li>{@code balance.immune.recover} (60 s): after the window the pool fills back from 0 over this time, the set
 * invulnerable meanwhile; then it is full and the round starts again.
 * <li>the tomb's radon gas (BlockGasRadonTomb) takes the invulnerability off entirely while the wearer is in it, with
 * the Regeneration and Resistance: its radiation then counts (NTM: 0.5 RAD a tick inside, 1000 RAD hurt for 1000).
 * </ul>
 * The state is kept by the player's id for the server's run (a relog does not refill the pool).
 *
 * <p>DNS and FAU (the 240-hour sets) get the same round with their own numbers ({@link Kit}; keys
 * balance.immune.dns.* and balance.immune.fau.*: mode, pool, window, recover): the pool takes what the hit would do to the
 * set itself (its rating, balanceS.dns 186 and balanceS.fau 168), the window lets hits through to that rating. They
 * have no Regeneration or Resistance of their own to drop. Other damage (fire, falling, gas, radiation, nuclear) is not
 * touched by their pool: NTM's own numbers of the set, as without it. The tomb's radon takes their pool off too.
 */
public final class EuphImmunity {
    private EuphImmunity() {
    }

    /**
     * the pool's numbers; {@code hold}: ticks a weapon's hit in the window keeps it open for (0: the window is its own
     * length, X873)
     */
    public record Pool(float size, int window, int recover, int hold) {
        public static final Pool DEFAULT = new Pool(150F, 300, 1200, 200);

        public Pool(float size, int window, int recover) {
            this(size, window, recover, 0);
        }
    }

    /** ticks after the last hit that took from the pool before it comes back */
    public static final int REFILL_PAUSE = 100;
    /** the DNS-class set the pool counts by */
    public static final float POOL_S = 186F, POOL_TAU = 7.5F;

    /** where one wearer is: the pool left (as at {@code at}), the window's end, the recovery's end */
    public static final class State {
        public float pool;
        public long at, lastHit = Long.MIN_VALUE / 2, windowStart = Long.MIN_VALUE / 2, windowEnd = Long.MIN_VALUE / 2, recoverEnd = Long.MIN_VALUE / 2;

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
        long from = Math.max(s.at, s.lastHit + REFILL_PAUSE);
        if (now > from && p.recover() > 0) pool = Math.min(p.size(), pool + p.size() * (now - from) / p.recover());
        else if (p.recover() <= 0) pool = p.size();
        return Math.min(p.size(), pool);
    }

    /**
     * A hit of {@code drain} (what it would do to the DNS-class set) at {@code now}; true: it is called off. An emptied
     * pool opens the window from the next tick, the hit itself is still called off.
     */
    public static boolean hit(State s, long now, float drain, boolean weapon, Pool p) {
        return hit(s, now, drain, weapon, weapon, p);
    }

    /** the same; {@code keeps}: a shot, beam or blast, which holds the window open (euphemium's melee drains, not holds) */
    public static boolean hit(State s, long now, float drain, boolean weapon, boolean keeps, Pool p) {
        Phase ph = phase(s, now);
        if (ph == Phase.WINDOW) {
            // a weapon's hit keeps the window open (balance.immune.hold): a gun too weak to kill in 15 s does not pay
            // a whole round of invulnerability and a new pool for it, as long as it keeps hitting
            if (keeps && p.hold() > 0 && now + p.hold() > s.windowEnd) {
                s.windowEnd = now + p.hold();
                s.recoverEnd = s.windowEnd + p.recover();
            }
            return false;
        }
        if (ph == Phase.RECOVER || !weapon) return true;
        float pool = poolAt(s, now, p) - Math.max(0F, drain);
        s.at = now;
        s.lastHit = now;
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

    // ---------------------------------------------------------------- the game's side

    /** a set with the pool: its keys' prefix, its table name (the drain's rating), its pool by default, its message */
    public enum Kit {
        EUPHEMIUM("balance.immune", "euphemium", 150F, "ntmgunfeel.euph.window"),
        DNS("balance.immune.dns", "dns", 40F, "ntmgunfeel.dns.window"),
        FAU("balance.immune.fau", "fau", 45F, "ntmgunfeel.fau.window");

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
            // the old switch of DNS (X858: balance.immune.dns = formula | ntm): its ntm counts while the new key is not set
            if (this == DNS && !ReaimTimes.inFile(key + ".mode") && ReaimTimes.inFile(key) && ReaimTimes.text(key, "").trim().equalsIgnoreCase("ntm")) return "ntm";
            if (this == FAU && !m.equals("pool")) return "formula";
            return m.equals("ntm") || m.equals("formula") ? m : "pool";
        }
    }

    private static final Map<Kit, Map<UUID, State>> STATES = new java.util.EnumMap<>(Kit.class);
    private static final Map<Entity, Long> IN_RADON = new WeakHashMap<>();

    static {
        for (Kit k : Kit.values()) STATES.put(k, new HashMap<>());
    }

    public static boolean on() {
        return on(Kit.EUPHEMIUM);
    }

    public static boolean on(Kit kit) {
        return acup.gunfeel.compat.Feature.BALANCE.on() && kit.mode().equals("pool");
    }

    /** NTM's own DNS checks (every hit but a blast called off, blasts down to 0.001) are off: modes formula and pool */
    public static boolean dnsFormula() {
        return acup.gunfeel.compat.Feature.BALANCE.on() && !Kit.DNS.mode().equals("ntm");
    }

    public static Pool pool() {
        return pool(Kit.EUPHEMIUM);
    }

    public static Pool pool(Kit kit) {
        Pool d = new Pool(kit.size, Pool.DEFAULT.window(), Pool.DEFAULT.recover(), Pool.DEFAULT.hold());
        float size = ReaimTimes.number(kit.key + ".pool", d.size());
        float window = ReaimTimes.number(kit.key + ".window", d.window() / 20F);
        float recover = ReaimTimes.number(kit.key + ".recover", d.recover() / 20F);
        // one key for the three sets
        float hold = ReaimTimes.number("balance.immune.hold", d.hold() / 20F);
        return new Pool(Float.isFinite(size) && size > 0F ? size : d.size(), Float.isFinite(window) && window >= 0F ? Math.round(window * 20F) : d.window(),
                Float.isFinite(recover) && recover >= 0F ? Math.round(recover * 20F) : d.recover(),
                Float.isFinite(hold) && hold >= 0F ? Math.round(hold * 20F) : d.hold());
    }

    static synchronized State state(Kit kit, Player player, long now) {
        return STATES.get(kit).computeIfAbsent(player.getUUID(), u -> new State(pool(kit).size(), now));
    }

    /** the tomb's radon gas touched the entity this tick */
    public static synchronized void inRadon(Entity entity, long now) {
        IN_RADON.put(entity, now);
    }

    static synchronized boolean radon(Entity entity, long now) {
        Long t = IN_RADON.get(entity);
        return t != null && now - t <= 1;
    }

    /** the rating and tier a hit on the kit takes from its pool by: euphemium a DNS-class set, DNS and FAU their own */
    static float[] drainSet(Kit kit) {
        if (kit == Kit.EUPHEMIUM) return new float[] {POOL_S, POOL_TAU};
        BalanceArmor.SetOf def = BalanceArmor.SETS.get(kit.set);
        float s = BalanceArmor.rating(kit.set);
        return new float[] {s > 0F ? s : def.s(), BalanceArmor.tau(kit.set)};
    }

    /** what the hit would do to the kit's drain set (formula C; one hit at most damageCapClick of the health), or 0 */
    static float drain(Kit kit, Player player, DamageSource source, float amount, String cat) {
        if (cat == null) return 0F;
        float[] st = drainSet(kit);
        // NTM's accepted damage takes an electric hit on a powered chestplate five times (EntityDamageUtil)
        if (kit != Kit.EUPHEMIUM && source.is(com.hbm.lib.ModDamageTypes.ELECTRIC)
                && player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).getItem() instanceof com.hbm.items.armor.ModArmorItem armor && armor.maxPower() > 0L)
            amount *= BalanceArmor.electricPowered(5F);
        NtmDamageContext ctx = (NtmDamageContext) player;
        float s = DamageResistanceHandler.CATEGORY_ENERGY.equals(cat) ? st[0] * Math.min(1F, ReaimTimes.number("balanceEnShare", 0.75F)) : st[0];
        boolean squeeze = !DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat) || amount <= ReaimTimes.number("balanceExplRawLimit", 60F) || BalanceArmor.bulletBlastNow();
        // a weapon's blast as BalanceArmor takes it (balanceBlastPower, set around the blast's hurt)
        float power = DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat) && squeeze ? BalanceArmor.BLAST_POWER.get() : 1F;
        // by the kit's tier (8, 9): the power itself, a rocket's direct hit too
        int tier = kit == Kit.EUPHEMIUM ? 9 : 8;
        if (power > 1F) power = BalanceArmor.pieceBlastPower(tier, amount, power);
        // the gun table's floor of the shot's pair (balanceArmorFloor), as BalanceArmor takes it
        boolean expl = DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat);
        float floor = !expl || BalanceArmor.bulletBlastNow() ? BalanceGuns.floorOf(BalanceArmor.floorShot(source, expl), tier) : 1F;
        float drain = Math.min(amount, BalanceArmorRule.full(new BalanceArmorRule.Hit(amount * power, ctx.hbm$pierceDT(), ctx.hbm$pierceDR(), squeeze), s, st[1],
                BalanceArmorRule.Tune.DEFAULT, floor));
        float click = ReaimTimes.number("damageCapClick", 0.6F);
        if (Float.isFinite(click) && click > 0F) drain = Math.min(drain, click * player.getMaxHealth());
        return drain;
    }

    /**
     * NTM's euphemium check found the full set on {@code player} for this hit: true calls it off (NTM's way), false
     * lets it through.
     */
    public static boolean absorbs(Player player, DamageSource source, float amount) {
        if (!on()) return true;
        return absorbs(Kit.EUPHEMIUM, player, source, amount);
    }

    /**
     * A hit on {@code player} in the full set of {@code kit} with its pool on: true calls it off. Euphemium calls off
     * every hit in the pool and the recovery; DNS and FAU only bullets, beams and blasts (the rest is NTM's).
     */
    public static boolean absorbs(Kit kit, Player player, DamageSource source, float amount) {
        long now = player.level().getGameTime();
        if (radon(player, now)) {
            // the set's tick takes them off too, but not before this hit
            if (kit == Kit.EUPHEMIUM) dropEffects(player);
            return false;
        }
        // DNS and FAU: shots and blasts only; euphemium calls off every hit, its pool drained by melee as before
        String cat = kit == Kit.EUPHEMIUM ? BalanceArmor.categoryAny(source) : BalanceArmor.category(source);
        if (kit != Kit.EUPHEMIUM && cat == null) return false;
        State s = state(kit, player, now);
        Pool p = pool(kit);
        float drain = drain(kit, player, source, amount, cat);
        // only a shot, an IR beam or a blast holds the window: for euphemium cat takes melee and the rest too
        boolean keeps = kit == Kit.EUPHEMIUM ? BalanceArmor.category(source) != null : cat != null;
        Phase before;
        boolean off;
        synchronized (EuphImmunity.class) {
            before = phase(s, now);
            off = hit(s, now, drain, cat != null, keeps, p);
        }
        if (before == Phase.POOL && phase(s, now + 1) == Phase.WINDOW) {
            if (kit == Kit.EUPHEMIUM) dropEffects(player);
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) sp.sendSystemMessage(Component.translatable(kit.message, p.window() / 20), true);
        }
        return off;
    }

    /** the full set of DNS or FAU (charged, as NTM counts it) with its pool on, or null */
    public static Kit kitOf(Player player) {
        if (!acup.gunfeel.compat.Feature.BALANCE.on()) return null;
        if (com.hbm.items.armor.ArmorSuitEffects.hasFullSet(player, com.hbm.items.armor.ModArmorItem.Suit.DNS)) return on(Kit.DNS) ? Kit.DNS : null;
        if (com.hbm.items.armor.ArmorSuitEffects.hasFullSet(player, com.hbm.items.armor.ModArmorItem.Suit.DIGAMMA)) return on(Kit.FAU) ? Kit.FAU : null;
        return null;
    }

    /** the last decision on a hit of DNS or FAU: the hit's source (by identity, not held), its tick, called off */
    private record Decided(int source, long tick, boolean off) {
    }

    // by the player's id, for the same hit's actuallyHurt: one drain a hit (the source not held: it holds its attacker)
    private static final Map<UUID, Decided> DECIDED = new HashMap<>();

    /** NTM's early check (allowsAttack) of a hit on DNS or FAU: true calls it off */
    public static boolean early(Kit kit, Player player, DamageSource source, float amount) {
        boolean off = absorbs(kit, player, source, amount);
        synchronized (EuphImmunity.class) {
            DECIDED.put(player.getUUID(), new Decided(System.identityHashCode(source), player.level().getGameTime(), off));
        }
        return off;
    }

    /**
     * The hit reaches NTM's accepted damage (beams and blasts come with ignoreEarlyCancellation: the early check's
     * calling off does not stop them): true takes it to 0. The early check's decision on the same hit, or a new one.
     */
    public static boolean accepted(Kit kit, Player player, DamageSource source, float amount) {
        Decided d;
        synchronized (EuphImmunity.class) {
            d = DECIDED.get(player.getUUID());
        }
        if (d != null && d.source() == System.identityHashCode(source) && d.tick() == player.level().getGameTime()) return d.off();
        return absorbs(kit, player, source, amount);
    }

    /** the set's Regeneration and Resistance must not be on the wearer now: the window, or the radon gas */
    public static boolean vulnerable(Player player) {
        if (!on()) return false;
        long now = player.level().getGameTime();
        if (radon(player, now)) return true;
        State s;
        synchronized (EuphImmunity.class) {
            s = STATES.get(Kit.EUPHEMIUM).get(player.getUUID());
        }
        return s != null && phase(s, now) == Phase.WINDOW;
    }

    /** the set's own effects (NTM: amplifier 127) */
    public static void dropEffects(Player player) {
        MobEffectInstance regen = player.getEffect(MobEffects.REGENERATION), res = player.getEffect(MobEffects.RESISTANCE);
        if (regen != null && regen.getAmplifier() >= 100) player.removeEffect(MobEffects.REGENERATION);
        if (res != null && res.getAmplifier() >= 100) player.removeEffect(MobEffects.RESISTANCE);
    }

    /** the stand: the wearer's pool now and the phase */
    static String describe(Player player) {
        return describe(Kit.EUPHEMIUM, player);
    }

    static synchronized String describe(Kit kit, Player player) {
        State s = STATES.get(kit).get(player.getUUID());
        if (s == null) return "нет";
        long now = player.level().getGameTime();
        return phase(s, now) + " " + Math.round(poolAt(s, now, pool(kit)));
    }

    /** the stand: the pool now (NaN: no state, or not in the pool's phase) */
    static synchronized float poolOf(Kit kit, Player player) {
        State s = STATES.get(kit).get(player.getUUID());
        if (s == null) return Float.NaN;
        long now = player.level().getGameTime();
        return phase(s, now) == Phase.POOL ? poolAt(s, now, pool(kit)) : Float.NaN;
    }

    static synchronized void forget(Player player) {
        for (Map<UUID, State> m : STATES.values()) m.remove(player.getUUID());
        DECIDED.remove(player.getUUID());
    }

    public static synchronized void clear() {
        for (Map<UUID, State> m : STATES.values()) m.clear();
        IN_RADON.clear();
        DECIDED.clear();
    }
}
