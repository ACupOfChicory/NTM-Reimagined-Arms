package acup.gunfeel.host;

/** Live bullet, hit and explosion access for the explosive damage context. */
public interface ExplosiveShots {
    /** The bullet's side (26.2: MK4.level().isClientSide). */
    boolean client(Object bullet);
    /** The bullet's config (26.2: MK4.config). */
    Object config(Object bullet);
    /** The thrower (26.2: MK4.getThrower). */
    Object thrower(Object bullet);
    /** The bullet's level (26.2: MK4.level). */
    Object level(Object bullet);
    /** The explosion's world (26.2: ExplosionVNT.world). */
    Object world(Object explosion);
    /** The explosion's initiator (26.2: ExplosionVNT.exploder). */
    Object exploder(Object explosion);
    /** The creature hit, or null (26.2: EntityHitResult.getEntity). */
    Object struck(Object impact);
    /** A launcher's round (26.2: ExplosiveAmmo.heavy). */
    boolean heavy(Object config);
    /** A rocket (26.2: ExplosiveAmmo.rocket). */
    boolean rocket(Object config);
    /** The bullet is gone (26.2: MK4.isRemoved). */
    boolean removed(Object bullet);
    /** Send a block impact hole if eligible (26.2: WaterTick.surfaceHit, ImpactHolePayload, PacketDistributor). */
    boolean hole(Object bullet,Object hit);
    /** A living entity (26.2: instanceof LivingEntity). */
    boolean living(Object target);
    /** The target is alive (26.2: LivingEntity.isAlive). */
    boolean alive(Object target);
    /** Apply NTM damage (26.2: EntityDamageUtil.attackEntityFromNT). */
    boolean attack(Object target,Object source,float damage,boolean first,boolean second,double knockback,float threshold,float piercing);
    /** Pay missing contact damage (26.2: BulletConfig.getDamage, ExplosiveDamage.attack). */
    void contact(Object bullet,Object target,float damage);

    ExplosiveShots MISSING=new ExplosiveShots() {
        @Override public boolean client(Object bullet){throw new UnsupportedOperationException("no ExplosiveShots here");}
        @Override public Object config(Object bullet){throw new UnsupportedOperationException("no ExplosiveShots here");}
        @Override public Object thrower(Object bullet){throw new UnsupportedOperationException("no ExplosiveShots here");}
        @Override public Object level(Object bullet){throw new UnsupportedOperationException("no ExplosiveShots here");}
        @Override public Object world(Object explosion){throw new UnsupportedOperationException("no ExplosiveShots here");}
        @Override public Object exploder(Object explosion){throw new UnsupportedOperationException("no ExplosiveShots here");}
        @Override public Object struck(Object impact){throw new UnsupportedOperationException("no ExplosiveShots here");}
        @Override public boolean heavy(Object config){throw new UnsupportedOperationException("no ExplosiveShots here");}
        @Override public boolean rocket(Object config){throw new UnsupportedOperationException("no ExplosiveShots here");}
        @Override public boolean removed(Object bullet){throw new UnsupportedOperationException("no ExplosiveShots here");}
        @Override public boolean hole(Object bullet,Object hit){throw new UnsupportedOperationException("no ExplosiveShots here");}
        @Override public boolean living(Object target){throw new UnsupportedOperationException("no ExplosiveShots here");}
        @Override public boolean alive(Object target){throw new UnsupportedOperationException("no ExplosiveShots here");}
        @Override public boolean attack(Object target,Object source,float damage,boolean first,boolean second,double knockback,float threshold,float piercing){throw new UnsupportedOperationException("no ExplosiveShots here");}
        @Override public void contact(Object bullet,Object target,float damage){throw new UnsupportedOperationException("no ExplosiveShots here");}
    };
}
