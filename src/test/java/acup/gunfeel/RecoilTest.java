package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RecoilTest {
    // firing while looking straight up (or down): the kick is cut off at the stop, and the return must not take back
    // more than was applied, otherwise the view ends up below the original aim.
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
