package acup.gunfeel.host;

/** Live reads and version callbacks of the final scheme; no shot state. */
public interface FinalShots {
    /** Client side (26.2: LivingEntity.level().isClientSide). */
    boolean client(Body b);
    /** Gun balance switch (26.2: BalanceGuns.on). */
    boolean gunsOn();
    /** Held gun (26.2: BalanceGuns.gunOf). */
    Object gunOf(Body b);
    /** Scheme gun name (26.2: Reads.gunName0). */
    String gunName(Object gun);
    /** Scheme ammo name (26.2: Reads.ammoName0). */
    String ammoName(Object ammo);
    /** Ammo ratio (26.2: BalanceGuns.as). */
    float as(Object gun, Object from, Object as);
    /** Loaded ammo name (26.2: Reads.loadedAmmo0). */
    String loadedAmmo(Body b, Object gun);
    /** World tick (26.2: Entity.level().getGameTime). */
    long time(Object entity);
    /** IR submunition identity (26.2: XFactoryEnergy.energy_tesla_ir_sub). */
    boolean irConfig(Object config);
    /** Direct entity (26.2: DamageSource.getDirectEntity). */
    Object direct(Object source);
    /** Source owner (26.2: DamageSource.getEntity). */
    Object entity(Object source);
    /** Damage category (26.2: BalanceArmor.category). */
    String category(Object source);
    /** VNT context (26.2: BalanceArmor.vntBlastNow). */
    boolean vnt();
    /** Mob owner (26.2: DamageSource.getEntity and entity types). */
    boolean mobShooter(Object source);
    /** Shooter distance (26.2: Entity.position().distanceTo). */
    double distance(Body b, Object source);
    /** Logging switch (26.2: BalanceLog.on). */
    boolean logOn();
    /** Shot event (26.2: BalanceLog.shot). */
    void logShot(Object projectile, Body shooter, Object gun);
    /** Plain hit event (26.2: BalanceLog.hit with null projectile). */
    void logHit(Body b, Object source, String id, double tier, boolean head);
    /** Stand count (26.2: standShots.merge). */
    void countShot(Body shooter);
    /** Head limit (26.2: DamageCap.limits().head). */
    float headCap();
    /** Balance switch (26.2: BalanceOn.ON). */
    boolean balanceOn();
    /** Armor slot name (26.2: BalanceArmorBase.setOfWorn). */
    String worn(Body b, int slot);
    /** Raw entity key (26.2: EntityBody.entity). */
    Object raw(Body b);

    /** Bullet blast context (26.2: BalanceArmor.bulletBlastNow). */
    boolean bulletBlast();
    /** Impact target (26.2: ExplosiveDamage.struckNow). */
    Object struckNow();
    /** Impact bullet (26.2: ExplosiveDamage.bulletNow). */
    Object bulletNow();
    /** Explosion tag (26.2: DamageSource.is). */
    boolean explosion(Object source);
    /** Entity ID (26.2: Entity.getId). */
    int entityId(Object entity);
    /** Raw entity side (26.2: Entity.level().isClientSide). */
    boolean rawClient(Object entity);
    /** Filling name (26.2: EntityGrenadeUniversal.getFilling().name). */
    String grenadeFilling(Object grenade);
    /** Bound bullet (26.2: ExplosiveDamage.bulletOf). */
    Object boundBullet(Object blast);
    /** Bullet owner (26.2: EntityBulletBaseMK4.getOwner). */
    Object projectileOwner(Object bullet);
    /** Impact context (26.2: ExplosiveDamage.current). */
    Object currentImpact();
    /** Explosion owner (26.2: ExplosionVNT.exploder). */
    Object exploder(Object explosion);
    /** Player type (26.2: instanceof Player). */
    boolean playerEntity(Object entity);
    /** Box distance (26.2: LivingEntity.getBoundingBox and ExplosionVNT.posX/Y/Z). */
    double boxDistance(Body target, Object value);
    /** Explosion size (26.2: ExplosionVNT.size). */
    double blastSize(Object explosion);
    /** Own attribute (26.2: LivingEntity.getAttribute). */
    Object ownAttribute(Body target, int which);
    /** FBI modifier (26.2: AttributeInstance.getModifier). */
    Object ownModifier(Object attribute);
    /** Base value (26.2: AttributeInstance.getBaseValue). */
    double attributeBase(Object attribute);
    /** Modifier amount (26.2: AttributeModifier.amount). */
    double modifierAmount(Object modifier);
    /** Blast hit event (26.2: BalanceLog.hit with projectile). */
    void logBlastHit(Body b, Object source, Object projectile, String id, double tier, boolean head);

