package acup.gunfeel.ai.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.util.Mth;

/** The moves from recordings (BotMoves): read, mirrored, played by the mob's own turn, the feet left to them */
class BotMovesTest {
    static final double FRAME = 1.0 / 3.0;

    @Test
    void theMovesAreReadAndEachHasItsMirror() {
        for (String n : new String[] { "turn90_left_stand", "turn90_right_swat", "turn180_right_swat", "start_run_cmu104_55",
                "stop_run_cmu104_56" }) {
            BotMoves.Move m = BotMoves.get(n), mm = BotMoves.get(n + "_mirrored");
            assertNotNull(m, n);
            assertNotNull(mm, n);
            assertTrue(m.clip().length() > 0.5F && m.total() > 0F, n);
        }
        for (boolean left : new boolean[] { false, true })
            for (boolean half : new boolean[] { false, true }) assertNotNull(BotMoves.turn(left, half));
        // a turn to the right turns right (its curve to the left below nought), its mirror left
        assertTrue(BotMoves.turn(false, false).curve()[BotMoves.turn(false, false).curve().length - 1] < 0F);
        assertTrue(BotMoves.turn(true, false).curve()[BotMoves.turn(true, false).curve().length - 1] > 0F);
    }

    @Test
    void aMirroredMovesLegsAreItsTwinsTurnedTheOtherWay() {
        BotMoves.Move m = BotMoves.get(BotMoves.TURN_90), mm = BotMoves.get(BotMoves.TURN_90 + "_mirrored");
        float[] a = new float[3], b = new float[3];
        for (float t : new float[] { 0F, 0.4F, 0.9F, 1.5F }) {
            for (String[] pair : new String[][] { { "right_leg", "left_leg" }, { "left_shin", "right_shin" } }) {
                assertTrue(m.clip().rotation(pair[0], t, a) && mm.clip().rotation(pair[1], t, b));
                assertEquals(a[0], b[0], 0F);
                assertEquals(-a[1], b[1], 0F);
                assertEquals(-a[2], b[2], 0F);
            }
        }
        assertEquals(m.total(), mm.total(), 0F);
        assertEquals(m.timeAt(40F), mm.timeAt(40F), 0F);
        assertTrue(m.bothDown(0F) == mm.bothDown(0F));
    }

    @Test
    void aMovesTimeIsWhereItHasTurnedThatFar() {
        BotMoves.Move m = BotMoves.get(BotMoves.TURN_180);
        assertEquals(0F, m.timeAt(0F), 0F);
        assertEquals(m.clip().length(), m.timeAt(m.total() + 10F), 0F);
        float before = 0F;
        for (float v = 1F; v < m.total(); v += 1F) {
            float t = m.timeAt(v);
            assertTrue(t >= before, "back in time at " + v);
            before = t;
            // its curve there (linear between samples): as far as asked
            float at = t * m.hz();
            int i = Math.min((int) at, m.curve().length - 2);
            float c = -Mth.lerp(at - i, m.curve()[i], m.curve()[i + 1]);
            assertEquals(v, c, 1.5F, "turned at " + t);
        }
    }

    /** a frame of a mob standing at the spot, its body drawn going to {@code yaw} */
    static void frame(BotAnimator a, float yaw, double t) {
        a.turn(yaw, yaw, 1F, t);
        a.moves(true, t, yaw);
        a.feet(0.0, 0.0, 1F, t);
    }

    /** where a move's legs put the ankles in the world from the mob's place (blocks, right then left), its body at yaw */
    static double[][] ankles(BotAnimator.Moving mv, float yaw) {
        BotPoser.Parts p = BotPoserTest.parts();
        BotPoser.set(p, mv.clip(), mv.t(), 1F, BotPoser.LEGS, new float[3]);
        BotPoser.twistLegs(p, mv.twist());
        double[][] out = new double[2][];
        org.joml.Vector3f v = new org.joml.Vector3f();
        BotPoser.ankle(p.rightLeg(), p.rightShin(), v);
        out[0] = BotAnimator.toWorld(v.x, v.z, yaw);
        BotPoser.ankle(p.leftLeg(), p.leftShin(), v);
        out[1] = BotAnimator.toWorld(v.x, v.z, yaw);
        return out;
    }

