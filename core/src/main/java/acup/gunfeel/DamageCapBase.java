package acup.gunfeel;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.CapNow;
import acup.gunfeel.host.HurtNow;
import acup.gunfeel.host.Hurts;
import acup.gunfeel.host.NtmBullets;
import acup.gunfeel.host.NtmGuns;
import acup.gunfeel.host.Worlds;

/** The cap's settings, shots, shield snapshots, ledgers and diagnostics; events stay with each version. */
class DamageCapBase {
    DamageCapBase() {
    }

    interface Settings {
        boolean flag(String key, boolean def);
        float number(String key, float def);
    }

    private static final Settings LIVE = new Settings() {
        public boolean flag(String key, boolean def) { return ReaimTimes.flag(key, def); }
        public float number(String key, float def) { return ReaimTimes.number(key, def); }
    };

    private static final class CapPort {
        static final CapNow CAP = CoreServices.load(CapNow.class, CapNow.MISSING);
        static final HurtNow NOW = CoreServices.load(HurtNow.class, HurtNow.MISSING);
    }

    private static final class ShotPort {
        static final NtmBullets BULLETS = CoreServices.load(NtmBullets.class, NtmBullets.MISSING);
        static final NtmGuns GUNS = CoreServices.load(NtmGuns.class, NtmGuns.NONE);
    }

    /** one projectile: the tick it was fired, its gun waiting {@code sniperDelay} or more between shots */
    record Shot(long tick, boolean sniper) {}
    private static final Map<Object, Shot> SHOTS = new WeakHashMap<>();

    static void joinedOf(Object level, Object entity) {
        joinedOf(level, entity, ShotPort.BULLETS, ShotPort.GUNS, LIVE);
    }

    static void joinedOf(Object level, Object entity, NtmBullets bullets, NtmGuns guns, Settings settings) {
        if (!bullets.capProjectile(entity)) return;
        Body shooter = bullets.shooter(entity);
        if (shooter == null) return;
        SHOTS.put(entity, new Shot(Worlds.of(level).gameTime(),
                guns.maxDelay(shooter, Body.MAIN_HAND) >= limitsOf(settings).sniperDelay() && !bullets.pellet(entity)));
    }

    static @Nullable Shot shotOf(Object entity) {
        return SHOTS.get(entity);
    }

    private static final Map<UUID, Map<UUID, DamageCapRule.Ledger>> LEDGERS = new HashMap<>();
    /** NTM's shield before the hurt, and the tick it was read. */
    record Before(long tick, float shield) {}
    private static final Map<Object, Before> SHIELD_BEFORE = new IdentityHashMap<>();
    static volatile String last = "";
    static volatile long cut;

    static boolean cappedOf(Body target) {
        return cappedOf(target, LIVE);
    }

    static boolean cappedOf(Body target, Settings settings) {
        return target.player() || settings.flag("damageCapMobs", false);
    }

    static void incomingOf(Body target) {
        incomingOf(target, CapPort.CAP, LIVE);
    }

    static void incomingOf(Body target, CapNow cap, Settings settings) {
        if (target.player() && !cap.client(target) && onOf(settings))
            SHIELD_BEFORE.put(cap.entity(target), new Before(cap.time(target), cap.shield(target)));
    }

    static Before removeBeforeOf(Object target) {
        return SHIELD_BEFORE.remove(target);
    }

    static boolean blastFreeOf(float raw) {
        return blastFreeOf(raw, LIVE);
    }

    static boolean blastFreeOf(float raw, Settings settings) {
        if (!settings.flag("damageCapBlasts", true)) return true;
        return raw > BalanceArmorBase.explRawLimit() && !BalanceArmorBase.bulletBlastNow();
    }

    static float takeAmountOf(Body target, float fallback) {
        return takeAmountOf(target, fallback, CapPort.CAP);
    }

    static float takeAmountOf(Body target, float fallback, CapNow cap) {
        return cap.takeAmount(target, fallback);
    }

    static boolean headOf(Body target) {
        return headOf(target, CapPort.NOW);
    }

    static boolean headOf(Body target, HurtNow hurt) {
        return hurt.onHead(target);
    }

    static boolean sniperOf(Body target, Object hurt, boolean head, boolean sniper) {
        return sniperOf(target, hurt, head, sniper, CapPort.CAP);
    }

    static boolean sniperOf(Body target, Object hurt, boolean head, boolean sniper, CapNow cap) {
        return head && sniper && cap.schemeHit(target, hurt) ? false : sniper;
    }

    /** The event writes damage before finishOf updates the stand's counters. */
    record Applied(long click, boolean head, boolean sniper, float shield, float before, DamageCapRule.Cut result) {}

    static Applied applyOf(Body target, Body shooter, Before shieldBefore, long now, long click, boolean head,
            boolean sniper, Supplier<Float> damage) {
        return applyOf(target, shooter, shieldBefore, now, click, head, sniper, damage, CapPort.CAP, LIVE);
    }

