package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// The port's camera and model kick give 26.2's numbers on the same inputs: the expectations are 26.2's formulas
// (CameraRecoil.frame / ModelRecoil.apply at 4c720e2) written out here over the presets in the jar.
class RecoilPortTest {
    static final long S = 1_000_000_000L;

    static final class FakeView implements CameraRecoil.View {
        float pitch, yaw;

        FakeView(float pitch) {
            this.pitch = pitch;
        }

        @Override
        public float pitch() {
            return pitch;
        }

        @Override
        public float yaw() {
            return yaw;
        }

        @Override
        public void turn(float up, float left) {
            pitch -= up;
            yaw -= left;
        }
    }

    @BeforeEach
    @AfterEach
    void clean() {
        CameraRecoil.reset();
        ModelRecoil.reset();
        Config.select(null);
    }

    static Spring preset(String preset, String channel) {
        return RecoilPresets.preset(preset, channel, false);
    }

    // a generator whose nextDouble is always d: the shake's side is +1 below 0.5, -1 from it (26.2: Rng.camera())
    static Random fixed(final double d) {
        return new Random() {
            @Override
            public double nextDouble() {
                return d;
            }
        };
    }

    // 26.2's reach(t) written out: the furthest the curve has gone by t, on a 0.001 grid, linear between
    static float reach(Recoil c, float t) {
        int n = Math.min(4096, (int) Math.ceil(c.length() / 0.001F) + 1);
        float[] far = new float[n];
        float f = 0F;
        for (int i = 0; i < n; i++) {
            float v = c.at(i * 0.001F);
            if (Math.abs(v) > Math.abs(f)) f = v;
            far[i] = f;
        }
        float x = Math.max(0F, t) / 0.001F;
        int i = (int) x;
        if (i >= n - 1) return far[n - 1];
        return far[i] + (x - i) * (far[i + 1] - far[i]);
    }

    // one hip-fire shot of NTM recoil 10 up, 2 left: the view at each frame is where 26.2 puts it
    @Test
    void cameraFollowsThePresetOfTheGun() {
        for (String gun : new String[] {null, "hbm:gun_maresleg"}) {
            clean();
            Config.select(gun);
            String p = RecoilPresets.presetOf("cameraPitch");
            Spring up = preset(p, "cameraPitch"), left = preset(p, "cameraYaw"), roll = preset(p, "cameraRoll");
            float scale = Config.cameraScale, time = Config.cameraTime, shake = Config.cameraShake, climb = Config.cameraClimb;
            FakeView view = new FakeView(10F);
            CameraRecoil.addImpulse(10F, 2F, false, view.pitch, 5 * S, fixed(0.7));
            float keep = 10F * scale * climb * reach(up, up.length() + 1F);
            for (int f = 0; f <= 60; f++) {
                long now = 5 * S + f * S / 60;
                CameraRecoil.frame(now, view);
                float t = (now - 5 * S) / 1e9F * 1F / time;
                boolean on = t < Math.max(up.length(), Math.max(left.length(), roll.length()));
                // the climb share follows the curve's furthest point and stays (cameraClimb of the gun)
                float wantPitch = !on ? keep : climb == 0F ? up.at(t) * (10F * scale)
                        : ((1F - climb) * up.at(t) + climb * reach(up, t)) * (10F * scale);
                float wantYaw = on ? left.at(t) * (2F * scale) : 0F;
                assertEquals(10F - wantPitch, view.pitch, 1e-5F, gun + " pitch at frame " + f);
                assertEquals(-wantYaw, view.yaw, 1e-5F, gun + " yaw at frame " + f);
                assertEquals(on ? roll.at(t) * shake * -1F : 0F, CameraRecoil.roll, 0F, gun + " roll at frame " + f);
            }
            assertEquals(10F - keep, view.pitch, 1e-5F, gun + ": the camera comes back, but for the climb");
        }
    }

    // aiming scales the kick by adsRecoil; two shots add up
    @Test
    void cameraAddsShotsAndScalesWhileAiming() {
        Spring up = preset("default", "cameraPitch");
        FakeView view = new FakeView(0F);
        CameraRecoil.addImpulse(6F, 0F, true, 0F, 0L, fixed(0.2));
        CameraRecoil.addImpulse(4F, 0F, false, 0F, S / 20, fixed(0.2));
        long now = S / 10;
        CameraRecoil.frame(now, view);
        float want = up.at(0.1F) * 6F * Config.cameraScale * Config.adsRecoil + up.at(0.05F) * 4F * Config.cameraScale;
        assertEquals(-want, view.pitch, 1e-5F);
    }

