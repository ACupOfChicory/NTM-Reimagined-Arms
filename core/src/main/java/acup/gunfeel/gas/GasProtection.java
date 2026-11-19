package acup.gunfeel.gas;

/**
 * The class of protection that keeps a {@link GasKind} out: a mask against what one breathes in, a mask and a suit
 * against what blisters the skin. Core's own names for NTM's hazard classes of the same name; each version's adapter
 * maps them onto its NTM (26.2: {@code GasServer.hazard}).
 */
public enum GasProtection {
    /** NTM's {@code GAS_LUNG}: chlorine, phosgene, the acid. */
    GAS_LUNG,
    /** NTM's {@code GAS_BLISTERING}: mustard. */
    GAS_BLISTERING
}
