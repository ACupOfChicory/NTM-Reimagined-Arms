package acup.gunfeel.host;

/** Live access to the two bullets of a Duplex shot. */
public interface DuplexShots {
    /** Whether this is a Duplex bullet (26.2: MK4 and Duplex.is(config)). */
    boolean duplex(Object bullet);
    /** The round's identity (26.2: MK4.config). */
    Object config(Object bullet);
    /** The shooter's identity (26.2: MK4.getThrower). */
    Object thrower(Object bullet);
    /** Motion along X (26.2: MK4.getDeltaMovement().x). */
    double mx(Object bullet);
    /** Motion along Y (26.2: MK4.getDeltaMovement().y). */
    double my(Object bullet);
    /** Motion along Z (26.2: MK4.getDeltaMovement().z). */
    double mz(Object bullet);
    /** Position along X (26.2: MK4.position().x). */
    double px(Object bullet);
    /** Position along Y (26.2: MK4.position().y). */
    double py(Object bullet);
    /** Position along Z (26.2: MK4.position().z). */
    double pz(Object bullet);
    /** A normal sample from this bullet (26.2: MK4.getRandom().nextGaussian()). */
    double gaussian(Object bullet);
    /** Write motion, rotations, position and history in order (26.2: MK4 setters and old-coordinate fields). */
    void write(Object rear, double mx, double my, double mz, float yaw, float pitch, double ax, double ay, double az);

    DuplexShots MISSING = new DuplexShots() {
        @Override public boolean duplex(Object bullet) { throw new UnsupportedOperationException("no DuplexShots here"); }
        @Override public Object config(Object bullet) { throw new UnsupportedOperationException("no DuplexShots here"); }
        @Override public Object thrower(Object bullet) { throw new UnsupportedOperationException("no DuplexShots here"); }
        @Override public double mx(Object bullet) { throw new UnsupportedOperationException("no DuplexShots here"); }
        @Override public double my(Object bullet) { throw new UnsupportedOperationException("no DuplexShots here"); }
        @Override public double mz(Object bullet) { throw new UnsupportedOperationException("no DuplexShots here"); }
        @Override public double px(Object bullet) { throw new UnsupportedOperationException("no DuplexShots here"); }
        @Override public double py(Object bullet) { throw new UnsupportedOperationException("no DuplexShots here"); }
        @Override public double pz(Object bullet) { throw new UnsupportedOperationException("no DuplexShots here"); }
        @Override public double gaussian(Object bullet) { throw new UnsupportedOperationException("no DuplexShots here"); }
        @Override public void write(Object rear, double mx, double my, double mz, float yaw, float pitch, double ax, double ay, double az) {
            throw new UnsupportedOperationException("no DuplexShots here");
        }
    };
}
