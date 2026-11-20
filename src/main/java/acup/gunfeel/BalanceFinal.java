package acup.gunfeel;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import com.hbm.util.DamageResistanceHandler;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * The balance's final scheme (server setting balance.final; BalanceFinalRule): a bullet or a beam of a pair of the
 * scheme's table on a player, or on anyone wearing a piece of the table's sets, does {@code 20 / N} HP a click whose
 * projectiles all hit, N worked out from the pair's tier, the set's, the pair's raw DPS and role (the TTK ladder of
 * balance.final.ttk). NTM's damage, the set's threshold and resistance and the formula C are not in it. The part hit
 * (head ×1.75, limb ×0.5), the damage cap and the pools of DNS, FAU and euphemium come after, as before; the hit's
 * share by range (balance.final.range, BalanceRangeRule) by the pair's role and the shooter's distance.
 *
 * <p>Blasts (balance.final.blast; Fable 12.3, BalanceBlastRule): a launcher's round's or an NTM hand grenade's blast on
 * the same targets takes the ladder's share of the health and pool, the GL-54's fragment grenades a burst's share; the
 * other blasts keep formula C (balanceBlast*). Out of it: an explosive bullet's blast on anyone but the creature it struck, and shots of no
 * pair of the table (NTM's mobs' and turrets' shots of no gun, other games' guns): NTM's numbers. Off: everything as X908.
 */
public final class BalanceFinal extends BalanceFinalBase {
    private BalanceFinal() {
    }

    // ---------------------------------------------------------------- the shot's pair

    /** the pair (gun.ammo) a projectile was fired with, by the projectile */
    private static final Map<Entity, String> PAIR = Collections.synchronizedMap(new WeakHashMap<>());

