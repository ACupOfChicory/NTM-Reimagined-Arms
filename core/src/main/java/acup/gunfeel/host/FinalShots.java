package acup.gunfeel.host;

/** Live reads and version callbacks of the final scheme; no shot state. */
public interface FinalShots {
    Object NO_PROJECTILE = new Object();
    boolean client(Body b);
    boolean gunsOn();
    Object gunOf(Body b);
    String gunName(Object gun);
    String ammoName(Object ammo);
    float as(Object gun, Object from, Object as);
    String loadedAmmo(Body b, Object gun);
    long time(Object entity);
    boolean irConfig(Object config);
    Object direct(Object source);
    Object entity(Object source);
    String category(Object source);
    Object hitProjectile(Body b, Object source, String cat);
    String fallbackPair(Object source, String cat);
    boolean vnt();
    boolean again(Body b, Object source);
    float blastOrFrag(Body b, Object source);
    boolean mobShooter(Object source);
    double distance(Body b, Object source);
    boolean logOn();
    void logShot(Object projectile, Body shooter, Object gun);
    void logHit(Body b, Object source, String id, double tier, boolean head);
    void countShot(Body shooter);
    float headCap();
    boolean balanceOn();
    String worn(Body b, int slot);
    Object raw(Body b);

    FinalShots MISSING = new FinalShots() {
        @Override public boolean client(Body b) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public boolean gunsOn() { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public Object gunOf(Body b) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public String gunName(Object gun) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public String ammoName(Object ammo) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public float as(Object gun, Object from, Object as) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public String loadedAmmo(Body b, Object gun) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public long time(Object entity) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public boolean irConfig(Object config) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public Object direct(Object source) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public Object entity(Object source) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public String category(Object source) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public Object hitProjectile(Body b, Object source, String cat) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public String fallbackPair(Object source, String cat) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public boolean vnt() { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public boolean again(Body b, Object source) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public float blastOrFrag(Body b, Object source) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public boolean mobShooter(Object source) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public double distance(Body b, Object source) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public boolean logOn() { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public void logShot(Object projectile, Body shooter, Object gun) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public void logHit(Body b, Object source, String id, double tier, boolean head) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public void countShot(Body shooter) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public float headCap() { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public boolean balanceOn() { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public String worn(Body b, int slot) { throw new UnsupportedOperationException("no FinalShots here"); }
        @Override public Object raw(Body b) { throw new UnsupportedOperationException("no FinalShots here"); }
    };
}
