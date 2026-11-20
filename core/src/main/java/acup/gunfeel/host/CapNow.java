package acup.gunfeel.host;

/** Live reads of the cap's hurt; shield properties are opaque and kept for this hit only. */
public interface CapNow {
    /** The raw entity used as an identity key (26.2: EntityBody.entity). */
    Object entity(Body target);

    /** Whether its level is client-side (26.2: entity.level().isClientSide()). */
    boolean client(Body target);

    /** Its level's current tick (26.2: entity.level().getGameTime()). */
    long time(Body target);

    /** Its shield before a hurt (26.2: HbmPlayerProps.getData(target).shield). */
    float shield(Body target);

    /** Run once synchronously with one props object, then return shield through it (26.2: getData, shield, +=). */
    <T> T withShield(Body target, ShieldUse<T> use);

    record ShieldBack<T>(T value, boolean give, float amount) {}

    interface ShieldUse<T> {
        /** Apply the cap with opaque props and their shield (26.2: the same HbmPlayerProps, no second getData). */
        ShieldBack<T> take(Object props, float shield);
    }

    /** Consume the armour's raw amount (26.2: BalanceArmor.takeAmount, clearing amountOf). */
    float takeAmount(Body target, float fallback);

    /** Whether the final scheme handles this hit (26.2: BalanceFinal.hit != null). */
    boolean schemeHit(Body target, Object hurt);

    CapNow MISSING = new CapNow() {
        @Override public Object entity(Body b) { throw new UnsupportedOperationException("no CapNow here"); }
        @Override public boolean client(Body b) { throw new UnsupportedOperationException("no CapNow here"); }
        @Override public long time(Body b) { throw new UnsupportedOperationException("no CapNow here"); }
        @Override public float shield(Body b) { throw new UnsupportedOperationException("no CapNow here"); }
        @Override public <T> T withShield(Body b, ShieldUse<T> use) { throw new UnsupportedOperationException("no CapNow here"); }
        @Override public float takeAmount(Body b, float f) { throw new UnsupportedOperationException("no CapNow here"); }
        @Override public boolean schemeHit(Body b, Object h) { throw new UnsupportedOperationException("no CapNow here"); }
    };
}
