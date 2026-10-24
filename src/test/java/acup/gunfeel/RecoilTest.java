package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RecoilTest {
    @Test
    void strengthStaysInItsRange() {
        assertEquals(0.2F, ModelRecoil.strength(0F, 0F), 1e-6F);
        assertEquals(1.4F, ModelRecoil.strength(1000F, 1000F), 1e-6F);
        for (float v = 0F; v < 50F; v += 0.25F) {
            float s = ModelRecoil.strength(v, v / 3F);
            assertTrue(s >= 0.2F && s <= 1.4F, "strength " + s + " for " + v);
        }
    }

    @Test
    void strengthGrowsWithRecoil() {
        float prev = ModelRecoil.strength(0F, 0F);
        for (float v = 0.5F; v < 40F; v += 0.5F) {
            float s = ModelRecoil.strength(v, 0F);
            assertTrue(s >= prev, "weaker at " + v);
            prev = s;
        }
    }

    @Test
    void strengthIgnoresDirection() {
        assertEquals(ModelRecoil.strength(5F, 2F), ModelRecoil.strength(-5F, -2F), 1e-6F);
        assertEquals(ModelRecoil.strength(5F, 2F), ModelRecoil.strength(2F, 5F), 1e-6F);
    }

    // firing while looking straight up (or down): the kick is cut off at the stop, and the return must not take back
    // more than was applied (BUGS.md: the view ended up to a whole shot's recoil below the aim)
    @Test
    void cameraEndsWhereItStartedAtTheStop() {
        for (float start : new float[] {-90F, -85F, 0F, 89F, 90F}) {
            float xRot = start, applied = 0F;
            Spring kick = new Spring(1F, 0.015F, 0.4F, 0.1F, 0F, 0F);
            for (float t = 0F; t <= kick.length() + 0.05F; t += 1F / 60F) {
                float pitch = t >= kick.length() ? 0F : kick.at(t) * 8F;
                float got = CameraRecoil.pitchTurn(xRot, pitch - applied);
                xRot -= got;
                applied += got;
                assertTrue(xRot >= -90F && xRot <= 90F);
            }
            assertEquals(start, xRot, 1e-3F, "from pitch " + start);
        }
    }
}