    @Test
    void aFootTheTurnHasDownStandsInTheWorldHoweverFarTheClipIsBehindOrAheadOfTheBody() {
        for (boolean left : new boolean[] { false, true }) {
            BotAnimator a = new BotAnimator();
            double t = 0.0;
            for (; t < 30.0; t += FRAME) frame(a, 0F, t);
            // a half turn at 100 degrees a second, then stood: on to both feet down
            float yaw = 0F, sign = left ? -1F : 1F, most = 0F, twist = 0F;
            // each foot: where it came down in this stance (null: lifted)
            double[][] at = new double[2][];
            int down = 0;
            for (; t < 100.0; t += FRAME) {
                yaw = sign * Math.min(180F, Math.abs(yaw) + 5F * (float) FRAME);
                frame(a, yaw, t);
                BotAnimator.Moving[] mv = a.moving();
                if (mv.length != 1 || mv[0].w() < 1F) {
                    at[0] = at[1] = null;
                    continue;
                }
                twist = Math.max(twist, Math.abs(mv[0].twist()) * Mth.RAD_TO_DEG);
                BotMoves.Move m = BotMoves.turn(left, true).clip() == mv[0].clip() ? BotMoves.turn(left, true) : BotMoves.turn(left, false);
                double[][] now = ankles(mv[0], a.bodyYaw);
                for (int k = 0; k < 2; k++) {
                    float[] st = m.stances()[k];
                    boolean stands = false;
                    for (int i = 0; i + 1 < st.length; i += 2) stands |= mv[0].t() > st[i] + 0.02F && mv[0].t() < st[i + 1] - 0.02F;
                    if (!stands) {
                        at[k] = null;
                        continue;
                    }
                    if (at[k] == null) at[k] = now[k];
                    most = (float) Math.max(most, Math.hypot(now[k][0] - at[k][0], now[k][1] - at[k][1]) * 1000.0);
                    down++;
                }
            }
            assertTrue(down > 60, "feet down seen: " + down);
            // the recording's own: its feet slide some 5 mm a stance (tools/motion)
            assertTrue(most < 8F, (left ? "left" : "right") + ": a foot down went " + most + " mm from where it came down");
            assertTrue(twist > 5F && twist < 45F, "legs turned from the body at most " + twist);
        }
    }

    @Test
    void stoodItGoesOnToBothFeetDownAndTheLockTakesThemAsTheLegsAreTurned() {
        BotAnimator a = new BotAnimator();
        double t = 0.0;
        for (; t < 30.0; t += FRAME) frame(a, 0F, t);
        float yaw = 0F;
        for (; t < 50.0; t += FRAME) {
            yaw = Math.min(90F, yaw + 5F * (float) FRAME);
            frame(a, yaw, t);
        }
        // the clip turns 104.6: stood at 90, it ends with the legs past the body, both feet down
        BotAnimator.Moving last = null;
        float twist = 0F;
        for (; t < 120.0; t += FRAME) {
            boolean was = a.moveFeet;
            frame(a, 90F, t);
            if (was && !a.moveFeet && last == null) {
                // going out from where it stood
                last = a.moving()[0];
                twist = a.legTwist;
            }
        }
        assertNotNull(last, "gone out");
        BotMoves.Move m = BotMoves.turn(false, false);
        assertTrue(m.bothDown(last.t()), "both feet down at " + last.t());
        assertTrue(twist > 3F && twist < 20F, "the legs past the body " + twist);
        // the lock holds them turned so, from the legs as the pose has them now (nothing left of the move)
        assertEquals(0F, a.legTwist, 0F);
        assertEquals(twist * Mth.DEG_TO_RAD, a.feetOut.at[3], 0.03F);
        assertEquals(twist * Mth.DEG_TO_RAD, a.feetOut.at[7], 0.03F);
    }

