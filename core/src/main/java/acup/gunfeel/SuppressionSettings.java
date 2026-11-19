package acup.gunfeel;

/**
 * Suppression's server settings (ntm-gunfeel-server.properties; a client reads the server's while connected), as the
 * server's side reads them (26.2: SuppressionServer, which tells the players its blasts) and the AI mobs' scale
 * (AiProneBase).
 */
public class SuppressionSettings {
    protected SuppressionSettings() {
    }

    // ---- the server's settings (ntm-gunfeel-server.properties; a client reads the server's while connected)

    public static final String KEY = "suppression";

    public static boolean on() {
        return ReaimTimes.flag(KEY, true);
    }

    // each source's share of its force (0: it does not count) and the farthest it counts, blocks
    public static float passShare() {
        return Math.max(0F, ReaimTimes.number("suppressionPass", 1F));
    }

    public static float impactShare() {
        return Math.max(0F, ReaimTimes.number("suppressionImpact", 1F));
    }

    public static float blastShare() {
        return Math.max(0F, ReaimTimes.number("suppressionBlast", 1F));
    }

    public static double passRange() {
        return Math.max(0F, ReaimTimes.number("suppressionPassRange", 3.5F));
    }

    public static double impactRange() {
        return Math.max(0F, ReaimTimes.number("suppressionImpactRange", 8F));
    }

    public static double blastRange() {
        return Math.max(0F, ReaimTimes.number("suppressionBlastRange", 14F));
    }

    // seconds from a full scale to none
    public static float recoverySeconds() {
        return Math.max(0.5F, ReaimTimes.number("suppressionRecovery", 5F));
    }

    // the most the players' screens darken and their sounds go down, 0 to 1 (each player may take less)
    public static float darken() {
        return Math.clamp(ReaimTimes.number("suppressionDarken", 1F), 0F, 1F);
    }

    public static float muffle() {
        return Math.clamp(ReaimTimes.number("suppressionMuffle", 1F), 0F, 1F);
    }

    // the jolt of the view at each event (Squad's flinch), off by default, and its strength in degrees
    public static boolean flinch() {
        return ReaimTimes.flag("suppressionFlinch", false);
    }

    public static float flinchDeg() {
        return Math.clamp(ReaimTimes.number("suppressionFlinchDeg", 0.22F), 0F, 5F);
    }
}
