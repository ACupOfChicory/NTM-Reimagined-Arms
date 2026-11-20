package acup.gunfeel.host;

import acup.gunfeel.Materials;

/** Live access to a bullet's path; points, hits and levels belong to the version. */
public interface PierceShots {
    /** The hit's point (26.2: BlockHitResult.getLocation). */
    Object location(Object hit);
    /** Distance from a point to the path's start (26.2: Vec3.distanceTo). */
    double distance(Object point, Object from);
    /** The bullet's level, read once even with no debt (26.2: MK4.level). */
    Object level(Object bullet);
    /** Whether the bullet is gone (26.2: MK4.isRemoved). */
    boolean removed(Object bullet);
    /** Water's damage factor at entry (26.2: WaterTick.k with in.getLocation). */
    float water(Object bullet, Object in);
    /** The bullet's current damage (26.2: MK4.damage). */
    float damage(Object bullet);
    /** Replace the bullet's damage (26.2: MK4.damage). */
    void damage(Object bullet, float value);
    /** Pay for a block with the live round (26.2: MK4.config, then PassableClip.pierced). */
    float pierced(Object level, Object in, Object out, float damage, Object bullet, Materials.Penetration penetration);
    /** Stop at entry, then remove the bullet (26.2: getLocation, setPos, discard). */
    void stopAt(Object bullet, Object in);
    /** The diagnostic's shot id (26.2: MK4.getId). */
    int id(Object bullet);
    /** Log a rewind when enabled by the caller (26.2: damage, TestLog.s). */
    void logLater(int id, int blocks, Object bullet);
    /** Log a repayment when enabled by the caller (26.2: getId, block state, hit, material, damage, TestLog.s). */
    void logBlock(Object bullet, Object level, Object in, float left);

    PierceShots MISSING = new PierceShots() {
        @Override public Object location(Object hit) { throw new UnsupportedOperationException("no PierceShots here"); }
        @Override public double distance(Object point, Object from) { throw new UnsupportedOperationException("no PierceShots here"); }
        @Override public Object level(Object bullet) { throw new UnsupportedOperationException("no PierceShots here"); }
        @Override public boolean removed(Object bullet) { throw new UnsupportedOperationException("no PierceShots here"); }
        @Override public float water(Object bullet, Object in) { throw new UnsupportedOperationException("no PierceShots here"); }
        @Override public float damage(Object bullet) { throw new UnsupportedOperationException("no PierceShots here"); }
        @Override public void damage(Object bullet, float value) { throw new UnsupportedOperationException("no PierceShots here"); }
        @Override public float pierced(Object level, Object in, Object out, float damage, Object bullet, Materials.Penetration penetration) { throw new UnsupportedOperationException("no PierceShots here"); }
        @Override public void stopAt(Object bullet, Object in) { throw new UnsupportedOperationException("no PierceShots here"); }
        @Override public int id(Object bullet) { throw new UnsupportedOperationException("no PierceShots here"); }
        @Override public void logLater(int id, int blocks, Object bullet) { throw new UnsupportedOperationException("no PierceShots here"); }
        @Override public void logBlock(Object bullet, Object level, Object in, float left) { throw new UnsupportedOperationException("no PierceShots here"); }
    };
}
