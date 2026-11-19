package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.SplittableRandom;

import net.minecraft.util.Mth;

import org.junit.jupiter.api.Test;

// The heat bar's colour went from Hud to core's HudColors (on GameMath's clamp for Mth's): it gives the colour Hud's old
// line gave on any heat, the ends of the ramp, NaN and the infinities among them
class HudHeatColorTest {
    // Hud.heatColor as it was, on Overheat.ramp as it was (Mth.clamp)
    private static int old(float heat) {
        float t = Mth.clamp((heat - 0.5F) / (1F - 0.5F), 0F, 1F);
        int gr = (int) (208 - 176 * t), b = (int) (64 - 32 * t);
        return 0xFF000000 | 0xFF << 16 | gr << 8 | b;
    }

    @Test
    void heatColorIsHudsOld() {
        float[] odd = {0F, -0F, 0.5F, Math.nextDown(0.5F), Math.nextUp(0.5F), 1F, Math.nextDown(1F), Math.nextUp(1F), -1F, 2F,
                Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MIN_VALUE, Float.MAX_VALUE};
        for (float h : odd) assertEquals(old(h), HudColors.heatColor(h), () -> "heat " + h);
        SplittableRandom r = new SplittableRandom(20261120);
        for (int i = 0; i < 200_000; i++) {
            float h = i % 2 == 0 ? (float) r.nextDouble(-0.5, 1.5) : Float.intBitsToFloat(r.nextInt());
            assertEquals(old(h), HudColors.heatColor(h), () -> "heat " + h);
        }
    }
}
