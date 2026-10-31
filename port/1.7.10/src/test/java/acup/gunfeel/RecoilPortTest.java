package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// The port's camera and model kick give 26.2's numbers on the same inputs: the expectations are 26.2's formulas
// (CameraRecoil.frame / ModelRecoil.apply at 4a6ec20) written out here over the presets in the jar.
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

    // one hip-fire shot of NTM recoil 10 up, 2 left: the view at each frame is where 26.2 puts it
    @Test
    void cameraFollowsThePresetOfTheGun() {
        for (String gun : new String[] {null, "hbm:gun_maresleg"}) {
            clean();
            Config.select(gun);
            String p = RecoilPresets.presetOf("cameraPitch");
            Spring up = preset(p, "cameraPitch"), left = preset(p, "cameraYaw"), roll = preset(p, "cameraRoll");
            float scale = Config.cameraScale, time = Config.cameraTime, shake = Config.cameraShake;
            FakeView view = new FakeView(10F);
            CameraRecoil.addImpulse(10F, 2F, false, view.pitch, 5 * S, -1F);
            for (int f = 0; f <= 60; f++) {
                long now = 5 * S + f * S / 60;
                CameraRecoil.frame(now, view);
                float t = (now - 5 * S) / 1e9F * 1F / time;
                boolean on = t < Math.max(up.length(), Math.max(left.length(), roll.length()));
                float wantPitch = on ? up.at(t) * (10F * scale) : 0F;
                float wantYaw = on ? left.at(t) * (2F * scale) : 0F;
                assertEquals(10F - wantPitch, view.pitch, 1e-5F, gun + " pitch at frame " + f);
                assertEquals(-wantYaw, view.yaw, 1e-5F, gun + " yaw at frame " + f);
                assertEquals(on ? roll.at(t) * shake * -1F : 0F, CameraRecoil.roll, 0F, gun + " roll at frame " + f);
            }
            assertEquals(10F, view.pitch, 0F, gun + ": the camera comes back");
        }
    }

    // aiming scales the kick by adsRecoil; two shots add up
    @Test
    void cameraAddsShotsAndScalesWhileAiming() {
        Spring up = preset("default", "cameraPitch");
        FakeView view = new FakeView(0F);
        CameraRecoil.addImpulse(6F, 0F, true, 0F, 0L, 1F);
        CameraRecoil.addImpulse(4F, 0F, false, 0F, S / 20, 1F);
        long now = S / 10;
        CameraRecoil.frame(now, view);
        float want = up.at(0.1F) * 6F * Config.cameraScale * Config.adsRecoil + up.at(0.05F) * 4F * Config.cameraScale;
        assertEquals(-want, view.pitch, 1e-5F);
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
