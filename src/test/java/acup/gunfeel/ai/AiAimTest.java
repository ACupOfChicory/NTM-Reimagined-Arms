package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

// An AI mob's aim on a target moving across its sight with Minecraft's ground movement: standing, sprinting across,
// strafing in a steady rhythm and in an uneven one. Aim only (no gun spread), bullets at 16 blocks a tick.
class AiAimTest {
    static final double BULLET = 16, EYE = 1.6, CHEST = 1.1;

    /** sideways places a tick apart: v = 0.546 v + push, as a player walks or sprints on the ground */
    static double[] track(String kind, Random r, int n) {
        double[] xs = new double[n];
        double x = 0, v = 0;
        int side = 1, left = 10;
        for (int t = 0; t < n; t++) {
            xs[t] = x;
            double push;
            switch (kind) {
                case "stand" -> push = 0;
                case "run" -> push = 0.1274;
                default -> {
                    push = 0.098 * side;
                    if (--left == 0) {
                        side = -side;
                        left = kind.equals("steady") ? 10 : 6 + r.nextInt(9);
                    }
                }
            }
            v = 0.546 * v + push;
            x += v;
        }
        return xs;
    }

    static double at(double[] xs, double t) {
        t = Math.max(0, Math.min(t, xs.length - 2));
        int i = (int) t;
        return xs[i] + (xs[i + 1] - xs[i]) * (t - i);
    }

    /** shots that would hit out of those it fired, a shot every 3 ticks at most, the target d blocks ahead */
    static double hits(AiSkill skill, String kind, double d, int seed) {
        Random rng = new Random(seed);
        double[] xs = track(kind, new Random(seed * 7L + 1), 160);
        AiAim aim = new AiAim();
        double flight = d / BULLET;
        float yaw = -25F, pitch = 0F;
        int next = 0, shots = 0, hits = 0;
        for (int t = 0; t < 120; t++) {
            aim.see(1, at(xs, t), CHEST, d, t, skill, 0, 0);
            assertTrue(aim.lead(t, skill, flight));
            aim.turn(t, skill, 0, EYE, 0, yaw, pitch, aim.spotX, aim.spotY, aim.spotZ, aim.moveX, aim.moveY, aim.moveZ, rng::nextGaussian);
            yaw = aim.yaw;
            pitch = aim.pitch;
            if (t < next || !AiAim.onTarget(skill, 0, EYE, 0, yaw, pitch, aim.spotX, aim.spotY, aim.spotZ)) continue;
            next = t + 3;
            shots++;
            // where the shot passes the target when the bullet gets there: off its middle across, and how high
            double c = Math.cos(Math.toRadians(pitch)), dx = -Math.sin(Math.toRadians(yaw)) * c, dz = Math.cos(Math.toRadians(yaw)) * c;
            double dy = -Math.sin(Math.toRadians(pitch)), flat = Math.hypot(dx, dz), tx = at(xs, t + flight);
            double off = Math.abs(tx * dz - d * dx) / flat, y = EYE + dy / flat * ((tx * dx + d * dz) / flat);
            if (off <= 0.3 && y >= CHEST - 1.1 && y <= CHEST + 0.7) hits++;
        }
        return shots == 0 ? 0 : (double) hits / shots;
    }

    static double mean(AiSkill skill, String kind, double d) {
        double sum = 0;
        for (int s = 0; s < 200; s++) sum += hits(skill, kind, d, s);
        return sum / 200;
    }

    @Test
    void aRegularHitsOneStandingOrRunningAcross() {
        for (double d : new double[] {10, 30, 60}) {
            assertTrue(mean(AiSkill.REGULAR, "stand", d) >= 0.95, "standing, " + d + " blocks");
            assertTrue(mean(AiSkill.REGULAR, "run", d) >= 0.90, "running across, " + d + " blocks");
        }
    }

    @Test
    void itReadsASteadyStrafeButNotAnUnevenOne() {
        for (double d : new double[] {10, 30, 60}) {
            double steady = mean(AiSkill.REGULAR, "steady", d), uneven = mean(AiSkill.REGULAR, "uneven", d);
            assertTrue(steady >= 0.6, "a steady strafe, " + d + " blocks: " + steady);
            assertTrue(uneven >= 0.08 && uneven < steady - 0.3, "an uneven strafe, " + d + " blocks: " + uneven);
            // a recruit does not read the rhythm
            assertTrue(mean(AiSkill.RECRUIT, "steady", d) < 0.3, "a recruit on a steady strafe, " + d + " blocks");
        }
    }

