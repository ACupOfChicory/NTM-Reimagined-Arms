package acup.gunfeel.ai;

/**
 * Radiation on the mob AI's soldiers: NTM lets a player's armour keep radiation out (ContaminationUtil
 * .calculateRadiationMod: 10 to the minus its HazmatRegistry resistance, a full DNS set 5) but takes every other mob's
 * armour as none (the factor 1). A soldier is let off as a player is (owner 2026-11-17: one in DNS died of a nuclear
 * .50's radiation in seconds); other mobs stay as NTM has them (MixinAiRadiation).
 */
public final class AiRadiation {
    private AiRadiation() {
    }

    /** the part of the radiation that gets through armour of NTM's resistance {@code resistance}, as a player's */
    public static double mod(double resistance) {
        return Math.pow(10.0, -resistance);
    }
}
