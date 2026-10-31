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

    // cameraClimb 0 is the old camera, to the bit
    @Test
    void noClimbIsTheCurveItself() {
        Spring kick = new Spring(1F, 0.015F, 0.4F, 0.1F, 0F, 0F);
        float[] reach = CameraRecoil.reachTable(kick);
        for (float t = 0F; t <= kick.length(); t += 0.0007F) {
            assertEquals(kick.at(t) * 8F, CameraRecoil.pitchOf(kick, reach, 8F, 0F, 0F, t), 0F, "at " + t);
        }
    }

    // climb 0.5: the view rises by the whole kick, comes back half of it and ends half a kick higher; at the stop only
    // what the stop let through stays
    @Test
    void halfTheKickStays() {
        Spring kick = new Spring(1F, 0.015F, 0.4F, 0.1F, 0F, 0F);
        float[] reach = CameraRecoil.reachTable(kick);
        assertEquals(1F, reach[reach.length - 1], 2e-3F, "peak");
        assertEquals(8F, CameraRecoil.pitchOf(kick, reach, 8F, 0.5F, 0F, 0.015F), 0.02F, "the whole kick at the peak");
        float keep = 8F * 0.5F * reach[reach.length - 1];
        for (float start : new float[] {-90F, -88F, -85F, 0F, 89F, 90F}) {
            float xRot = start, applied = 0F, top = start;
            for (float t = 0F; t <= kick.length() + 0.05F; t += 1F / 60F) {
                float pitch = t >= kick.length() ? keep : CameraRecoil.pitchOf(kick, reach, 8F, 0.5F, 0F, t);
                float got = CameraRecoil.pitchTurn(xRot, pitch - applied);
                xRot -= got;
                applied += got;
                top = Math.min(top, xRot);
                assertTrue(xRot >= -90F && xRot <= 90F);
            }
            assertEquals(Math.max(-90F, start - 8F), top, 0.2F, "kick top from pitch " + start);
            assertEquals(Math.max(-90F, start - keep), xRot, 1e-3F, "from pitch " + start);
        }
    }

    // cameraClimbDeg: a kick of noise around 0 (here exactly 0) still leaves the view that many degrees higher, rising
    // with the curve rather than in one jump
    @Test
    void degreesStayWhateverTheKick() {
        Spring kick = new Spring(1F, 0.015F, 0.4F, 0.1F, 0F, 0F);
        float[] reach = CameraRecoil.reachTable(kick);
        float half = CameraRecoil.pitchOf(kick, reach, 0F, 0F, 0.4F, 0.0075F);
        assertTrue(half > 0.1F && half < 0.35F, "half way up the rise: " + half);
        for (float up : new float[] {0F, -1.5F, 2F}) {
            float xRot = 0F, applied = 0F;
            for (float t = 0F; t <= kick.length() + 0.05F; t += 1F / 60F) {
                float pitch = t >= kick.length() ? 0.4F : CameraRecoil.pitchOf(kick, reach, up, 0F, 0.4F, t);
                float got = CameraRecoil.pitchTurn(xRot, pitch - applied);
                xRot -= got;
                applied += got;
            }
            assertEquals(-0.4F, xRot, 1e-3F, "kick " + up);
        }
        assertEquals(0F, CameraRecoil.rise(reach, 0F), 1e-6F, "the sideways part starts with the kick");
        assertEquals(1F, CameraRecoil.rise(reach, kick.length()), 1e-6F, "and ends whole");
    }

    // cameraClimbRamp: the staying parts grow evenly over the burst's first shots
    @Test
    void rampGrowsToFull() {
        assertEquals(1F, CameraRecoil.ramp(1, 0F));
        assertEquals(1F, CameraRecoil.ramp(1, 1F));
        assertEquals(0.2F, CameraRecoil.ramp(1, 5F), 1e-6F);
        assertEquals(0.6F, CameraRecoil.ramp(3, 5F), 1e-6F);
        assertEquals(1F, CameraRecoil.ramp(5, 5F));
        assertEquals(1F, CameraRecoil.ramp(40, 5F));
    }

    // cameraDriftDeg: the side keeps its heading for a few shots and its spread stays about 1 over a long burst
    @Test
    void driftWandersButStaysBounded() {
        java.util.Random r = new java.util.Random(7);
        float d = 0F;
        double sum2 = 0;
        int flips = 0, n = 20000;
        for (int i = 0; i < n; i++) {
            float next = CameraRecoil.nextDrift(d, i == 0, (float) r.nextGaussian());
            if (i > 0 && Math.signum(next) != Math.signum(d)) flips++;
            d = next;
            sum2 += d * d;
            assertTrue(Math.abs(d) <= 2.5F);
        }
        double spread = Math.sqrt(sum2 / n);
        assertTrue(spread > 0.85 && spread < 1.1, "spread " + spread);
        // a fresh random side each shot would flip every other shot
        assertTrue(flips < n / 3, "flips " + flips);
    }
}
