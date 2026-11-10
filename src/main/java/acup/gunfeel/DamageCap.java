package acup.gunfeel;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import org.jspecify.annotations.Nullable;

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
public final class DamageCap {
    private DamageCap() {
    }

    /** one projectile: the tick it was fired, its gun waiting {@code sniperDelay} or more between shots */
    private record Shot(long tick, boolean sniper) {
    }

    private static final Map<Entity, Shot> SHOTS = new WeakHashMap<>();
    private static final Map<UUID, Map<UUID, DamageCapRule.Ledger>> LEDGERS = new HashMap<>();
    /** NTM's shield of each target being hurt, before the hurt, and the tick it was read */
    private record Before(long tick, float shield) {
    }

    private static final Map<LivingEntity, Before> SHIELD_BEFORE = new IdentityHashMap<>();

    /** the stand: what the last capped hit was (its click, head, sniper, the damage before and after) */
    static volatile String last = "";
    /** the stand: hits capped so far */
    static volatile long cut;

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, EntityJoinLevelEvent.class, DamageCap::joined);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LivingIncomingDamageEvent.class, DamageCap::incoming);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, LivingDamageEvent.Pre.class, DamageCap::damage);
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> {
            if (e.getServer().getTickCount() % 200 == 0) sweep(e.getServer().overworld().getGameTime());
        });
        // another world in the same game starts its ticks anew
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.server.ServerStoppingEvent.class, e -> forget());
    }

    static boolean on() {
        return ReaimTimes.flag("damageCap", true);
    }

    static DamageCapRule.Limits limits() {
        var d = DamageCapRule.Limits.DEFAULT;
        return new DamageCapRule.Limits(share("damageCapClick", d.click()), share("damageCapHead", d.head()), share("damageCapWindow", d.window()),
                ticks("damageCapWindowTicks", d.windowTicks()), share("damageCapSniperHead", d.sniperHead()), ticks("damageCapSniperDelay", d.sniperDelay()));
    }

    /** a share of max health: above 0, a number (else the default) */
    private static float share(String key, float def) {
        float v = ReaimTimes.number(key, def);
        return Float.isFinite(v) && v > 0F ? v : def;
    }

    /** ticks: 1 or more (else the default) */
    private static int ticks(String key, int def) {
        float v = ReaimTimes.number(key, def);
        return Float.isFinite(v) && v >= 1F ? Math.round(v) : def;
    }

    // ---------------------------------------------------------------- who fired it

    /** the living entity that fired {@code e}, or {@code e} itself when it is living */
    static @Nullable LivingEntity shooter(@Nullable Entity e) {
        if (e == null) return null;
        if (e instanceof LivingEntity living) return living;
        if (e instanceof EntityBulletBeamBase beam) return beam.getThrower();
        if (e instanceof EntityThrowableNT thrown && thrown.getThrower() != null) return thrown.getThrower();
        if (e instanceof Projectile p && p.getOwner() instanceof LivingEntity owner) return owner;
        return null;
    }

    /** the wait between shots of the gun in the shooter's main hand (NTM's guns fire from there; akimbo guns are one item) */
    static int gunDelay(LivingEntity shooter) {
        int out = 0;
        ItemStack stack = shooter.getMainHandItem();
        if (!(stack.getItem() instanceof ItemGunBaseNT gun)) return 0;
        for (int i = 0; i < gun.getConfigCount(); i++) {
            GunConfig config = gun.getConfig(stack, i);
            if (config == null) continue;
            for (Receiver r : config.getReceivers(stack)) out = Math.max(out, r.getDelayAfterFire(stack));
        }
        return out;
    }

    private static void joined(EntityJoinLevelEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level) || e.getEntity() instanceof LivingEntity) return;
        Entity entity = e.getEntity();
        if (!(entity instanceof Projectile || entity instanceof EntityBulletBeamBase)) return;
        LivingEntity shooter = shooter(entity);
        if (shooter == null) return;
        SHOTS.put(entity, new Shot(level.getGameTime(), gunDelay(shooter) >= limits().sniperDelay() && !pellet(entity)));
    }

    /**
     * a pellet of a round of several (buckshot, Pepperbox's stone shot): never a sniper's, whatever its gun waits, or
     * one pellet in the head would lift the whole click's cap to the sniper's
     */
    static boolean pellet(Entity entity) {
        com.hbm.items.weapon.sedna.BulletConfig c = entity instanceof com.hbm.entity.projectile.EntityBulletBaseMK4 b ? b.config
                : entity instanceof EntityBulletBeamBase beam ? beam.config : null;
        return c != null && c.projectilesMax > 1;
    }

    // ---------------------------------------------------------------- the hurt

    /** not capped: no shooter's gun behind it (nuclear, digamma, fire, falling, radiation) */
    static boolean exempt(DamageSource source) {
        return source.is(ModDamageTypes.NUCLEAR_BLAST) || source.is(ModDamageTypes.RADIATION) || source.is(ModDamageTypes.DIGAMMA)
                || source.is(DamageTypeTags.IS_FIRE) || source.is(DamageTypeTags.IS_FALL);
    }

    /** the projectile between the shooter and the target, or null for none (melee, no shooter) */
    private static @Nullable Entity projectile(DamageSource source, LivingEntity shooter) {
        Entity direct = source.getDirectEntity(), cause = source.getEntity();
        if (direct != null && direct != shooter) return direct;
        if (cause != null && cause != shooter) return cause;
        return null;
    }

    private static boolean capped(LivingEntity target) {
        return target instanceof Player || ReaimTimes.flag("damageCapMobs", false);
    }

    private static void incoming(LivingIncomingDamageEvent e) {
        if (e.getEntity() instanceof Player p && !p.level().isClientSide() && on()) SHIELD_BEFORE.put(p, new Before(p.level().getGameTime(), HbmPlayerProps.getData(p).shield));
    }

    /**
     * an NTM blast of more raw damage than balanceExplRawLimit (a mine, C4, a block of TNT: building, not a weapon) is
     * not capped, nor any NTM blast with damageCapBlasts off. Raw: as NTM's armour (and the balance's formula, its
     * squeeze by the same limit) takes it, after NTM's shield.
     */
    static boolean blastFree(float raw) {
        return !ReaimTimes.flag("damageCapBlasts", true) || raw > BalanceArmor.explRawLimit();
    }

    private static void damage(LivingDamageEvent.Pre e) {
        LivingEntity target = e.getEntity();
        Before shieldBefore = SHIELD_BEFORE.remove(target);
        if (!(target.level() instanceof ServerLevel level) || !on() || !capped(target)) return;
        DamageSource source = e.getSource();
        LivingEntity shooter = shooter(source.getEntity());
        if (shooter == null) shooter = shooter(source.getDirectEntity());
        if (shooter == null || shooter == target || exempt(source)) return;
        Entity projectile = projectile(source, shooter);
        // NTM's blasts from a bullet or a grenade name the shooter alone (ExplosionVNT's exploder): NTM's blast is capped
        // with no projectile, as a click of this tick (a creeper's, vanilla's, is not)
        boolean blast = source.is(DamageTypeTags.IS_EXPLOSION) && source.typeHolder().unwrapKey().map(k -> k.identifier().getNamespace().equals("hbm")).orElse(false);
        if (projectile == null && !blast) return;
        long now = level.getGameTime();
        // the hurt's raw damage as NTM's armour took it (after NTM's shield), else the hurt's own
        float raw = BalanceArmor.takeAmount(target, e.getOriginalDamage());
        if (blast && blastFree(raw)) return;
        Shot shot = projectile == null ? null : SHOTS.get(projectile);
        if (shot == null) shot = new Shot(projectile == null ? now : now - projectile.tickCount,
                gunDelay(shooter) >= limits().sniperDelay() && (projectile == null || !pellet(projectile)));
        boolean head = HeadDamage.onHead(target);
        float shield = 0F;
        HbmPlayerProps props = null;
        // read before this hurt, in this tick: an older one was left by a hurt that stopped short
        if (target instanceof Player p && shieldBefore != null && shieldBefore.tick() == now) {
            props = HbmPlayerProps.getData(p);
            shield = Math.max(0F, shieldBefore.shield() - props.shield);
        }
        var ledger = LEDGERS.computeIfAbsent(target.getUUID(), k -> new HashMap<>()).computeIfAbsent(shooter.getUUID(),
                k -> new DamageCapRule.Ledger());
        float before = e.getNewDamage();
        var result = DamageCapRule.take(ledger, limits(), now, shot.tick(), head, shot.sniper(), target.getMaxHealth(), shield, before);
        if (result.shield() > 0F && props != null) props.shield += result.shield();
        if (result.damage() < before) {
            e.setNewDamage(result.damage());
            cut++;
        }
        last = "click=" + shot.tick() + " head=" + head + " sniper=" + shot.sniper() + " shield=" + shield + " damage=" + before + "->"
                + result.damage() + " shieldBack=" + result.shield();
    }

    private static void sweep(long now) {
        SHIELD_BEFORE.clear();
        for (Iterator<Map<UUID, DamageCapRule.Ledger>> it = LEDGERS.values().iterator(); it.hasNext();) {
            var byShooter = it.next();
            byShooter.values().removeIf(l -> l.idle(now));
            if (byShooter.isEmpty()) it.remove();
        }
    }

    /** the stand: everything forgotten */
    static void forget() {
        LEDGERS.clear();
        SHIELD_BEFORE.clear();
    }
}