    /** a share of its pair's damage a projectile does (ShockServer's repeated hit: a part of a slug's) */
    private static final Map<Entity, Float> SHARE = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * a hit of {@code projectile} (fired with {@code from}) on one shocked or immune to a shock: it does {@code share}
     * of a shot of the same gun with {@code as}, by the scheme's pair of it when there is one (the target's armour and
     * the range as for that shot); the share of the projectile's own damage that makes it so off the scheme (a target
     * not the scheme's), 1 with the balance off
     */
    public static float hitAs(Entity projectile, LivingEntity shooter, com.hbm.items.weapon.sedna.BulletConfig from, com.hbm.items.weapon.sedna.BulletConfig as,
            float share) {
        if (projectile == null || shooter == null || shooter.level().isClientSide() || !BalanceGuns.on()) return 1F;
        net.minecraft.world.item.Item gunItem = BalanceGuns.gunOf(shooter);
        if (gunItem == null) return 1F;
        try {
            String gun = gunName(gunItem);
            String id = gun + "." + ammoName(as);
            if (on() && gun != null && state().pairs().containsKey(id)) {
                PAIR.put(projectile, id);
                SHARE.put(projectile, share);
            }
            return share * BalanceGuns.as(gunItem, from, as);
        } catch (RuntimeException | LinkageError e) {
            // a hit must never fail over the tables: the shell's own damage
            return 1F;
        }
    }

    /**
     * a hit of {@code projectile} (fired with {@code from}) that does {@code share} of a shot of the same gun with
     * {@code as} (a Duplex round's bullet: of the SP's): hitAs, and {@code share} of its own damage where hitAs leaves
     * it (the balance off, no gun in the shooter's hands, the tables failing), {@code from} being as heavy as {@code as}
     * in NTM's numbers
     */
    public static float shareOf(Entity projectile, LivingEntity shooter, com.hbm.items.weapon.sedna.BulletConfig from,
            com.hbm.items.weapon.sedna.BulletConfig as, float share) {
        if (projectile == null || shooter == null || shooter.level().isClientSide() || !BalanceGuns.on()) return share;
        net.minecraft.world.item.Item gunItem = BalanceGuns.gunOf(shooter);
        if (gunItem == null) return share;
        // hitAs's own, but a table failing leaves the share (hitAs would give the whole damage: two bullets of a full SP)
        try {
            String gun = gunName(gunItem);
            String id = gun + "." + ammoName(as);
            if (on() && gun != null && state().pairs().containsKey(id)) {
                PAIR.put(projectile, id);
                SHARE.put(projectile, share);
            }
            return share * BalanceGuns.as(gunItem, from, as);
        } catch (RuntimeException | LinkageError e) {
            return share;
        }
    }

    private static float share(DamageSource source) {
        Float k = SHARE.get(source.getDirectEntity());
        return k == null ? 1F : k;
    }

    /** the last pair each shooter fired, and when (an NTM blast of its shot, the Tesla Cannon's, has no projectile) */
    private record Last(String pair, long tick) {
    }

    private static final Map<Entity, Last> LAST = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * a shot of {@code shooter}'s {@code gunItem} with {@code ammo} has been made (BalanceGuns.shot): its pair by the
     * ammo's name, or by the round loaded in the gun when the shot's config is not an ammo of the table (the Shredder
     * fires a shell as plasma of a config of its own)
     */
    static void fired(Entity projectile, LivingEntity shooter, net.minecraft.world.item.Item gunItem, com.hbm.items.weapon.sedna.BulletConfig ammo) {
        if (projectile == null || shooter == null || shooter.level().isClientSide() || !on()) return;
        try {
            fired0(projectile, shooter, gunItem, ammo);
        } catch (RuntimeException | LinkageError e) {
            // the shot goes on without a pair: NTM's machine takes it
        }
    }

    private static void fired0(Entity projectile, LivingEntity shooter, net.minecraft.world.item.Item gunItem, com.hbm.items.weapon.sedna.BulletConfig ammo) {
        String gun = gunName(gunItem);
        if (gun == null || ammo == null) return;
        String id = gun + "." + ammoName(ammo);
        if (!state().pairs().containsKey(id)) {
            String loaded = loadedAmmo(shooter, gunItem);
            if (loaded != null && state().pairs().containsKey(gun + "." + loaded)) id = gun + "." + loaded;
        }
        PAIR.put(projectile, id);
        LAST.put(shooter, new Last(id, shooter.level().getGameTime()));
        var count = standShots;
        if (count != null) count.merge(shooter, 1, Integer::sum);
        if (BalanceLog.on()) BalanceLog.shot(projectile, shooter, gunItem);
    }

    /** the gun's name of the scheme: NTM's, or the GL-54's "gl54" (a launcher of balance.final.blast.launchers) */
    static String gunName(net.minecraft.world.item.Item item) {
        String g = BalanceGuns.gunName(item);
        if (g != null) return g;
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        return id != null && id.getNamespace().equals(acup.gunfeel.gl54.Gl54Items.NAMESPACE) && id.getPath().equals("gun_gl54") ? "gl54" : null;
    }

    /** the ammo's name of the scheme: NTM's, or a GL-54 grenade's kind (he, airburst, ap, inc, ...) */
    static String ammoName(com.hbm.items.weapon.sedna.BulletConfig ammo) {
        if (ammo instanceof acup.gunfeel.gl54.GrenadeType g) {
            var k = g.grenade();
            return k != null ? k.key : g.kind;
        }
        return BalanceGuns.ammoName(ammo);
    }

    /** the stand (pass ps): the projectiles each shooter fired, counted while not null */
    static volatile Map<Entity, Integer> standShots;

    /** a submunition of {@code thrower}'s (made from a shot of its, the Shredder's plasma): the pair it has just fired */
    public static void submunition(Entity projectile, LivingEntity thrower) {
        if (projectile == null || thrower == null || thrower.level().isClientSide() || !on() || PAIR.containsKey(projectile)) return;
        Last l = LAST.get(thrower);
        if (l != null && thrower.level().getGameTime() - l.tick() <= 2) PAIR.put(projectile, l.pair());
    }

    /** the Tesla Cannon's IR arcs that took their shooter's pair (beamSub) */
    private static final Map<Entity, Boolean> IR_SUBS = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * a beam made by the 3-argument constructor (MixinBalanceFinalBeamSub): the IR capacitor's arc (energy_tesla_ir_sub)
     * takes the pair its shooter has just fired (Fable №2, 2.5). Its hit on the creature the
     * click's arc has just struck is nothing (againThisTick): an IR click is the table's click; on anyone else, a click
     * of the pair with no range rule.
     */
    public static void beamSub(Entity beam, LivingEntity thrower, com.hbm.items.weapon.sedna.BulletConfig config) {
        if (beam == null || thrower == null || thrower.level().isClientSide() || !on()) return;
        try {
            if (config != com.hbm.items.weapon.sedna.factory.XFactoryEnergy.energy_tesla_ir_sub) return;
        } catch (RuntimeException | LinkageError e) {
            return;
        }
        Last l = LAST.get(thrower);
        if (l == null || thrower.level().getGameTime() - l.tick() > 2) return;
        PAIR.put(beam, l.pair());
        IR_SUBS.put(beam, Boolean.TRUE);
    }

    /** the ammo name of the round loaded in the shooter's gun (its first receiver that has one), or null */
    private static String loadedAmmo(LivingEntity shooter, net.minecraft.world.item.Item gunItem) {
        var stack = shooter.getMainHandItem().getItem() == gunItem ? shooter.getMainHandItem() : shooter.getOffhandItem();
        if (!(stack.getItem() instanceof com.hbm.items.weapon.sedna.ItemGunBaseNT gun)) return null;
        try {
            for (int c = 0; c < gun.getConfigCount(); c++)
                for (var rec : gun.getConfig(stack, c).getReceivers(stack))
                    if (rec.getMagazine(stack).getType(stack, null) instanceof com.hbm.items.weapon.sedna.BulletConfig b) return BalanceGuns.ammoName(b);
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    /** a blast of a shot (not a launcher's round) the scheme takes: the pair its shooter fired in the last ticks */
    private static String blastPair(DamageSource source) {
        Entity shooter = source.getEntity();
        Last l = shooter == null ? null : LAST.get(shooter);
        if (l == null || shooter.level().getGameTime() - l.tick() > 5) return null;
        // a beam's pair alone: an NTM blast after a bullet's shot (a fire's, a launcher's) is not the pair's
        var pr = state().pairs().get(l.pair());
        return pr != null && !pr.cat().equals("PHYS") ? l.pair() : null;
    }

    /** a blast's hit of the scheme on a target: the blast (by its source) and its tick; one blast a click counts */
    private record BlastHit(int source, int shooter, long tick) {
    }

    private static final Map<Entity, BlastHit> BLAST_HITS = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * a second blast of the same shooter's on the target in the tick (the Tesla Cannon's arc again), or the IR arc's beam
     * on the target its click's arc has just struck: nothing
     */
    private static boolean irSub(DamageSource source) {
        return source.getDirectEntity() != null && IR_SUBS.containsKey(source.getDirectEntity());
    }

    private static boolean againThisTick(LivingEntity target, DamageSource source) {
        boolean irSub = irSub(source);
        // the Tesla Cannon's arc (a beam's class, not an explosion): an explosive shell's pellets each have their own blast
        if (!(BalanceArmor.vntBlastNow() || irSub) || source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)
                || DamageResistanceHandler.CATEGORY_EXPLOSION.equals(BalanceArmor.category(source))) return false;
        long now = target.level().getGameTime();
        BlastHit was = BLAST_HITS.get(target);
        int id = System.identityHashCode(source);
        int shooter = source.getEntity() == null ? Integer.MIN_VALUE : source.getEntity().getId();
        if (was != null && was.tick() == now && was.shooter() == shooter && was.source() != id) return true;
        BLAST_HITS.put(target, new BlastHit(id, shooter, now));
        return false;
    }

    /** the pair the projectile was fired with, or null */
    static String pairOf(Entity projectile) {
        return projectile == null ? null : PAIR.get(projectile);
    }

    // ---------------------------------------------------------------- a hit

    /**
     * the scheme's view of a hit in words, for the stands: its pair, the set it takes on the target and the damage of
     * a click on the body ("stg77.r556_ap on hev, 1.18 a click"); null when the hit is not the scheme's
     */
    public static String describe(LivingEntity target, DamageSource source) {
        Hit h = hit(target, source);
        return h == null ? null : String.format(java.util.Locale.ROOT, "%s.%s on %s, %.3f a click", h.pair().gun(), h.pair().ammo(), h.armor().kind(), h.clickDamage());
    }

    /** the scheme's view of one hit: its pair and the set it meets */
    record Hit(BalanceFinalRule.Pair pair, BalanceFinalRule.Armor armor, double clickDamage) {
        /** the damage of one projectile on the body (before the part hit's multiplier) */
        float projectile() {
            return (float) (clickDamage / pair.projectiles());
        }
    }

    /**
     * the hit the scheme takes, or null when it is not the scheme's (blasts, pairs of no line or of the table, targets
     * that are not players and wear nothing of the table)
     */
    static Hit hit(LivingEntity target, DamageSource source) {
        if (target.level().isClientSide() || !on()) return null;
        String cat = BalanceArmor.category(source);
        if (cat == null) return null;
        State s = state();
        Entity projectile;
        if (DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat)) {
            // an explosive bullet's blast on the creature it struck: its direct hit (variant C); the rest of the blast as before
            if (!BalanceArmor.bulletBlastNow() || ExplosiveDamage.struckNow() != target) return null;
            projectile = ExplosiveDamage.bulletNow();
        } else projectile = source.getDirectEntity();
        String id = pairOf(projectile);
        // an NTM blast of a beam or a bullet of a pair (the Tesla Cannon's arc): the pair its shooter just fired
        if (id == null && BalanceArmor.vntBlastNow() && !DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat)) id = blastPair(source);
        if (id == null) return null;
        var pr = s.pairs().get(id);
        if (pr == null) return null;
        var armor = armorOf(target, s);
        if (armor == null) return null;
        BalanceFinalRule.Armor fau = s.sets().get("fau");
        double click = s.cache().computeIfAbsent(new CacheKey(id, armor), k -> BalanceFinalRule.clickDamage(pr, armor, fau, s.refs(), s.params()));
        return new Hit(pr, armor, click);
    }

    /** the stand: the damage of one projectile of pair {@code id} on {@code target}'s body (its health's scale), NaN when not the scheme's */
    static float clickOn(LivingEntity target, String id) {
        State s = state();
        var pr = s.pairs().get(id);
        var armor = pr == null ? null : armorOf(target, s);
        if (armor == null) return Float.NaN;
        double click = BalanceFinalRule.clickDamage(pr, armor, s.sets().get("fau"), s.refs(), s.params());
        return (float) (click / pr.projectiles()) * target.getMaxHealth() / (float) BalanceFinalRule.HP;
    }

    /**
     * the set the scheme takes on {@code target}: a full set of one kind its own (with its pool), a mixed or incomplete
     * one by the slots' weights (no pool), nothing of the table: bare (players only); null when not the scheme's
     */
    static BalanceFinalRule.Armor armorOf(LivingEntity target, State s) {
        var a = armorOf0(target, s);
        if (a == null || !s.acid().on()) return a;
        // the acid's corrosion takes the set's tier down (balance.final.acid; Fable №2, 2.4)
        float k = acup.gunfeel.gas.GasServer.corrosion(target);
        if (!(k > 0F)) return a;
        double t = s.acid().tierOf(a.tier(), k);
        return t == a.tier() ? a : new BalanceFinalRule.Armor(a.kind(), t, a.hours(), a.en(), a.pool());
    }

    private static BalanceFinalRule.Armor armorOf0(LivingEntity target, State s) {
        return armorOfWorn(EntityBody.of(target), s, BalanceOn.ON);
    }

    /**
     * what the scheme leaves of a hit on {@code target} (in place of NTM's threshold and resistance and formula C), or
     * NaN when the hit is not the scheme's
     */
    static float damage(LivingEntity target, DamageSource source) {
        float b = blastOrFrag(target, source);
        if (!Float.isNaN(b)) return b * target.getMaxHealth() / (float) BalanceFinalRule.HP;
        Hit h = hit(target, source);
        if (h == null) return Float.NaN;
        if (againThisTick(target, source)) return 0F;
        if (BalanceLog.on()) BalanceLog.hit(target, source, null, h.pair().id(), h.pair().tier(), HeadDamage.onHead(target));
        float scale = target.getMaxHealth() / (float) BalanceFinalRule.HP;
        return h.projectile() * scale * range(target, source, h) * share(source);
    }

    /**
     * the hit's share by range (balance.final.range; Fable 10.4): by the pair's role and the shooter's distance to the
     * target now; 1 for a blast (an explosive shell's direct hit), the Tesla Cannon's arc and its IR beam, no shooter, the rule off.
     * A gun of a line of its own (balance.final.range.lineOf: the Shredder) takes it on every hit of its the scheme takes:
     * its beam's flash, the drops the beam leaves where it strikes (all its damage on a creature) and a drop's blast on
     * the creature it struck, by the shooter's distance to the target
     */
    private static float range(LivingEntity target, DamageSource source, Hit h) {
        var r = state().range();
        String role = BalanceFinalRule.roleOf(h.pair(), state().params());
        boolean own = r.lineOf().containsKey(h.pair().gun());
        // an explosive shell's direct hit takes the shell's line, or its role's (Fable №2, 2.1; №3); other blasts none
        boolean shell = !own && expl(source) && h.pair().explosive() && (role.equals("shotgun") || role.equals("slug"));
        // the IR arc's beam: no range, like the arc (Fable's answer 6, 5)
        // the shell's direct hit comes in NTM's blast (vntBlastNow): the shell's line all the same; so does a drop's blast of a gun's own line
        if (!r.on() || (BalanceArmor.vntBlastNow() || expl(source)) && !shell && !(own && expl(source)) || irSub(source) || source.getEntity() == null) return 1F;
        double d = source.getEntity().position().distanceTo(target.position());
        // the gun's own line; the shell's (Fable №3), or its role's when the rule has none (X942)
        return (float) BalanceRangeRule.hit(r, h.pair().gun(), role, shell, d);
    }


    /** what a hit takes from a full set's pool (EuphImmunity), or NaN when it is not the scheme's */
    static float drain(LivingEntity target, DamageSource source) {
        float b = blastOrFrag(target, source);
        if (!Float.isNaN(b)) return b;
        Hit h = hit(target, source);
        if (h == null) return Float.NaN;
        if (againThisTick(target, source)) return 0F;
        boolean head = HeadDamage.onHead(target);
        if (BalanceLog.on()) BalanceLog.hit(target, source, null, h.pair().id(), h.pair().tier(), head);
        float d = h.projectile() * range(target, source, h) * share(source);
        return head ? Math.min(d * ModelHeads.multiplier(true, false), DamageCap.limits().head() * (float) BalanceFinalRule.HP) : d;
    }

    /**
     * a shot or a beam the scheme leaves to NTM's numbers: a mob's or a turret's of no pair of the table (owner
     * 2026-11-12: NTM's damage as it is): NTM's threshold and resistance, then vanilla's armour, no formula C
     */
    static boolean ntmShot(LivingEntity target, DamageSource source) {
        if (!on()) return false;
        String cat = BalanceArmor.category(source);
        if (cat == null || DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat)) return false;
        // a player's shot of no pair (a launcher's submunition, another game's gun) keeps X908's machine
        if (!(source.getEntity() instanceof LivingEntity shooter) || shooter instanceof Player) return false;
        String id = pairOf(source.getDirectEntity());
        if (id == null && BalanceArmor.vntBlastNow()) id = blastPair(source);
        if (id == null) return true;
        State s = state();
        // a GL-54 fragment grenade's (BalanceFinal.frag): the scheme's, like a pair's
        if (s.blast().on() && id.startsWith("gl54.") && s.blast().frags().containsKey(id.substring(5))) return false;
        return !s.pairs().containsKey(id);
    }

    // ---------------------------------------------------------------- blasts (balance.final.blast; Fable 11.2, 12.3)

    /** a blast of the ladder's under way on one target: what one hit of it takes (of 20 HP and the pool), its pair */
    private record BlastNow(LivingEntity target, double take, String id, double tier, Entity projectile) {
    }

    private static final ThreadLocal<BlastNow> BLAST_NOW = new ThreadLocal<>();

    /** a hand grenade going off (ItemGrenadeFilling.explode): its filling's name */
    private static final ThreadLocal<String> GRENADE = new ThreadLocal<>();

    /** a hand grenade goes off: its blasts are dealt with its filling known */
    public static void grenade(com.hbm.entity.grenade.EntityGrenadeUniversal grenade, Runnable explode) {
        if (grenade.level().isClientSide() || !on()) {
            explode.run();
            return;
        }
        String was = GRENADE.get(), filling;
        try {
            filling = grenade.getFilling().name().toLowerCase(java.util.Locale.ROOT);
        } catch (RuntimeException e) {
            filling = null;
        }
        if (filling != null) GRENADE.set(filling);
        else GRENADE.remove();
        try {
            explode.run();
        } finally {
            if (was != null) GRENADE.set(was);
            else GRENADE.remove();
        }
    }

    /**
     * an NTM blast's hit on {@code target} (MixinExplosiveProcessor): with a launcher's round's or a hand grenade's blast
     * on a target of the scheme, the hurt deals the ladder's share instead (BalanceArmor.apply, EuphImmunity.drain)
     */
    public static boolean blastAt(com.hbm.explosion.vanillant.ExplosionVNT explosion, ExplosiveDamage.Blast blast, LivingEntity target, boolean struck,
            boolean explosive, java.util.function.Supplier<Boolean> hurt) {
        if (!explosive || explosion == null || target == null || target.level().isClientSide() || !on()) return hurt.get();
        BlastNow b;
        try {
            b = blastOf(explosion, blast, target, struck);
        } catch (RuntimeException e) {
            b = null;
        }
        if (b == null) return hurt.get();
        BlastNow was = BLAST_NOW.get();
        BLAST_NOW.set(b);
        try {
            return hurt.get();
        } finally {
            if (was != null) BLAST_NOW.set(was);
            else BLAST_NOW.remove();
        }
    }

    /** the blast's hurt under way on {@code target} is the ladder's (no part's multiplier on it, NTM's early check passed) */
    public static boolean blastNow(LivingEntity target) {
        BlastNow b = BLAST_NOW.get();
        return b != null && b.target() == target;
    }

    /** a hit the scheme takes, a blast's or a GL-54 fragment's among them (BalanceArmor.covers) */
    static boolean takes(LivingEntity target, DamageSource source) {
        return hit(target, source) != null || blastNow(target) && expl(source) || !Float.isNaN(frag(target, source, false));
    }

    private static boolean expl(DamageSource source) {
        return DamageResistanceHandler.CATEGORY_EXPLOSION.equals(BalanceArmor.category(source));
    }

    private static BlastNow blastOf(com.hbm.explosion.vanillant.ExplosionVNT explosion, ExplosiveDamage.Blast blast, LivingEntity target, boolean struck) {
        State s = state();
        var r = s.blast();
        // balanceArmorBlasts off: the blast is NTM's (BalanceArmor.category), so not the ladder's either
        if (!r.on() || !ReaimTimes.flag("balanceArmorBlasts", true)) return null;
        var armor = armorOf(target, s);
        if (armor == null) return null;
        String id;
        double tier, near;
        Entity owner, projectile;
        if (blast != null) {
            // a launcher's round (its gun in balance.final.blast.launchers): its pair by the shot
            var bullet = ExplosiveDamage.bulletOf(blast);
            id = pairOf(bullet);
            int dot = id == null ? -1 : id.indexOf('.');
            if (dot <= 0) return null;
            var l = r.launchers().get(id.substring(0, dot));
            Double round = r.rounds().get(id.substring(dot + 1));
            if (l == null || round == null) return null;
            tier = BalanceBlastRule.pairTier(l, round, r.cap());
            double t = tier;
            near = s.cache().computeIfAbsent(new CacheKey("blast " + id, armor), k -> (double) BalanceBlastRule.nearLauncher(l, BalanceBlastRule.target(t, armor, s.params(), r)));
            owner = bullet.getOwner();
            projectile = bullet;
        } else {
            // a hand grenade (its filling in balance.final.blast.grenades), not inside a shot's impact
            String filling = GRENADE.get();
            Double t = filling == null || ExplosiveDamage.current() != null ? null : r.grenades().get(filling);
            if (t == null) return null;
            id = "grenade." + filling;
            tier = t;
            near = s.cache().computeIfAbsent(new CacheKey("blast " + id, armor), k -> BalanceBlastRule.nearGrenade(BalanceBlastRule.target(t, armor, s.params(), r), s.params(), r));
            owner = explosion.exploder;
            // a mob's or a machine's grenade: NTM's numbers, as their shots
            if (owner != null && !(owner instanceof net.minecraft.world.entity.player.Player)) return null;
            projectile = null;
        }
        // where the target is: struck, or its box's distance from the blast against NTM's reach (twice the size)
        var box = target.getBoundingBox();
        double dx = Math.max(Math.max(box.minX - explosion.posX, 0), explosion.posX - box.maxX);
        double dy = Math.max(Math.max(box.minY - explosion.posY, 0), explosion.posY - box.maxY);
        double dz = Math.max(Math.max(box.minZ - explosion.posZ, 0), explosion.posZ - box.maxZ);
        double where = BalanceBlastRule.where(struck, Math.sqrt(dx * dx + dy * dy + dz * dz), 2.0 * explosion.size, r.hits());
        double share = BalanceBlastRule.share(near, where);
        // one's own blast: half
        if (owner == target) share *= 0.5;
        return new BlastNow(target, share * (BalanceFinalRule.HP + armor.pool()), id, tier, projectile);
    }

    /**
     * balance.final.mobArmor (player; own: X949): a hit of the scheme on a mob meets the vanilla armour a player in
     * the same pieces would have, the pieces' own; the mob's own armour (the FBI agent's 20 and balance.fbiArmor) took
     * the scheme's damage a second time (an FBI agent in Security lived through 24 hits of the STG-77 its table kills
     * with 9 in the head). The armour and toughness to take off what the mob has, {0, 0} for none
     */
    public static float[] mobOwnArmor(LivingEntity target, DamageSource source) {
        if (target instanceof Player || target.level().isClientSide() || BalanceArmor.category(source) == null || !on()
                || !ReaimTimes.text("balance.final.mobArmor", "player").trim().equalsIgnoreCase("player"))
            return NO_OWN;
        try {
            if (!takes(target, source)) return NO_OWN;
        } catch (RuntimeException e) {
            return NO_OWN;
        }
        return new float[] {own(target, net.minecraft.world.entity.ai.attributes.Attributes.ARMOR),
                own(target, net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS)};
    }

    private static final float[] NO_OWN = {0F, 0F};

    /** the mob's own share of an attribute: its base and balance.fbiArmor's modifier */
    private static float own(LivingEntity target, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
        var a = target.getAttribute(attribute);
        if (a == null) return 0F;
        var fbi = a.getModifier(BalanceKit.FBI_ARMOR);
        return (float) Math.max(0, a.getBaseValue() + (fbi == null ? 0 : fbi.amount()));
    }

    /** a blast's or a GL-54 fragment's take of 20 HP and the pool, or NaN when the hit is neither (the log counts it) */
    private static float blastOrFrag(LivingEntity target, DamageSource source) {
        BlastNow b = BLAST_NOW.get();
        if (b != null && b.target() == target && expl(source)) {
            if (BalanceLog.on()) BalanceLog.hit(target, source, b.projectile(), b.id(), b.tier(), false);
            return (float) b.take();
        }
        return frag(target, source, true);
    }

    /**
     * a GL-54 fragment's (airburst, AP, incendiary; Fable 12.3) take of 20 HP and the pool: a burst whose cone's
     * fragments all hit takes BalanceBlastRule.fragClick; NaN when the hit is not one
     */
    private static float frag(LivingEntity target, DamageSource source, boolean log) {
        if (target.level().isClientSide() || !on()) return Float.NaN;
        String cat = BalanceArmor.category(source);
        if (cat == null || DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat)) return Float.NaN;
        Entity projectile = source.getDirectEntity();
        String id = pairOf(projectile);
        if (id == null || !id.startsWith("gl54.")) return Float.NaN;
        State s = state();
        var r = s.blast();
        String kind = id.substring(5);
        var f = r.frags().get(kind);
        var l = r.launchers().get("gl54");
        Double round = r.rounds().get(kind);
        var spec = acup.gunfeel.gl54.frag.FragSpec.of(acup.gunfeel.gl54.GrenadeKind.of(kind));
        if (!r.on() || f == null || l == null || round == null || spec == null) return Float.NaN;
        var armor = armorOf(target, s);
        if (armor == null) return Float.NaN;
        double tier = BalanceBlastRule.pairTier(l, round, r.cap());
        double role = s.params().roles().getOrDefault("airburst", 1.2);
        double click = s.cache().computeIfAbsent(new CacheKey("frag " + id, armor), k -> BalanceBlastRule.fragClick(l, f, tier, armor, s.params(), role));
        if (log && BalanceLog.on()) BalanceLog.hit(target, source, projectile, id, tier, false);
        // the click is a burst whose cone all hits (the ring's fragments are the side spray, each the same)
        return (float) (click / Math.max(1, spec.cone()));
    }
}