    FinalShots MISSING = new FinalShots() {
        /** Client side (26.2: LivingEntity.level().isClientSide). */
        @Override public boolean client(Body b) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Gun balance switch (26.2: BalanceGuns.on). */
        @Override public boolean gunsOn() { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Held gun (26.2: BalanceGuns.gunOf). */
        @Override public Object gunOf(Body b) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Scheme gun name (26.2: Reads.gunName0). */
        @Override public String gunName(Object gun) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Scheme ammo name (26.2: Reads.ammoName0). */
        @Override public String ammoName(Object ammo) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Ammo ratio (26.2: BalanceGuns.as). */
        @Override public float as(Object gun, Object from, Object as) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Loaded ammo name (26.2: Reads.loadedAmmo0). */
        @Override public String loadedAmmo(Body b, Object gun) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** World tick (26.2: Entity.level().getGameTime). */
        @Override public long time(Object entity) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** IR submunition identity (26.2: XFactoryEnergy.energy_tesla_ir_sub). */
        @Override public boolean irConfig(Object config) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Direct entity (26.2: DamageSource.getDirectEntity). */
        @Override public Object direct(Object source) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Source owner (26.2: DamageSource.getEntity). */
        @Override public Object entity(Object source) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Damage category (26.2: BalanceArmor.category). */
        @Override public String category(Object source) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** VNT context (26.2: BalanceArmor.vntBlastNow). */
        @Override public boolean vnt() { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Mob owner (26.2: DamageSource.getEntity and entity types). */
        @Override public boolean mobShooter(Object source) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Shooter distance (26.2: Entity.position().distanceTo). */
        @Override public double distance(Body b, Object source) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Logging switch (26.2: BalanceLog.on). */
        @Override public boolean logOn() { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Shot event (26.2: BalanceLog.shot). */
        @Override public void logShot(Object projectile, Body shooter, Object gun) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Plain hit event (26.2: BalanceLog.hit with null projectile). */
        @Override public void logHit(Body b, Object source, String id, double tier, boolean head) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Stand count (26.2: standShots.merge). */
        @Override public void countShot(Body shooter) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Head limit (26.2: DamageCap.limits().head). */
        @Override public float headCap() { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Balance switch (26.2: BalanceOn.ON). */
        @Override public boolean balanceOn() { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Armor slot name (26.2: BalanceArmorBase.setOfWorn). */
        @Override public String worn(Body b, int slot) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Raw entity key (26.2: EntityBody.entity). */
        @Override public Object raw(Body b) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Bullet blast context (26.2: BalanceArmor.bulletBlastNow). */
        @Override public boolean bulletBlast() { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Impact target (26.2: ExplosiveDamage.struckNow). */
        @Override public Object struckNow() { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Impact bullet (26.2: ExplosiveDamage.bulletNow). */
        @Override public Object bulletNow() { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Explosion tag (26.2: DamageSource.is). */
        @Override public boolean explosion(Object source) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Entity ID (26.2: Entity.getId). */
        @Override public int entityId(Object entity) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Raw entity side (26.2: Entity.level().isClientSide). */
        @Override public boolean rawClient(Object entity) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Filling name (26.2: EntityGrenadeUniversal.getFilling().name). */
        @Override public String grenadeFilling(Object grenade) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Bound bullet (26.2: ExplosiveDamage.bulletOf). */
        @Override public Object boundBullet(Object blast) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Bullet owner (26.2: EntityBulletBaseMK4.getOwner). */
        @Override public Object projectileOwner(Object bullet) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Impact context (26.2: ExplosiveDamage.current). */
        @Override public Object currentImpact() { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Explosion owner (26.2: ExplosionVNT.exploder). */
        @Override public Object exploder(Object explosion) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Player type (26.2: instanceof Player). */
        @Override public boolean playerEntity(Object entity) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Box distance (26.2: LivingEntity.getBoundingBox and ExplosionVNT.posX/Y/Z). */
        @Override public double boxDistance(Body target, Object value) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Explosion size (26.2: ExplosionVNT.size). */
        @Override public double blastSize(Object explosion) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Own attribute (26.2: LivingEntity.getAttribute). */
        @Override public Object ownAttribute(Body target, int which) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** FBI modifier (26.2: AttributeInstance.getModifier). */
        @Override public Object ownModifier(Object attribute) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Base value (26.2: AttributeInstance.getBaseValue). */
        @Override public double attributeBase(Object attribute) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Modifier amount (26.2: AttributeModifier.amount). */
        @Override public double modifierAmount(Object modifier) { throw new UnsupportedOperationException("no FinalShots here"); }
        /** Blast hit event (26.2: BalanceLog.hit with projectile). */
        @Override public void logBlastHit(Body b, Object source, Object projectile, String id, double tier, boolean head) { throw new UnsupportedOperationException("no FinalShots here"); }
    };
}