    @Test
    void aRecruitMissesMoreFarOff() {
        assertTrue(mean(AiSkill.RECRUIT, "stand", 60) < mean(AiSkill.REGULAR, "stand", 60) - 0.1);
    }

    @Test
    void theRhythmEndsWhenItStopsSwinging() {
        // four swings of 10 ticks, then a sprint one way
        double[] xs = new double[200];
        double x = 0, v = 0;
        for (int t = 0; t < xs.length; t++) {
            xs[t] = x;
            double push = t < 80 ? ((t / 10) % 2 == 0 ? 0.098 : -0.098) : 0.1274;
            v = 0.546 * v + push;
            x += v;
        }
        AiAim aim = new AiAim();
        boolean read = false;
        int lastRead = -1;
        for (int t = 0; t < xs.length; t++) {
            aim.see(1, xs[t], CHEST, 30, t, AiSkill.REGULAR, 0, 0);
            aim.lead(t, AiSkill.REGULAR, 30 / BULLET);
            if (aim.byRhythm) {
                read |= t < 85;
                lastRead = t;
            }
        }
        assertTrue(read, "the swings were read");
        assertTrue(lastRead < 80 + 3 + 20, "and no longer once it ran: last at " + lastRead);
    }

    @Test
    void aKickLiftsTheViewAndTheHandTakesItBackLate() {
        // a target straight ahead at eye height, 20 blocks off; no hand error, to see the recoil alone
        AiAim aim = new AiAim();
        AiSkill s = AiSkill.REGULAR;
        float yaw = 0F, pitch = 0F;
        float[] view = new float[20], seen = new float[20];
        for (int t = 0; t < 20; t++) {
            aim.see(1, 0, EYE, 20, t, s, 0, 0);
            aim.lead(t, s, 1);
            aim.turn(t, s, 0, EYE, 0, yaw, pitch, aim.spotX, aim.spotY, aim.spotZ, aim.moveX, aim.moveY, aim.moveZ, () -> 0);
            yaw = aim.yaw;
            pitch = aim.pitch;
            view[t] = pitch;
            seen[t] = aim.seenPitch;
            if (t == 5) {
                float[] moved = aim.kick(4F, 0F, 0F, 0F, 0.25F, s);
                assertTrue(Math.abs(moved[1] + 4F * (1 - s.absorb())) < 1e-4, "a regular takes back " + s.absorb() + " of it at once");
                yaw += moved[0];
                pitch += moved[1];
            }
        }
        assertTrue(view[6] < -1.5F && Math.abs(seen[6]) < 1e-3, "the view went up, and the mob does not know yet: " + view[6]);
        assertTrue(view[8] < -1F, "nor a tick before it notices: " + view[8]);
        assertTrue(Math.abs(view[6 + s.lag()]) < 0.05F, "noticed, the hand takes back what is left: " + view[6 + s.lag()]);
        assertTrue(Math.abs(view[19]) < 0.05F, "and it stays on the target: " + view[19]);
        // a recruit takes back none of it at once
        float[] all = new AiAim().kick(4F, 1F, 0F, 0F, 0.25F, AiSkill.RECRUIT);
        assertTrue(all[1] == -4F && all[0] == -1F);
    }

    @Test
    void whatStaysUpHoldsTillTheHandNotices() {
        AiAim aim = new AiAim();
        AiSkill s = AiSkill.REGULAR;
        float yaw = 0F, pitch = 0F;
        float[] up = new float[20], aside = new float[20];
        for (int t = 0; t < 20; t++) {
            aim.see(1, 0, EYE, 20, t, s, 0, 0);
            aim.lead(t, s, 1);
            aim.turn(t, s, 0, EYE, 0, yaw, pitch, aim.spotX, aim.spotY, aim.spotZ, aim.moveX, aim.moveY, aim.moveZ, () -> 0);
            yaw = aim.yaw;
            pitch = aim.pitch;
            up[t] = pitch;
            aside[t] = yaw;
            if (t == 5) {
                float[] moved = aim.kick(0F, 0F, 2F, 1F, 0.25F, s);
                yaw += moved[0];
                pitch += moved[1];
            }
        }
        float keep = 1 - s.absorb();
        for (int t = 6; t < 6 + s.lag(); t++) {
            assertTrue(Math.abs(up[t] + 2F * keep) < 1e-3, "it does not come back by itself: " + up[t]);
            assertTrue(Math.abs(aside[t] + keep) < 1e-3, "nor aside: " + aside[t]);
        }
        for (int t = 6 + s.lag(); t < 20; t++)
            assertTrue(Math.abs(up[t]) < 0.05F && Math.abs(aside[t]) < 0.05F, "the hand takes it back: tick " + t + ", " + up[t] + ", " + aside[t]);
    }

