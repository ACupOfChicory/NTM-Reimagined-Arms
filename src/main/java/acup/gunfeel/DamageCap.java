package acup.gunfeel;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.CapNow;
import acup.gunfeel.host.NtmBullets;
import acup.gunfeel.host.NtmGuns;

import com.hbm.entity.projectile.EntityBulletBeamBase;
import com.hbm.entity.projectile.EntityThrowableNT;
import com.hbm.extprop.HbmPlayerProps;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.lib.ModDamageTypes;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The damage cap (server settings damageCap*): a shooter's click takes at most a share of a player's max health, and
 * any 14 ticks at most another (DamageCapRule).
 *
 * <p>The shooter is the living entity behind the damage: NTM's bullets, beams and grenades carry it as the source's
 * cause ({@code BulletConfig.getDamage}: the projectile direct, the shooter causing); NTM's blasts name the exploding
 * projectile, whose thrower is the shooter, or the shooter alone (a blast with no projectile is a click of its tick).
 * Damage with no projectile between the shooter and the target and no blast (melee) and
 * damage with no shooter (fire, falling, radiation, gas) is not capped, nor are nuclear blasts and digamma, nor an NTM
 * blast of more raw damage than balanceExplRawLimit (a mine, C4, TNT), nor any NTM blast with damageCapBlasts off.
 *
 * <p>The click is the game tick the projectile came into the world: NTM fires all the rounds of one click in one call
 * (Lego: {@code roundsPerCycle}, SPAS-12's secondary one more, akimbo guns in the same tick), so pellets that land in
 * different ticks (a client's hit held for its report, a far target) still add up as one click.
 *
 * <p>Applied in LivingDamageEvent.Pre, last: after NTM's armour (threshold and resistance), vanilla's armour and the
 * part multiplier (HeadDamage), before absorption takes its share. NTM's shield took its share earlier (at the head
 * of actuallyHurt); it is read before the hurt and after, counted with the rest, and what it took over the cap is
 * given back. Each shooter has their own cap on each target.
 */
public final class DamageCap extends DamageCapBase {
    private DamageCap() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, EntityJoinLevelEvent.class, DamageCap::joined);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LivingIncomingDamageEvent.class, DamageCap::incoming);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LivingDamageEvent.Pre.class, DamageCap::damage);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> {
            if (sweepTickOf(e.getServer().getTickCount())) sweepOf(e.getServer().overworld().getGameTime());
        });
        // another world in the same game starts its ticks anew
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.server.ServerStoppingEvent.class, e -> forget());
    }

    static boolean on() {
        return onOf();
    }

    static DamageCapRule.Limits limits() {
        return limitsOf();
    }

    // ---------------------------------------------------------------- who fired it

    /** the living entity that fired {@code e}, or {@code e} itself when it is living */
    static @Nullable LivingEntity shooter(@Nullable Entity e) {
        return (LivingEntity) EntityBody.entity(BulletsPort.BULLETS.shooter(e));
    }

    /** the wait between shots of the gun in the shooter's main hand (NTM's guns fire from there; akimbo guns are one item) */
    static int gunDelay(LivingEntity shooter) {
        return GunsPort.GUNS.maxDelay(EntityBody.of(shooter), Body.MAIN_HAND);
    }

    private static final class GunsPort {
        static final NtmGuns GUNS = CoreServices.load(NtmGuns.class, NtmGuns.NONE);
    }

    private static final class BulletsPort {
        static final NtmBullets BULLETS = CoreServices.load(NtmBullets.class, NtmBullets.MISSING);
    }

    private static void joined(EntityJoinLevelEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || e.getEntity() instanceof LivingEntity) return;
        Entity entity = e.getEntity();
        joinedOf(level, entity);
    }

    /**
     * a pellet of a round of several (buckshot, Pepperbox's stone shot): never a sniper's, whatever its gun waits, or
     * one pellet in the head would lift the whole click's cap to the sniper's
     */
    static boolean pellet(Entity entity) {
        return BulletsPort.BULLETS.pellet(entity);
    }

    // ---------------------------------------------------------------- the hurt

    /** not capped: no shooter's gun behind it (nuclear, digamma, fire, falling, radiation) */
    static boolean exempt(DamageSource source) {
        return exemptOf(source);
    }

    /** The cap's live reads for core (CapNow, NtmBullets and HurtsPort); NTM probes count them as CORE. */
    static final class Reads implements CapNow, NtmBullets {
        Reads() {
        }

        @Override public @Nullable Body shooter(@Nullable Object e) {
            if (e == null) return null;
            if (e instanceof LivingEntity living) return EntityBody.of(living);
            if (e instanceof EntityBulletBeamBase beam) {
                Entity by = beam.getThrower();
                return by == null ? null : EntityBody.of(by);
            }
            if (e instanceof EntityThrowableNT thrown && thrown.getThrower() != null) {
                Entity by = thrown.getThrower();
                return by == null ? null : EntityBody.of(by);
            }
            if (e instanceof Projectile p && p.getOwner() instanceof LivingEntity owner) return EntityBody.of(owner);
            return null;
        }

        @Override public boolean capProjectile(Object e) {
            return e instanceof Projectile || e instanceof EntityBulletBeamBase;
        }

        @Override public boolean pellet(Object entity) {
            com.hbm.items.weapon.sedna.BulletConfig c = entity instanceof com.hbm.entity.projectile.EntityBulletBaseMK4 b ? b.config
                    : entity instanceof EntityBulletBeamBase beam ? beam.config : null;
            return c != null && c.projectilesMax > 1;
        }

        static int maxDelay(ItemStack stack) {
            int out = 0;
            if (!(stack.getItem() instanceof ItemGunBaseNT gun)) return 0;
            for (int i = 0; i < gun.getConfigCount(); i++) {
                GunConfig config = gun.getConfig(stack, i);
                if (config == null) continue;
                for (Receiver r : config.getReceivers(stack)) out = Math.max(out, r.getDelayAfterFire(stack));
            }
            return out;
        }

        static boolean is(DamageSource source, int what) {
            return switch (what) {
                case acup.gunfeel.host.Hurts.NTM_NUCLEAR_BLAST -> source.is(ModDamageTypes.NUCLEAR_BLAST);
                case acup.gunfeel.host.Hurts.NTM_DIGAMMA -> source.is(ModDamageTypes.DIGAMMA);
                case acup.gunfeel.host.Hurts.NTM_RADIATION -> source.is(ModDamageTypes.RADIATION);
                default -> throw new IllegalArgumentException("not the cap's question " + what);
            };
        }

        @Override public Object entity(Body target) { return EntityBody.entity(target); }
        @Override public boolean client(Body target) { return EntityBody.entity(target).level().isClientSide(); }
        @Override public long time(Body target) { return EntityBody.entity(target).level().getGameTime(); }
        @Override public float shield(Body target) { return HbmPlayerProps.getData((Player) EntityBody.entity(target)).shield; }
        @Override public <T> T withShield(Body target, ShieldUse<T> use) {
            var props = HbmPlayerProps.getData((Player) EntityBody.entity(target));
            var back = use.take(props, props.shield);
            if (back.give()) props.shield += back.amount();
            return back.value();
        }
        @Override public float takeAmount(Body target, float fallback) { return BalanceArmor.takeAmount((LivingEntity) EntityBody.entity(target), fallback); }
        @Override public boolean schemeHit(Body target, Object hurt) { return BalanceFinal.hit((LivingEntity) EntityBody.entity(target), (DamageSource) hurt) != null; }

        static boolean ntmExplosion(DamageSource source) {
            return source.is(DamageTypeTags.IS_EXPLOSION) && source.typeHolder().unwrapKey().map(k -> k.identifier().getNamespace().equals("hbm")).orElse(false);
        }
    }

    private static boolean capped(LivingEntity target) {
        return cappedOf(EntityBody.of(target));
    }

    private static void incoming(LivingIncomingDamageEvent e) {
        incomingOf(EntityBody.of(e.getEntity()));
    }

    /**
     * an NTM blast of more raw damage than balanceExplRawLimit (a mine, C4, a block of TNT: building, not a weapon) is
     * not capped, nor any NTM blast with damageCapBlasts off. Raw: as NTM's armour (and the balance's formula, its
     * squeeze by the same limit) takes it, after NTM's shield.
     */
    static boolean blastFree(float raw) {
        return blastFreeOf(raw);
    }

    private static void damage(LivingDamageEvent.Pre e) {
        LivingEntity target = e.getEntity();
        Before shieldBefore = removeBeforeOf(target);
        if (!(target.level() instanceof ServerLevel level)) return;
        var hit = damageOf(EntityBody.of(target), level, shieldBefore, e, DamageReads.INSTANCE);
        if (hit == null) return;
        if (hit.result().damage() < hit.before()) e.setNewDamage(hit.result().damage());
        finishOf(hit);
    }

    /** Stateless event access (26.2: damageOf calls it only when each read is needed). */
    private enum DamageReads implements DamageEvent<LivingDamageEvent.Pre> {
        INSTANCE;

        @Override public Object source(LivingDamageEvent.Pre e) { return e.getSource(); }
        @Override public float original(LivingDamageEvent.Pre e) { return e.getOriginalDamage(); }
        @Override public Applied apply(LivingDamageEvent.Pre e, Body target, Body shooter, Before before,
                long now, Shot shot, boolean head) {
            float damage = e.getNewDamage();
            return applyOf(target, shooter, before, now, shot.tick(), head, shot.sniper(), damage);
        }
    }

    /** the stand: what was taken off {@code target} forgotten (its ledgers, by every shooter) */
    static void forget(LivingEntity target) {
        forgetOf(EntityBody.of(target));
    }

    /** the stand: everything forgotten */
    static void forget() {
        forgetOf();
    }
}