    @Test
    void aFootTheMoveHasDownStaysWhereItCameDown() {
        BotAnimator a = new BotAnimator();
        double t = 0.0;
        for (; t < 30.0; t += FRAME) frame(a, 0F, t);
        float yaw = 0F;
        double[][] at = new double[2][];
        int kept = 0;
        for (; t < 90.0; t += FRAME) {
            yaw = Math.max(-180F, yaw - 5F * (float) FRAME);
            frame(a, yaw, t);
            for (int k = 0; k < 2; k++) {
                // where the lock has the foot, in the world
                double[] w = BotAnimator.toWorld(a.feetOut.at[k * 4], a.feetOut.at[k * 4 + 1], a.bodyYaw);
                if (!a.moveFeet || !a.moveDown[k]) {
                    at[k] = null;
                    continue;
                }
                if (at[k] == null) at[k] = w;
                assertEquals(at[k][0], w[0], 1e-5, "foot " + k + " at " + t);
                assertEquals(at[k][1], w[1], 1e-5, "foot " + k + " at " + t);
                kept++;
            }
        }
        assertTrue(kept > 100, "kept " + kept);
    }

    @Test
    void stoodItStopsWhereBothFeetAreDownNearestTheBody() {
        BotMoves.Move m = BotMoves.turn(false, false);
        // the quarter turn: both down 0.4 .. 0.667 (some 8 .. 30 degrees), from 1.033 (60), from 1.633 (104)
        float early = m.settleAt(0.45F, 20F), late = m.settleAt(1.2F, 90F);
        assertTrue(m.bothDown(early) && early < 0.7F, "turned 20: at " + early);
        assertTrue(m.bothDown(late) && late >= 1.62F, "turned 90: at " + late);
        assertTrue(m.bothDown(m.settleAt(0.7F, 50F)));
        assertTrue(m.settleAt(0.7F, 50F) >= 0.7F);
    }

    @Test
    void aMoveComingInTurnsTheFeetFromAsTheLockHadThem() {
        BotAnimator a = new BotAnimator();
        double t = 0.0;
        for (; t < 30.0; t += FRAME) frame(a, 0F, t);
        float yaw = 0F;
        boolean seen = false;
        for (; t < 60.0 && !seen; t += FRAME) {
            yaw = Math.min(90F, yaw + 5F * (float) FRAME);
            boolean was = a.moveFeet;
            // each foot's way in the world as the lock has it (the legs' own turn as the pose has them, and the lock's)
            float[] before = { a.bodyYaw + a.legTwist + a.feetOut.at[3] * Mth.RAD_TO_DEG,
                    a.bodyYaw + a.legTwist + a.feetOut.at[7] * Mth.RAD_TO_DEG };
            frame(a, yaw, t);
            if (!was && a.moveFeet) {
                seen = true;
                for (int k = 0; k < 2; k++) {
                    float now = a.bodyYaw + a.legTwist + a.feetOut.at[k * 4 + 3] * Mth.RAD_TO_DEG;
                    assertTrue(Math.abs(now - before[k]) < 2F, "foot " + k + " turned " + (now - before[k]) + " as the move came in");
                }
            }
        }
        assertTrue(seen, "a move came in");
    }

    @Test
    void aTurnOnPastTheClipsGoesOnWithTheLegsNeverFarFromTheBody() {
        // looking round and round at 60 degrees a second: moves one after another, the legs never wound up
        BotAnimator a = new BotAnimator();
        double t = 0.0;
        for (; t < 30.0; t += FRAME) frame(a, 0F, t);
        float yaw = 0F, most = 0F;
        int starts = 0;
        boolean was = false;
        for (; t < 30.0 + 20.0 * 12.0; t += FRAME) {
            yaw += 3F * (float) FRAME;
            frame(a, yaw, t);
            most = Math.max(most, Math.abs(a.legTwist));
            if (a.moveFeet && !was) starts++;
            was = a.moveFeet;
            for (int k = 0; k < 2; k++)
                assertTrue(Math.abs(a.feetOut.at[k * 4 + 3]) < Mth.PI / 2F, "foot " + k + " turned " + a.feetOut.at[k * 4 + 3]);
        }
        assertTrue(yaw > 700F);
        assertTrue(most <= BotAnimator.MOVE_TWIST_MOST + 1F, "legs turned " + most);
        assertTrue(starts >= 3, "moves started " + starts);
    }

