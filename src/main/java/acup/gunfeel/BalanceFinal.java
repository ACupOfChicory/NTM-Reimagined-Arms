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

    static volatile Map<Entity, Integer> standShots;

    public static float hitAs(Entity projectile, LivingEntity shooter, com.hbm.items.weapon.sedna.BulletConfig from, com.hbm.items.weapon.sedna.BulletConfig as,
            float share) {
        return BalanceFinalBase.hitAs(projectile, shooter == null ? null : EntityBody.of(shooter), from, as, share, ShotsPort.READS, BalanceFinalBase::on);
    }

    public static float shareOf(Entity projectile, LivingEntity shooter, com.hbm.items.weapon.sedna.BulletConfig from,
            com.hbm.items.weapon.sedna.BulletConfig as, float share) {
        return BalanceFinalBase.shareOf(projectile, shooter == null ? null : EntityBody.of(shooter), from, as, share, ShotsPort.READS, BalanceFinalBase::on);
    }

    static void fired(Entity projectile, LivingEntity shooter, net.minecraft.world.item.Item gunItem, com.hbm.items.weapon.sedna.BulletConfig ammo) {
        BalanceFinalBase.fired(projectile, shooter == null ? null : EntityBody.of(shooter), gunItem, ammo, ShotsPort.READS, BalanceFinalBase::on);
    }

    public static void submunition(Entity projectile, LivingEntity thrower) {
        BalanceFinalBase.submunition(projectile, thrower == null ? null : EntityBody.of(thrower), ShotsPort.READS, BalanceFinalBase::on);
    }

    public static void beamSub(Entity beam, LivingEntity thrower, com.hbm.items.weapon.sedna.BulletConfig config) {
        BalanceFinalBase.beamSub(beam, thrower == null ? null : EntityBody.of(thrower), config, ShotsPort.READS, BalanceFinalBase::on);
    }

    static String pairOf(Entity projectile) {
        return BalanceFinalBase.pairOf(projectile);
    }

    static String gunName(net.minecraft.world.item.Item item) {
        return Reads.gunName0(item);
    }

    static String ammoName(com.hbm.items.weapon.sedna.BulletConfig ammo) {
        return Reads.ammoName0(ammo);
    }

    private static boolean againThisTick(LivingEntity target, DamageSource source) {
        boolean irSub = BalanceFinalBase.irSub(source, ShotsPort.READS);
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

    private record BlastHit(int source, int shooter, long tick) {}
    private static final Map<Entity, BlastHit> BLAST_HITS = Collections.synchronizedMap(new WeakHashMap<>());

    // ---------------------------------------------------------------- a hit

    /**
     * the scheme's view of a hit in words, for the stands: its pair, the set it takes on the target and the damage of
     * a click on the body ("stg77.r556_ap on hev, 1.18 a click"); null when the hit is not the scheme's
     */
    public static String describe(LivingEntity target, DamageSource source) {
        return describeOf(hit(target, source));
    }

    /**
     * the hit the scheme takes, or null when it is not the scheme's (blasts, pairs of no line or of the table, targets
     * that are not players and wear nothing of the table)
     */
    static Hit hit(LivingEntity target, DamageSource source) {
        return BalanceFinalBase.hit(target == null ? null : EntityBody.of(target), source, ShotsPort.READS, BalanceFinalBase::on, EuphImmunityBase.NowPort.NOW);
    }

    /** the stand: the damage of one projectile of pair {@code id} on {@code target}'s body (its health's scale), NaN when not the scheme's */
    static float clickOn(LivingEntity target, String id) {
        return clickOn(EntityBody.of(target), id, BalanceOn.ON);
    }

    /**
     * the set the scheme takes on {@code target}: a full set of one kind its own (with its pool), a mixed or incomplete
     * one by the slots' weights (no pool), nothing of the table: bare (players only); null when not the scheme's
     */
    static BalanceFinalRule.Armor armorOf(LivingEntity target, State s) {
        return armorOf(EntityBody.of(target), s, EuphImmunityBase.NowPort.NOW, BalanceOn.ON);
    }

    /**
     * what the scheme leaves of a hit on {@code target} (in place of NTM's threshold and resistance and formula C), or
     * NaN when the hit is not the scheme's
     */
    static float damage(LivingEntity target, DamageSource source) {
        return BalanceFinalBase.damage(target == null ? null : EntityBody.of(target), source, ShotsPort.READS, BalanceFinalBase::on, EuphImmunityBase.NowPort.NOW);
    }


    /** what a hit takes from a full set's pool (EuphImmunity), or NaN when it is not the scheme's */
    static float drain(LivingEntity target, DamageSource source) {
        return BalanceFinalBase.drain(target == null ? null : EntityBody.of(target), source, ShotsPort.READS, BalanceFinalBase::on, EuphImmunityBase.NowPort.NOW);
    }

    /**
     * a shot or a beam the scheme leaves to NTM's numbers: a mob's or a turret's of no pair of the table (owner
     * 2026-11-12: NTM's damage as it is): NTM's threshold and resistance, then vanilla's armour, no formula C
     */
    static boolean ntmShot(LivingEntity target, DamageSource source) {
        return BalanceFinalBase.ntmShot(null, source, ShotsPort.READS, BalanceFinalBase::on);
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

    /** The version's live reads; the pair state is core's. */
    static final class Reads implements acup.gunfeel.host.FinalShots {
        static float headMultiplier() { return ModelHeads.multiplier(true, false); }
        private static LivingEntity living(acup.gunfeel.host.Body b) { return (LivingEntity) EntityBody.entity(b); }
        @Override public Object raw(acup.gunfeel.host.Body b) { return EntityBody.entity(b); }
        @Override public boolean client(acup.gunfeel.host.Body b) { return living(b).level().isClientSide(); }
        @Override public boolean gunsOn() { return BalanceGuns.on(); }
        @Override public Object gunOf(acup.gunfeel.host.Body b) { return BalanceGuns.gunOf(living(b)); }
        @Override public String gunName(Object gun) { return gunName0((net.minecraft.world.item.Item) gun); }
        @Override public String ammoName(Object ammo) { return ammoName0((com.hbm.items.weapon.sedna.BulletConfig) ammo); }
        @Override public float as(Object gun, Object from, Object as) { return BalanceGuns.as((net.minecraft.world.item.Item) gun, (com.hbm.items.weapon.sedna.BulletConfig) from, (com.hbm.items.weapon.sedna.BulletConfig) as); }
        @Override public String loadedAmmo(acup.gunfeel.host.Body b, Object gun) { return loadedAmmo0(living(b), (net.minecraft.world.item.Item) gun); }
        @Override public long time(Object entity) { return ((Entity) entity).level().getGameTime(); }
        @Override public boolean irConfig(Object config) { return config == com.hbm.items.weapon.sedna.factory.XFactoryEnergy.energy_tesla_ir_sub; }
        @Override public Object direct(Object source) { return ((DamageSource) source).getDirectEntity(); }
        @Override public Object entity(Object source) { return ((DamageSource) source).getEntity(); }
        @Override public String category(Object source) { return BalanceArmor.category((DamageSource) source); }
        @Override public Object hitProjectile(acup.gunfeel.host.Body b, Object source, String cat) {
            if (DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat)) {
                if (!BalanceArmor.bulletBlastNow() || ExplosiveDamage.struckNow() != living(b)) return NO_PROJECTILE;
                return ExplosiveDamage.bulletNow();
            }
            return ((DamageSource) source).getDirectEntity();
        }
        @Override public String fallbackPair(Object source, String cat) {
            return BalanceArmor.vntBlastNow() && !DamageResistanceHandler.CATEGORY_EXPLOSION.equals(cat)
                    ? BalanceFinalBase.blastPair(source, this) : null;
        }
        @Override public boolean vnt() { return BalanceArmor.vntBlastNow(); }
        @Override public boolean again(acup.gunfeel.host.Body b, Object source) { return againThisTick(living(b), (DamageSource) source); }
        @Override public float blastOrFrag(acup.gunfeel.host.Body b, Object source) { return BalanceFinal.blastOrFrag(living(b), (DamageSource) source); }
        @Override public boolean mobShooter(Object source) { return ((DamageSource) source).getEntity() instanceof LivingEntity shooter && !(shooter instanceof Player); }
        @Override public double distance(acup.gunfeel.host.Body b, Object source) { return ((DamageSource) source).getEntity().position().distanceTo(living(b).position()); }
        @Override public boolean logOn() { return BalanceLog.on(); }
        @Override public void logShot(Object projectile, acup.gunfeel.host.Body shooter, Object gun) { BalanceLog.shot((Entity) projectile, living(shooter), (net.minecraft.world.item.Item) gun); }
        @Override public void logHit(acup.gunfeel.host.Body b, Object source, String id, double tier, boolean head) { BalanceLog.hit(living(b), (DamageSource) source, null, id, tier, head); }
        @Override public void countShot(acup.gunfeel.host.Body shooter) {
            var count = standShots;
            if (count != null) count.merge(living(shooter), 1, Integer::sum);
        }
        @Override public float headCap() { return DamageCap.limits().head(); }
        @Override public boolean balanceOn() { return BalanceOn.ON.getAsBoolean(); }
        @Override public String worn(acup.gunfeel.host.Body b, int slot) { return BalanceArmorBase.setOfWorn(b, slot); }
    static String gunName0(net.minecraft.world.item.Item item) {
        String g = BalanceGuns.gunName(item);
        if (g != null) return g;
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        return id != null && id.getNamespace().equals(acup.gunfeel.gl54.Gl54Items.NAMESPACE) && id.getPath().equals("gun_gl54") ? "gl54" : null;
    }
    static String ammoName0(com.hbm.items.weapon.sedna.BulletConfig ammo) {
        if (ammo instanceof acup.gunfeel.gl54.GrenadeType g) {
            var k = g.grenade();
            return k != null ? k.key : g.kind;
        }
        return BalanceGuns.ammoName(ammo);
    }
    private static String loadedAmmo0(LivingEntity shooter, net.minecraft.world.item.Item gunItem) {
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
    }
}
