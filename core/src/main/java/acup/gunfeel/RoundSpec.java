package acup.gunfeel;

/**
 * A round's numbers as core's ballistics reads them, filled in by the version from its NTM's round (on 26.2 from
 * BulletConfig: RoundSpecs.fill, beside the ballistics that call core) into an object it reuses. Not a whole picture of NTM's
 * round: a field comes with the cut of core that asks for it. No round (a bullet without NTM's config): all 0.
 */
public final class RoundSpec {
    /** NTM's armorThresholdNegation: the armour threshold the round takes off (blocks: their material's) */
    public float armorThresholdNegation;
    /** NTM's armorPiercingPercent: the share of resistance the round goes through */
    public float armorPiercingPercent;

    /** no round: every number 0 */
    public RoundSpec clear() {
        armorThresholdNegation = 0F;
        armorPiercingPercent = 0F;
        return this;
    }
}
