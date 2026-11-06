package acup.gunfeel.water;

import acup.gunfeel.ReaimTimes;

// 26.2's WaterSettings, the ballistics' keys. They are the server's (ntmgunfeel-server.properties, sent to the
// players, so a client's tracer works out the same path).
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
}