    @Test
    void noLongerFreeItGoesOutAtOnce() {
        BotAnimator a = new BotAnimator();
        double t = 0.0;
        for (; t < 30.0; t += FRAME) frame(a, 0F, t);
        float yaw = 0F;
        for (; t < 45.0; t += FRAME) {
            yaw += 5F * (float) FRAME;
            frame(a, yaw, t);
        }
        assertTrue(a.moveFeet);
        // crouched (or in the air, a dash): out, the feet the lock's again
        a.turn(yaw, yaw, 1F, t);
        a.moves(false, t, yaw);
        assertFalse(a.moveFeet);
        for (t += FRAME; t < 60.0; t += FRAME) {
            a.turn(yaw, yaw, 1F, t);
            a.moves(false, t, yaw);
            a.feet(0.0, 0.0, 1F, t);
        }
        assertEquals(0, a.moving().length);
        assertEquals(0F, a.legTwist, 0F);
    }

    @Test
    void aLookBackWithinATickChangesNothing() {
        BotAnimator a = new BotAnimator();
        double t = 0.0;
        for (; t < 30.0; t += FRAME) frame(a, 0F, t);
        float yaw = 0F;
        for (; t < 40.0; t += FRAME) {
            yaw = Math.min(90F, yaw + 5F * (float) FRAME);
            frame(a, yaw, t);
        }
        BotAnimator.Moving[] before = a.moving();
        assertEquals(1, before.length);
        a.moves(true, t - 0.6, yaw);
        BotAnimator.Moving[] after = a.moving();
        assertEquals(1, after.length);
        assertEquals(before[0].t(), after[0].t(), 0F);
        assertEquals(before[0].w(), after[0].w(), 0F);
    }

    @Test
    void aSnapComingStartsNoMove() {
        BotAnimator a = new BotAnimator();
        double t = 0.0;
        for (; t < 30.0; t += FRAME) frame(a, 0F, t);
        // the aim sent 90 round at once, the body drawn going there slowly: no move
        for (; t < 60.0; t += FRAME) {
            a.turn(Math.min(90F, a.bodyYaw + 5F * (float) FRAME), 0F, 1F, t);
            a.moves(true, t, 90F);
            a.feet(0.0, 0.0, 1F, t);
            if (a.bodyYaw < 55F) assertEquals(0, a.moving().length, "at " + a.bodyYaw);
        }
    }

    @Test
    void aSlowTurnOnTheSpotStepsAsPeopleDoThenStandsOnItsFeet() {
        BotAnimator a = new BotAnimator();
        double t = 0.0;
        for (; t < 30.0; t += FRAME) frame(a, 0F, t);
        assertTrue(a.feetOut.w > 0.9F, "standing on its feet");
        // a quarter turn to its right at 100 degrees a second
        float yaw = 0F;
        boolean held = false, lifted = false;
        BotAnimator.Moving[] seen = BotAnimator.NO_MOVES;
        for (; t < 60.0; t += FRAME) {
            yaw = Math.min(90F, yaw + 5F * (float) FRAME);
            frame(a, yaw, t);
            held |= a.moveFeet;
            // a foot the clip lifts, the other down
            lifted |= a.moveFeet && a.moveDown[0] != a.moveDown[1];
            if (a.moving().length > 0) seen = a.moving();
            assertTrue(a.feetOut.w > 0.9F, "the lock stays on");
        }
        assertTrue(held, "the move held the legs");
        assertTrue(lifted, "the clip's steps");
        assertTrue(seen.length > 0 && seen[0].t() > 0.6F, "played on as it turned");
        // stood: on to both feet down, out, the feet standing
        for (; t < 120.0; t += FRAME) frame(a, 90F, t);
        assertEquals(0, a.moving().length);
        assertFalse(a.moveFeet);
    }

