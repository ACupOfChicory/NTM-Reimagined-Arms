package acup.gunfeel.water;

import acup.gunfeel.ReaimTimes;

// The water keys. Physics and explosives are the server's (ntm-gunfeel-server.properties, sent to the players, so a
// client works out the same path); the splashes are the player's own (Config.waterSplash, read by WaterFx).
public final class WaterSettings {
    private WaterSettings() {
    }

    public static WaterPath.Settings path() {
        return new WaterPath.Settings(ballistics(), Math.clamp(ReaimTimes.number("waterStopFraction", 0.15F), 0.001F, 0.99F),
                Math.max(0.01F, ReaimTimes.number("waterRangeScale", 1F)), ReaimTimes.number("waterSkipAngle", 6F),
                ReaimTimes.number("waterFuzeSkipAngle", 10F), 2);
    }

    // waterBallistics: water slows, stops and skips bullets
    public static boolean ballistics() {
        return ReaimTimes.flag("waterBallistics", true);
    }

    // waterMuffle: a gun fired with the shooter's eyes in water sounds muffled (volume × 0.6, pitch × 0.7)
    public static boolean muffle() {
        return ReaimTimes.flag("waterMuffle", true);
    }

    // waterFuze: an impact fuze goes off on water
    public static boolean fuze() {
        return ReaimTimes.flag("waterFuze", true);
    }

    public static float fuzeSkipAngle() {
        return ReaimTimes.number("waterFuzeSkipAngle", 10F);
    }

    // waterBlastRadius: a blast in water reaches mobs in the same water out to this × its radius
    public static float blastRadius() {
        return ReaimTimes.number("waterBlastRadius", 1.5F);
    }
}
