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
 * <li>{@code balance.immune.pool} (2000): bullets, beams and blasts (PHYS, EN, EXPL) on the full set are still called
 * off, but each takes from the pool what it would have done to a DNS-class set (formula C at S 186, tier 7.5; one hit
 * at most damageCapClick of the wearer's health). The pool comes back over {@code balance.immune.recover} once no
 * hit took from it for 5 s. Other damage (fire, falling, gas, radiation, nuclear blasts) is called off as NTM does and
 * takes nothing;
 * <li>{@code balance.immune.window} (15 s): the emptied pool opens a window: every hit goes through, to the set's
 * rating (balanceS.euphemium, 280) and the damage cap, and the set's Regeneration and Resistance (NTM: 128) are gone
 * for it, or the window would give nothing;
 * <li>{@code balance.immune.recover} (60 s): after the window the pool fills back from 0 over this time, the set
 * invulnerable meanwhile; then it is full and the round starts again.
 * <li>the tomb's radon gas (BlockGasRadonTomb) takes the invulnerability off entirely while the wearer is in it, with
 * the Regeneration and Resistance: its radiation then counts (NTM: 0.5 RAD a tick inside, 1000 RAD hurt for 1000).
 * </ul>
 * The state is kept by the player's id for the server's run (a relog does not refill the pool).
 */
public final class EuphImmunity {
    private EuphImmunity() {
    }

    /** the pool's numbers */
    public record Pool(float size, int window, int recover) {
        public static final Pool DEFAULT = new Pool(2000F, 300, 1200);
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
        Phase ph = phase(s, now);
        if (ph == Phase.WINDOW) return false;
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

    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final Map<Entity, Long> IN_RADON = new WeakHashMap<>();

    public static boolean on() {
        return acup.gunfeel.compat.Feature.BALANCE.on() && !ReaimTimes.text("balance.immune.mode", "pool").trim().equalsIgnoreCase("ntm");
    }

    /** balance.immune.dns: formula (the balance's: no invulnerability, the set's rating) or ntm (all but blasts called off) */
    public static boolean dnsFormula() {
        return acup.gunfeel.compat.Feature.BALANCE.on() && !ReaimTimes.text("balance.immune.dns", "formula").trim().equalsIgnoreCase("ntm");
    }

    public static Pool pool() {
        Pool d = Pool.DEFAULT;
        float size = ReaimTimes.number("balance.immune.pool", d.size());
        float window = ReaimTimes.number("balance.immune.window", d.window() / 20F);
        float recover = ReaimTimes.number("balance.immune.recover", d.recover() / 20F);
        return new Pool(Float.isFinite(size) && size > 0F ? size : d.size(), Float.isFinite(window) && window >= 0F ? Math.round(window * 20F) : d.window(),
                Float.isFinite(recover) && recover >= 0F ? Math.round(recover * 20F) : d.recover());
    }

    static synchronized State state(Player player, long now) {
        return STATES.computeIfAbsent(player.getUUID(), u -> new State(pool().size(), now));
    }

    /** the tomb's radon gas touched the entity this tick */
    public static synchronized void inRadon(Entity entity, long now) {
        IN_RADON.put(entity, now);
    }

    static synchronized boolean radon(Entity entity, long now) {
        Long t = IN_RADON.get(entity);
        return t != null && now - t <= 1;
    }

    /**
     * NTM's euphemium check found the full set on {@code player} for this hit: true calls it off (NTM's way), false
     * lets it through.
     */
    public static boolean absorbs(Player player, DamageSource source, float amount) {
        if (!on()) return true;
        long now = player.level().getGameTime();
        if (radon(player, now)) {
            // the set's tick takes them off too, but not before this hit
            dropEffects(player);
            return false;
        }
        State s = state(player, now);
        Pool p = pool();
        String cat = BalanceArmor.category(source);
        float drain = 0F;
        if (cat != null) {
            NtmDamageContext ctx = (NtmDamageContext) player;
            float s186 = DamageResistanceHandler.CATEGORY_ENERGY.equals(cat) ? POOL_S * Math.min(1F, ReaimTimes.number("balanceEnShare", 0.75F)) : POOL_S;
            boolean squeeze = !DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat) || amount <= ReaimTimes.number("balanceExplRawLimit", 60F);
            drain = BalanceArmorRule.full(new BalanceArmorRule.Hit(amount, ctx.hbm$pierceDT(), ctx.hbm$pierceDR(), squeeze), s186, POOL_TAU, BalanceArmorRule.Tune.DEFAULT);
            float click = ReaimTimes.number("damageCapClick", 0.6F);
            if (Float.isFinite(click) && click > 0F) drain = Math.min(drain, click * player.getMaxHealth());
        }
        Phase before;
        boolean off;
        synchronized (EuphImmunity.class) {
            before = phase(s, now);
            off = hit(s, now, drain, cat != null, p);
        }
        if (before == Phase.POOL && phase(s, now + 1) == Phase.WINDOW) {
            dropEffects(player);
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) sp.sendSystemMessage(Component.translatable("ntmgunfeel.euph.window", p.window() / 20), true);
        }
        return off;
    }

    /** the set's Regeneration and Resistance must not be on the wearer now: the window, or the radon gas */
    public static boolean vulnerable(Player player) {
        if (!on()) return false;
        long now = player.level().getGameTime();
        if (radon(player, now)) return true;
        State s;
        synchronized (EuphImmunity.class) {
            s = STATES.get(player.getUUID());
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
    static synchronized String describe(Player player) {
        State s = STATES.get(player.getUUID());
        if (s == null) return "нет";
        long now = player.level().getGameTime();
        return phase(s, now) + " " + Math.round(poolAt(s, now, pool()));
    }

    static synchronized void forget(Player player) {
        STATES.remove(player.getUUID());
    }

    public static synchronized void clear() {
        STATES.clear();
        IN_RADON.clear();
    }
}