    @Test
    void aTurnPastAQuarterGoesOverToTheHalf() {
        BotAnimator a = new BotAnimator();
        double t = 0.0;
        for (; t < 30.0; t += FRAME) frame(a, 0F, t);
        float yaw = 0F, swung = 0F;
        boolean both = false;
        double[][] before = new double[2][];
        boolean[] wasUp = new boolean[2];
        for (; t < 75.0; t += FRAME) {
            yaw = Math.max(-180F, yaw - 6F * (float) FRAME);
            frame(a, yaw, t);
            both |= a.moving().length == 2;
            // where the lock has each foot in the world: a foot down stays, one in a step does not jump, the clip going
            // over from the quarter to the half or not
            for (int k = 0; k < 2; k++) {
                double[] w = BotAnimator.toWorld(a.feetOut.at[k * 4], a.feetOut.at[k * 4 + 1], a.bodyYaw);
                boolean up = a.lifted(k);
                if (before[k] != null && a.moveFeet) {
                    double d = Math.hypot(w[0] - before[k][0], w[1] - before[k][1]);
                    if (!up && !wasUp[k]) assertEquals(0.0, d, 1e-5, "foot " + k + " down moved at " + t);
                    else swung = (float) Math.max(swung, d);
                }
                before[k] = a.moveFeet ? w : null;
                wasUp[k] = up;
            }
        }
        assertTrue(both, "the quarter fading out over the half");
        assertTrue(swung > 0F && swung < 0.04F, "a foot in a step went " + swung + " a frame");
    }

    @Test
    void anAimSnappingRoundGoesOnTheFeetsOwnSteps() {
        BotAnimator a = new BotAnimator();
        double t = 0.0;
        for (; t < 30.0; t += FRAME) frame(a, 0F, t);
        float yaw = 0F;
        for (; t < 50.0; t += FRAME) {
            yaw = Math.min(180F, yaw + 30F * (float) FRAME);
            frame(a, yaw, t);
            assertEquals(0, a.moving().length, "no move at " + a.yawRate + " a second");
        }
    }

    @Test
    void oldMovesAreTheFeetsOwnSteps() {
        System.setProperty("ntmgunfeel.botMoves", "old");
        try {
            assertFalse(BotMoves.on());
            BotAnimator a = new BotAnimator();
            double t = 0.0;
            for (; t < 30.0; t += FRAME) frame(a, 0F, t);
            float yaw = 0F;
            for (; t < 60.0; t += FRAME) {
                yaw = Math.min(90F, yaw + 5F * (float) FRAME);
                frame(a, yaw, t);
                assertEquals(0, a.moving().length);
            }
        } finally {
            System.clearProperty("ntmgunfeel.botMoves");
        }
    }

    @Test
    void theLegsAreTheMovesAndTheHipsGoDownAsFarAsItsLegsAreMadeFor() {
        BotMoves.Move m = BotMoves.get(BotMoves.TURN_90);
        float[] arms = { 1F, 0F, 0F, 0F };
        BotPoser.Parts p = BotPoserTest.parts(), own = BotPoserTest.parts();
        BotAnimator.Moving[] moves = { new BotAnimator.Moving(m.clip(), 0.5F, 1F, m.drop(), 0F) };
        BotPoser.apply(p, new BotAnimator.Frame(0F, 0F, arms, null, BotAnimator.NO_ACTS, BotAnimator.NO_HITS, 0F, 9, 0F, 0F, -1F,
                0F, 0F, 1F, 0F, 0F, Float.NaN, Float.NaN, null, Float.NaN, null, moves), 0F, 0F, 0F);
        BotPoser.apply(own, new BotAnimator.Frame(0F, 0F, arms, null, BotAnimator.NO_ACTS, BotAnimator.NO_HITS, 0F, 9, 0F, 0F, -1F,
                0F, 0F, 1F, 0F, 0F, Float.NaN, Float.NaN, null, Float.NaN, null), 0F, 0F, 0F);
        float[] r = new float[3];
        assertTrue(m.clip().rotation("right_leg", 0.5F, r));
        assertEquals(Mth.DEG_TO_RAD * r[0], p.rightLeg().xRot, 1e-5F);
        assertEquals(Mth.DEG_TO_RAD * r[1], p.rightLeg().yRot, 1e-5F);
        assertTrue(m.clip().rotation("left_shin", 0.5F, r));
        assertEquals(Mth.DEG_TO_RAD * r[0], p.leftShin().xRot, 1e-5F);
        assertEquals(own.rightLeg().y + m.drop(), p.rightLeg().y, 1e-4F);
        assertEquals(own.head().y + m.drop(), p.head().y, 1e-4F);
    }
}