    static Applied applyOf(Body target, Body shooter, Before shieldBefore, long now, long click, boolean head,
            boolean sniper, Supplier<Float> damage, CapNow cap, Settings settings) {
        // An older snapshot was left by a hurt that stopped short.
        if (target.player() && shieldBefore != null && shieldBefore.tick() == now) {
            return cap.withShield(target, (props, currentShield) -> {
                float shield = Math.max(0F, shieldBefore.shield() - currentShield);
                var hit = applyShieldOf(target, shooter, now, click, head, sniper, damage, settings, shield);
                return new CapNow.ShieldBack<>(hit, hit.result().shield() > 0F, hit.result().shield());
            });
        }
        return applyShieldOf(target, shooter, now, click, head, sniper, damage, settings, 0F);
    }

    private static Applied applyShieldOf(Body target, Body shooter, long now, long click, boolean head, boolean sniper,
            Supplier<Float> damage, Settings settings, float shield) {
        var ledger = LEDGERS.computeIfAbsent(target.uuid(), k -> new HashMap<>()).computeIfAbsent(shooter.uuid(),
                k -> new DamageCapRule.Ledger());
        float before = damage.get();
        var result = DamageCapRule.take(ledger, limitsOf(settings), now, click, head, sniper, target.maxHealth(), shield, before);
        return new Applied(click, head, sniper, shield, before, result);
    }

    static void finishOf(Applied hit) {
        if (hit.result().damage() < hit.before()) cut++;
        last = "click=" + hit.click() + " head=" + hit.head() + " sniper=" + hit.sniper() + " shield=" + hit.shield() + " damage=" + hit.before() + "->"
                + hit.result().damage() + " shieldBack=" + hit.result().shield();
    }

    static boolean sweepTickOf(int tick) {
        return tick % 200 == 0;
    }

    static void sweepOf(long now) {
        SHIELD_BEFORE.clear();
        for (Iterator<Map<UUID, DamageCapRule.Ledger>> it = LEDGERS.values().iterator(); it.hasNext();) {
            var byShooter = it.next();
            byShooter.values().removeIf(l -> l.idle(now));
            if (byShooter.isEmpty()) it.remove();
        }
    }

    static void forgetOf(Body target) {
        forgetOf(target, CapPort.CAP);
    }

    static void forgetOf(Body target, CapNow cap) {
        LEDGERS.remove(target.uuid());
        SHIELD_BEFORE.remove(cap.entity(target));
    }

    static void forgetOf() {
        LEDGERS.clear();
        SHIELD_BEFORE.clear();
    }

    static boolean onOf() {
        return onOf(LIVE);
    }

    static boolean onOf(Settings settings) {
        return settings.flag("damageCap", true);
    }

    static DamageCapRule.Limits limitsOf() {
        return limitsOf(LIVE);
    }

    static DamageCapRule.Limits limitsOf(Settings settings) {
        var d = DamageCapRule.Limits.DEFAULT;
        return new DamageCapRule.Limits(shareOf(settings, "damageCapClick", d.click()), shareOf(settings, "damageCapHead", d.head()), shareOf(settings, "damageCapWindow", d.window()),
                ticksOf(settings, "damageCapWindowTicks", d.windowTicks()), shareOf(settings, "damageCapSniperHead", d.sniperHead()), ticksOf(settings, "damageCapSniperDelay", d.sniperDelay()));
    }

    static float shareOf(String key, float def) {
        return shareOf(LIVE, key, def);
    }

    /** a share of max health: above 0, a number (else the default) */
    static float shareOf(Settings settings, String key, float def) {
        float v = settings.number(key, def);
        return Float.isFinite(v) && v > 0F ? v : def;
    }

    static int ticksOf(String key, int def) {
        return ticksOf(LIVE, key, def);
    }

    /** ticks: 1 or more (else the default) */
    static int ticksOf(Settings settings, String key, int def) {
        float v = settings.number(key, def);
        return Float.isFinite(v) && v >= 1F ? Math.round(v) : def;
    }

    /** not capped: no shooter's gun behind it (nuclear, digamma, fire, falling, radiation) */
    static boolean exemptOf(Object hurt) {
        return exemptOf(BalanceArmorBase.HurtPort.HURTS, hurt);
    }

    /** {@link #exemptOf(Object)} on the port {@code h} (a test's own) */
    static boolean exemptOf(Hurts h, Object hurt) {
        return h.is(hurt, Hurts.NTM_NUCLEAR_BLAST) || h.is(hurt, Hurts.NTM_RADIATION) || h.is(hurt, Hurts.NTM_DIGAMMA)
                || (h.is(hurt, Hurts.FIRE) && !BalanceArmorBase.irBeamOf(h, hurt)) || h.is(hurt, Hurts.FALL);
    }
}
