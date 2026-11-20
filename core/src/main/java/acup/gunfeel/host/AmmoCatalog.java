package acup.gunfeel.host;

/** Live round identities for the mod's matching rules. */
public interface AmmoCatalog {
    /** Duplex 5.56 identity (26.2: Duplex.r556). */
    Object duplex556();
    /** Duplex 7.62 identity (26.2: Duplex.r762). */
    Object duplex762();
    /** Whether this is the rubber slug (26.2: RubberSlug.is). */
    boolean rubber(Object round);
    /** Shock shell identity (26.2: ShockAmmo.config). */
    Object shock();
    /** Whether this is the nuclear .50 (26.2: NuclearBmg.is). */
    boolean nuclear(Object round);
    /** NTM 5.56 SP identity (26.2: XFactory556mm.r556_sp). */
    Object sp556();
    /** NTM 7.62 SP identity (26.2: XFactory762mm.r762_sp). */
    Object sp762();
    /** NTM 12 gauge slug identity (26.2: XFactory12ga.g12_slug). */
    Object slug();
    /** NTM .50 SP identity (26.2: XFactory50.bmg50_sp). */
    Object sp50();

    AmmoCatalog MISSING = new AmmoCatalog() {
        @Override public Object duplex556() { throw new UnsupportedOperationException("no AmmoCatalog here"); }
        @Override public Object duplex762() { throw new UnsupportedOperationException("no AmmoCatalog here"); }
        @Override public boolean rubber(Object round) { throw new UnsupportedOperationException("no AmmoCatalog here"); }
        @Override public Object shock() { throw new UnsupportedOperationException("no AmmoCatalog here"); }
        @Override public boolean nuclear(Object round) { throw new UnsupportedOperationException("no AmmoCatalog here"); }
        @Override public Object sp556() { throw new UnsupportedOperationException("no AmmoCatalog here"); }
        @Override public Object sp762() { throw new UnsupportedOperationException("no AmmoCatalog here"); }
        @Override public Object slug() { throw new UnsupportedOperationException("no AmmoCatalog here"); }
        @Override public Object sp50() { throw new UnsupportedOperationException("no AmmoCatalog here"); }
    };
}