    // a gun with cameraClimbDeg, cameraDriftDeg and cameraClimbRamp (am180): after a burst the view stays higher and
    // to one side by 26.2's sums, drawn from the generator in 26.2's order (the drift's normal number, then the side)
    @Test
    void burstClimbsAndDriftsAs26Point2() {
        Config.select("hbm:gun_am180");
        float deg = Config.cameraClimbDeg, drift = Config.cameraDriftDeg, ramps = Config.cameraClimbRamp, climb = Config.cameraClimb;
        Spring up = preset(RecoilPresets.presetOf("cameraPitch"), "cameraPitch");
        assertEquals(true, deg > 0F && drift > 0F && ramps > 1F, "am180 ships climb degrees, drift and a ramp");
        Random camera = new Random(71294L), mirror = new Random(71294L);
        FakeView view = new FakeView(0F);
        float wantUp = 0F, wantSide = 0F, d = 0F;
        int shots = 8;
        for (int i = 1; i <= shots; i++) {
            CameraRecoil.addImpulse(1F, 0F, false, view.pitch, i * S / 10, camera);
            CameraRecoil.frame(i * S / 10, view);
            float ramp = Math.min(1F, i / ramps);
            d = Math.clamp(i == 1 ? (float) mirror.nextGaussian() : 0.8F * d + 0.6F * (float) mirror.nextGaussian(), -2.5F, 2.5F);
            mirror.nextDouble();
            wantUp += 1F * Config.cameraScale * climb * ramp * reach(up, up.length() + 1F) + deg * ramp;
            wantSide += drift * ramp * d;
        }
        CameraRecoil.frame(100 * S, view);
        assertEquals(-wantUp, view.pitch, 1e-4F, "the climb");
        assertEquals(-wantSide, view.yaw, 1e-4F, "the drift");
    }

    // the gun model kick: 26.2's sums with the same random draws
    @Test
    void modelKickIsTheSumOf26Point2() {
        for (boolean aiming : new boolean[] {false, true}) {
            clean();
            ModelRecoil.shot(aiming, 0L, new Random(71294L));
            Random r = new Random(71294L);
            float s = Config.modelKick * (aiming ? Config.adsRecoil : 1F), loose = aiming ? 0.5F : 1F;
            float mp = 0.85F + 0.3F * r.nextFloat();
            float my = loose * (r.nextBoolean() ? 1F : -1F) * (0.5F + 0.7F * r.nextFloat());
            float mr = loose * (r.nextBoolean() ? 1F : -1F) * (0.6F + 0.6F * r.nextFloat());
            Spring kp = preset("default", "kickPitch"), ky = preset("default", "kickYaw"), kr = preset("default", "kickRoll");
            Spring py = preset("default", "kickPosY"), pz = preset("default", "kickPosZ");
            float length = Math.max(Math.max(kp.length(), ky.length()), Math.max(kr.length(), Math.max(py.length(), pz.length())));
            for (int f = 1; f <= 60; f++) {
                long now = f * S / 60;
                float t = now / 1e9F * Config.modelSpeed;
                if (t >= length) {
                    // 26.2 too: the frame the last shot runs out still turns, by nothing; after it no shot is left
                    float[] end = ModelRecoil.sample(now);
                    for (float v : end) assertEquals(0F, v, 0F, "frame " + f);
                    assertNull(ModelRecoil.sample(now), "the kick ends at its longest movement");
                    break;
                }
                float[] k = ModelRecoil.sample(now);
                assertEquals(kp.at(t) * s * mp, k[0], 0F, "pitch");
                assertEquals(ky.at(t) * s * my, k[1], 0F, "yaw");
                assertEquals(kr.at(t) * s * mr, k[2], 0F, "roll");
                assertEquals(py.at(t) * s, k[3], 0F, "up");
                assertEquals(pz.at(t) * s, k[4], 0F, "back");
            }
            assertNull(ModelRecoil.sample(10 * S), "the kick ends");
        }
    }
}
