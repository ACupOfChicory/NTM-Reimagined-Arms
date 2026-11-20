package acup.gunfeel;

import java.util.Map;

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

    public static void grenade(com.hbm.entity.grenade.EntityGrenadeUniversal grenade, Runnable explode) {
        BalanceFinalBase.grenade(grenade, explode, ShotsPort.READS, BalanceFinalBase::on);
    }

    public static boolean blastAt(com.hbm.explosion.vanillant.ExplosionVNT explosion, ExplosiveDamage.Blast blast, LivingEntity target, boolean struck,
            boolean explosive, java.util.function.Supplier<Boolean> hurt) {
        return BalanceFinalBase.blastAt(explosion, blast, target == null ? null : EntityBody.of(target), struck, explosive, hurt,
                ShotsPort.READS, BalanceFinalBase::on, EuphImmunityBase.NowPort.NOW);
    }

    public static boolean blastNow(LivingEntity target) {
        return BalanceFinalBase.blastNow(target == null ? null : EntityBody.of(target), ShotsPort.READS);
    }

    static boolean takes(LivingEntity target, DamageSource source) {
        return BalanceFinalBase.takes(EntityBody.of(target), source, ShotsPort.READS, BalanceFinalBase::on, EuphImmunityBase.NowPort.NOW);
    }

    public static float[] mobOwnArmor(LivingEntity target, DamageSource source) {
        return BalanceFinalBase.mobOwnArmor(EntityBody.of(target), source, ShotsPort.READS, BalanceFinalBase::on, EuphImmunityBase.NowPort.NOW);
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
        @Override public boolean vnt() { return BalanceArmor.vntBlastNow(); }
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
        @Override public boolean bulletBlast() { return BalanceArmor.bulletBlastNow(); }
        @Override public Object struckNow() { return ExplosiveDamage.struckNow(); }
        @Override public Object bulletNow() { return ExplosiveDamage.bulletNow(); }
        @Override public boolean explosion(Object source) { return ((DamageSource) source).is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION); }
        @Override public int entityId(Object entity) { return ((Entity) entity).getId(); }
        @Override public boolean rawClient(Object entity) { return ((Entity) entity).level().isClientSide(); }
        @Override public String grenadeFilling(Object grenade) { return ((com.hbm.entity.grenade.EntityGrenadeUniversal) grenade).getFilling().name().toLowerCase(java.util.Locale.ROOT); }
        @Override public Object boundBullet(Object blast) { return ExplosiveDamage.bulletOf((ExplosiveDamage.Blast) blast); }
        @Override public Object projectileOwner(Object bullet) { return ((com.hbm.entity.projectile.EntityBulletBaseMK4) bullet).getOwner(); }
        @Override public Object currentImpact() { return ExplosiveDamage.current(); }
        @Override public Object exploder(Object explosion) { return ((com.hbm.explosion.vanillant.ExplosionVNT) explosion).exploder; }
        @Override public boolean playerEntity(Object entity) { return entity instanceof Player; }
        @Override public double boxDistance(acup.gunfeel.host.Body target, Object value) {
            var explosion = (com.hbm.explosion.vanillant.ExplosionVNT) value;
            var box = living(target).getBoundingBox();
            double dx = Math.max(Math.max(box.minX - explosion.posX, 0), explosion.posX - box.maxX);
            double dy = Math.max(Math.max(box.minY - explosion.posY, 0), explosion.posY - box.maxY);
            double dz = Math.max(Math.max(box.minZ - explosion.posZ, 0), explosion.posZ - box.maxZ);
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        @Override public double blastSize(Object explosion) { return ((com.hbm.explosion.vanillant.ExplosionVNT) explosion).size; }
        @Override public Object ownAttribute(acup.gunfeel.host.Body target, int which) { return living(target).getAttribute(which == 0 ? net.minecraft.world.entity.ai.attributes.Attributes.ARMOR : net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS); }
        @Override public Object ownModifier(Object attribute) { return ((net.minecraft.world.entity.ai.attributes.AttributeInstance) attribute).getModifier(BalanceKit.FBI_ARMOR); }
        @Override public double attributeBase(Object attribute) { return ((net.minecraft.world.entity.ai.attributes.AttributeInstance) attribute).getBaseValue(); }
        @Override public double modifierAmount(Object modifier) { return ((net.minecraft.world.entity.ai.attributes.AttributeModifier) modifier).amount(); }
        @Override public void logBlastHit(acup.gunfeel.host.Body b, Object source, Object projectile, String id, double tier, boolean head) { BalanceLog.hit(living(b), (DamageSource) source, (Entity) projectile, id, tier, head); }
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