    @Test
    void aLongBurstDoesNotPileUp() {
        // a target well below, a kick that stays every tick for 200 ticks: the view never gets more off than the kicks
        // the hand has not noticed yet, and ends on the target
        AiAim aim = new AiAim();
        AiSkill s = AiSkill.REGULAR;
        double d = 20, y = EYE - d * Math.tan(Math.toRadians(60));
        float yaw = 0F, pitch = 60F, keep = 1 - s.absorb();
        for (int t = 0; t < 230; t++) {
            aim.see(1, 0, y, d, t, s, 0, 0);
            aim.lead(t, s, 1);
            aim.turn(t, s, 0, EYE, 0, yaw, pitch, aim.spotX, aim.spotY, aim.spotZ, aim.moveX, aim.moveY, aim.moveZ, () -> 0);
            yaw = aim.yaw;
            pitch = aim.pitch;
            if (t > 10) assertTrue(Math.abs(pitch - 60F) <= s.lag() * keep + 1e-3F, "tick " + t + ": " + pitch);
            if (t < 200) pitch += aim.kick(0F, 0F, 1F, 0F, 0.25F, s)[1];
        }
        assertTrue(Math.abs(pitch - 60F) < 0.05F, "back on the target: " + pitch);
    }

    @Test
    void aPauseLeavesNoKickBehind() {
        // a kick that stays, then nothing to hold the gun on for a while, the head back level: no kick comes back
        AiAim aim = new AiAim();
        AiSkill s = AiSkill.REGULAR;
        float yaw = 0F, pitch = 0F;
        for (int t = 0; t < 6; t++) {
            aim.see(1, 0, EYE, 20, t, s, 0, 0);
            aim.lead(t, s, 1);
            aim.turn(t, s, 0, EYE, 0, yaw, pitch, aim.spotX, aim.spotY, aim.spotZ, aim.moveX, aim.moveY, aim.moveZ, () -> 0);
            yaw = aim.yaw;
            pitch = aim.pitch;
        }
        aim.kick(0F, 0F, 5F, 0F, 0.25F, s);
        aim.see(1, 0, EYE, 20, 60, s, 0, 0);
        aim.lead(60, s, 1);
        aim.turn(60, s, 0, EYE, 0, 0F, 0F, aim.spotX, aim.spotY, aim.spotZ, aim.moveX, aim.moveY, aim.moveZ, () -> 0);
        assertTrue(Math.abs(aim.pitch) < 0.05F, "on the target: " + aim.pitch);
    }

    @Test
    void aNewTargetOrALongLookAwayStartsOver() {
        AiAim aim = new AiAim();
        assertFalse(aim.lead(0, AiSkill.REGULAR, 1), "nothing seen");
        aim.see(1, 0, CHEST, 20, 0, AiSkill.REGULAR, 0, 0);
        assertTrue(aim.lead(0, AiSkill.REGULAR, 1), "the first sight is all it has");
        for (int t = 1; t < 10; t++) aim.see(1, t * 0.3, CHEST, 20, t, AiSkill.REGULAR, 0, 0);
        aim.lead(9, AiSkill.REGULAR, 1);
        assertTrue(aim.moveX > 0.2, "it saw it go");
        aim.see(2, 5, CHEST, 20, 10, AiSkill.REGULAR, 0, 0);
        aim.lead(10, AiSkill.REGULAR, 1);
        assertTrue(aim.moveX == 0 && aim.spotX == 5, "another one: no motion yet");
        aim.see(2, 9, CHEST, 20, 30, AiSkill.REGULAR, 0, 0);
        aim.lead(30, AiSkill.REGULAR, 1);
        assertTrue(aim.moveX == 0 && aim.spotX == 9, "seen again after a while: starts over");
    }
}
