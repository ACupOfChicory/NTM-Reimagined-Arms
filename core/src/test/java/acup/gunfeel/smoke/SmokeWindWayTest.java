package acup.gunfeel.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// The wind over a level by itself: its speed by the weather, its way turning slowly, each world its own.
class SmokeWindWayTest {
    @Test
    void theWindFollowsTheWeatherAndTurnsSlowly() {
        float[] clear = SmokeWind.at(42L, 1000L, 0F, 0F), rain = SmokeWind.at(42L, 1000L, 1F, 0F), storm = SmokeWind.at(42L, 1000L, 1F, 1F);
        assertEquals(0.025F, (float) Math.hypot(clear[0], clear[1]), 1e-6F);
        assertEquals(0.05F, (float) Math.hypot(rain[0], rain[1]), 1e-6F);
        assertEquals(0.1F, (float) Math.hypot(storm[0], storm[1]), 1e-6F);
        double most = 0, far = 0;
        for (long t = 0; t < 72_000; t += 200) {
            most = Math.max(most, Math.abs(SmokeWind.way(42L, t + 1200) - SmokeWind.way(42L, t)));
            far = Math.max(far, Math.abs(SmokeWind.way(42L, t + 24_000) - SmokeWind.way(42L, t)));
        }
        System.out.println("the wind turns at most " + Math.toDegrees(most) + " degrees a minute");
        assertTrue(most < Math.toRadians(45), "a minute turns it little: " + Math.toDegrees(most));
        assertTrue(SmokeWind.way(42L, 0) != SmokeWind.way(43L, 0), "each world its own");
        assertEquals(SmokeWind.way(42L, 5000), SmokeWind.way(42L, 5000));
        System.out.println("and at most " + Math.toDegrees(far) + " degrees in 20 minutes");
        assertTrue(far > Math.toRadians(60), "in 20 minutes it turns well round: " + Math.toDegrees(far));
    }
}
