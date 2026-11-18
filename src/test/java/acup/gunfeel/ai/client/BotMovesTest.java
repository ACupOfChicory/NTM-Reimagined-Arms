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

    /**
     * A mob facing +z (yaw 0) going {@code steps} (blocks a tick along +z; {@code aside} along -x, its right), three
     * frames a tick laid as BotAnimator.frame lays them, the pose's ankles the move's (pose); calls {@code each} after
     * each frame with the tick and the place drawn (z, x)
     */
    static void walk(BotAnimator a, double[] steps, double aside, java.util.function.BiConsumer<Integer, double[]> each) {
        walk(a, steps, aside, now -> 0F, each);
    }

    /** ... its body drawn going to yaw(now) */
    static void walk(BotAnimator a, double[] steps, double aside, java.util.function.DoubleFunction<Float> yawAt,
            java.util.function.BiConsumer<Integer, double[]> each) {
        double[] right = new double[steps.length];
        for (int n = 0; n < steps.length; n++) right[n] = steps[n] * aside;
        walk(a, steps, right, yawAt, each);
    }

    /** ... going {@code right[n]} a tick to its right (along -x) too */
    static void walk(BotAnimator a, double[] steps, double[] right, java.util.function.DoubleFunction<Float> yawAt,
            java.util.function.BiConsumer<Integer, double[]> each) {
        double z = 0.0, x = 0.0;
        for (int n = 0; n < steps.length; n++) {
            double zo = z, xo = x;
            z += steps[n];
            x -= right[n];
            for (int i = 0; i < 3; i++) {
                double now = n + i / 3.0, u = i / 3.0, dz = zo + (z - zo) * u, dx = xo + (x - xo) * u;
                float yaw = yawAt.apply(now);
                a.turn(yaw, yaw, 1F, now);
                a.move(x - xo, z - zo, a.bodyYaw, now, BotGaitOverride.of(0));
                a.moves(true, a.still() >= 1F - 1e-4F, now, yaw, dx, dz);
                a.feet(dx, dz, Math.max(a.still(), a.pathFeet), now);
                pose(a);
                each.accept(n, new double[] { dz, dx });
            }
        }
    }

    /** the pose's ankles (Feet.neutral, as BotPoser writes them) the move's legs, else a stand's */
    static void pose(BotAnimator a) {
        BotAnimator.Moving[] mv = a.moving();
        float[] n = a.feetOut.neutral;
        if (mv.length == 0) {
            System.arraycopy(new float[] { -1.9F, 24F, 0F, 1.9F, 24F, 0F }, 0, n, 0, 6);
            return;
        }
        // by the move's weight from the legs straight (as the poser lays it over the gait)
        BotPoser.Parts p = BotPoserTest.parts();
        BotPoser.set(p, mv[0].clip(), mv[0].t(), mv[0].w(), BotPoser.LEGS, new float[3]);
        BotPoser.twistLegs(p, mv[0].w() * mv[0].twist());
        org.joml.Vector3f v = new org.joml.Vector3f();
        BotPoser.ankle(p.rightLeg(), p.rightShin(), v);
        n[0] = v.x;
        n[1] = v.y;
        n[2] = v.z;
        BotPoser.ankle(p.leftLeg(), p.leftShin(), v);
        n[3] = v.x;
        n[4] = v.y;
        n[5] = v.z;
    }

    @Test
    void aTouchOfNoLengthIsNoStance() {
        // the recordings' first or last sample with a foot on the floor (a turn's lifting foot at its start: both feet
        // down there, it came in from nought and played ahead of the body)
        for (String name : new String[] { "turn90_right_swat", "turn90_left_stand", "turn180_right_cmu69_14", "start_run_cmu143_03" }) {
            BotMoves.Move m = BotMoves.get(name);
            assertNotNull(m, name);
            for (float[] s : m.stances())
                for (int i = 0; i + 1 < s.length; i += 2) assertTrue(s[i + 1] > s[i], name + ": a stance " + s[i] + ".." + s[i + 1]);
        }
        // a turn comes in where both its feet first stand
        assertEquals(0.4F, BotMoves.get("turn90_right_swat").downFrom(0F), 1e-4F);
    }

    @Test
    void aRunTurnedBackPlaysNoStopAndNoLegJumps() {
        // the hit stand's clips: a walk, a run (0.33 a tick), then back at once (0.10 a tick) facing the same way;
        // the whole frame as BotAnimator.frame lays it, 100 frames a tick. The heading comes round over some ticks:
        // the step going back is no stop
        BotAnimator a = new BotAnimator();
        double z = 0, zo = 0;
        float[] last = null;
        float worst = 0F;
        String where = "";
        int per = 100;
        for (int i = 60 * per; i < 115 * per; i++) {
            int tick = i / per;
            float partial = (i % per) / (float) per;
            if (i % per == 0) {
                zo = z;
                z -= tick < 70 ? 0.19 : tick < 95 ? 0.33 : -0.10; // facing -z (yaw 180): ahead is -z
            }
            double now = tick + partial, at = zo + (z - zo) * partial;
            a.turn(180F, 180F, 1F, now);
            a.move(0, z - zo, a.bodyYaw, now, BotGaitOverride.of(1), tick);
            BotAnimator.Frame f = a.step(new acup.gunfeel.ai.AiPose(acup.gunfeel.ai.AiPose.STAND, (byte) 0, (byte) 0, 0, acup.gunfeel.ai.AiPose.HOLD, acup.gunfeel.ai.AiPose.NONE, 0, (short) 0, acup.gunfeel.ai.AiPose.FIGHT),
                    tick, partial, 0, false, (float) now, 1);
            a.moves(true, a.still() >= 1F - 1e-4F, now, 180F, 0.0, at);
            a.feet(0.0, at, Math.max(a.still(), a.pathFeet), now);
            if (tick >= 95) assertEquals(0, a.moving().length, "a move going back at " + now);
            BotAnimator.Frame g = new BotAnimator.Frame(f.crouch(), f.lean(), f.arms(), f.act(), f.fading(), f.hits(), f.idleSec(), f.entity(),
                    a.speed, a.heading, -1F, a.walk, a.walkPos, f.alert(), a.air, f.dashed(), a.bodyYaw, a.headYaw, a.feetOut.snapshot(), a.gait,
                    null, a.moving());
            BotPoser.Parts p = BotPoserTest.parts();
            BotPoser.apply(p, g, 0F, g.walk(), g.walkPos());
            float[] legs = { p.rightLeg().xRot, p.rightLeg().yRot, p.rightLeg().zRot, p.rightShin().xRot, p.rightShin().yRot, p.rightShin().zRot,
                    p.leftLeg().xRot, p.leftLeg().yRot, p.leftLeg().zRot, p.leftShin().xRot, p.leftShin().yRot, p.leftShin().zRot };
            if (last != null) for (int k = 0; k < 4; k++) {
                org.joml.Quaternionf qa = new org.joml.Quaternionf().rotationZYX(last[k * 3 + 2], last[k * 3 + 1], last[k * 3]);
                org.joml.Quaternionf qb = new org.joml.Quaternionf().rotationZYX(legs[k * 3 + 2], legs[k * 3 + 1], legs[k * 3]);
                float d = 2F * (float) Math.acos(Math.min(1F, Math.abs(qa.dot(qb))));
                if (d > worst) {
                    worst = d;
                    where = String.format(java.util.Locale.ROOT, "part %d at %.2f", k, now);
                }
            }
            last = legs;
        }
        // the sprint's own shin swings 2.5 radians a tick at full speed (0.025 a frame here: BotMoveClipsTest); a
        // stop's legs taken in half a tick by the lock went 4.8
        assertTrue(worst < 0.04F, "a leg jumped " + worst + " rad, " + where);
    }

    /** standing 30 ticks, then going so */
    static double[] fromStanding(double... then) {
        double[] out = new double[30 + then.length];
        System.arraycopy(then, 0, out, 30, then.length);
        return out;
    }

    static double[] repeat(double step, int ticks) {
        double[] out = new double[ticks];
        java.util.Arrays.fill(out, step);
        return out;
    }

    static double[] concat(double[]... parts) {
        return java.util.Arrays.stream(parts).flatMapToDouble(java.util.Arrays::stream).toArray();
    }

    @Test
    void aStartsAndAStopsStepsGoUpAsHighAsATurnsAtLeast() {
        // a walk off (walk_turn90: 0.002 .. 0.15, the start's first step low in the clip) and a stop after it
        BotAnimator a = new BotAnimator();
        double[] steps = fromStanding(concat(new double[] { 0.007, 0.012, 0.017, 0.022, 0.03, 0.04, 0.05, 0.07, 0.09, 0.11, 0.13 },
                repeat(0.15, 30), new double[] { 0.07, 0.07, 0.06, 0.01, 0.01 }, repeat(0.0, 12)));
        float[] most = { Float.NaN, Float.NaN };
        int[] stepped = { 0 };
        walk(a, steps, 0.0, (n, p) -> {
            float[] y = a.feetOut.neutral;
            for (int k = 0; k < 2; k++) {
                // over the foot it stands on (the floor, the clip's: both its legs under the same hips)
                if (a.lifted(k) && !a.lifted(1 - k) && a.moveFeet) {
                    float up = y[(1 - k) * 3 + 1] - y[k * 3 + 1] + a.feetOut.at[k * 4 + 2];
                    most[k] = Float.isNaN(most[k]) ? up : Math.max(most[k], up);
                } else if (!a.lifted(k) && !Float.isNaN(most[k])) {
                    assertTrue(most[k] >= 0.9F * BotAnimator.MOVE_LIFT, "foot " + k + " stepped up " + most[k] + " px at " + n);
                    stepped[0]++;
                    most[k] = Float.NaN;
                }
            }
        });
        assertTrue(stepped[0] >= 2, stepped[0] + " steps");
    }

    @Test
    void aStartFromStandingStepsOffItsFeetStandingTillTheGaitGoesOnFromItsStep() {
        // the mob AI's walk off (BotFeelStand start_stop): 0.03, 0.03, 0.05, then 0.16 .. 0.19
        BotAnimator a = new BotAnimator();
        double[] steps = fromStanding(concat(new double[] { 0.03, 0.03, 0.05, 0.16, 0.16, 0.16, 0.2 }, repeat(0.19, 30)));
        int[] firstTick = { -1 }, played = { 0 };
        float[] clipSlid = { 0F };
        double[][] at = new double[2][], lock = new double[2][];
        BotMoves.Move[] start = { null };
        walk(a, steps, 0.0, (n, p) -> {
            BotAnimator.Moving[] mv = a.moving();
            if (mv.length == 1 && firstTick[0] < 0) {
                firstTick[0] = n;
                start[0] = BotMoves.start(false).clip() == mv[0].clip() ? BotMoves.start(false) : BotMoves.start(true);
            }
            if (a.moveFeet && mv.length == 1 && mv[0].w() >= 1F) {
                played[0]++;
                // the clip's own feet down stand where they came down (played by the way gone)
                double[][] now = ankles(mv[0], a.bodyYaw);
                for (int k = 0; k < 2; k++) {
                    double wx = p[1] + now[k][0], wz = p[0] + now[k][1];
                    // and the lock's
                    double[] l = BotAnimator.toWorld(a.feetOut.at[k * 4], a.feetOut.at[k * 4 + 1], a.bodyYaw);
                    l = new double[] { p[1] + l[0], p[0] + l[1] };
                    float[] st = start[0].stances()[k];
                    boolean stands = false;
                    for (int i = 0; i + 1 < st.length; i += 2) stands |= mv[0].t() > st[i] + 0.02F && mv[0].t() < st[i + 1] - 0.02F;
                    if (!stands) {
                        at[k] = lock[k] = null;
                        continue;
                    }
                    if (at[k] == null) {
                        at[k] = new double[] { wx, wz };
                        lock[k] = l;
                    }
                    float d = (float) (Math.hypot(wx - at[k][0], wz - at[k][1]) * 1000.0);
                    clipSlid[0] = Math.max(clipSlid[0], d);
                    assertEquals(lock[k][0], l[0], 1e-5, "the lock's foot " + k + " at tick " + n);
                    assertEquals(lock[k][1], l[1], 1e-5, "the lock's foot " + k + " at tick " + n);
                }
            }
        });
        assertEquals(30, firstTick[0], "it steps off at once");
        assertTrue(played[0] > 10, "frames played " + played[0]);
        // the recording's own feet slide some millimetres a stance (tools/motion); the first frames are its pace bound
        assertTrue(clipSlid[0] < 15F, "a foot of the clip down went " + clipSlid[0] + " mm");
        // gone over to the gait, on the run: no move left, the feet the gait's
        assertEquals(0, a.moving().length);
        assertFalse(a.moveFeet);
        assertEquals(0F, a.feetOut.w, 1e-3F);
    }

    @Test
    void theGaitGoesOnFromTheStartsStepItsLegsNearest() {
        BotAnimator a = new BotAnimator();
        double[] steps = fromStanding(concat(new double[] { 0.03, 0.03, 0.05, 0.16, 0.16, 0.16, 0.2 }, repeat(0.19, 30)));
        float[] off = { -1F };
        boolean[] was = { false };
        walk(a, steps, 0.0, (n, p) -> {
            BotAnimator.Moving[] mv = a.moving();
            boolean fading = mv.length == 1 && mv[0].w() < 1F && n > 33;
            if (fading && !was[0]) {
                // the frame it went out: the gait's legs where walkPos is now against the clip's
                float[] g = new float[12], c = new float[12], s = new float[3];
                assertTrue(BotPoser.gaitLegs(a.speed, a.gait, a.heading, a.walkPos, null, g, s));
                assertTrue(BotAnimator.legs(mv[0].clip(), mv[0].t(), c, s));
                float best = Float.MAX_VALUE;
                for (int i = 0; i < 96; i++) {
                    assertTrue(BotPoser.gaitLegs(a.speed, a.gait, a.heading, i / 96F * BotPoser.CYCLE, null, g, new float[3]));
                    best = Math.min(best, BotAnimator.legsOff(g, c));
                }
                assertTrue(BotPoser.gaitLegs(a.speed, a.gait, a.heading, a.walkPos, null, g, s));
                off[0] = BotAnimator.legsOff(g, c);
                // the nearest of the cycle, a frame's walk on at most
                assertTrue(off[0] <= best * 1.5F + 50F, "the gait's legs " + off[0] + " off the clip's, at best " + best);
            }
            was[0] = fading;
        });
        assertTrue(off[0] >= 0F, "gone over to the gait");
    }

    @Test
    void aStopFromAWalkHoldsTheFeetFromTheTickItSlowsDownAndStandsOnThem() {
        // the mob AI's stop from a walk (BotFeelStand start_stop): 0.19, then 0.07, 0.07, 0.06, 0.01, 0.01
        BotAnimator a = new BotAnimator();
        double[] steps = concat(repeat(0.19, 40), new double[] { 0.07, 0.07, 0.06, 0.01, 0.01 }, repeat(0.0, 30));
        int[] first = { -1 }, kept = { 0 };
        float[] w0 = { -1F }, wIn = { -1F };
        double[][] at = new double[2][];
        walk(a, steps, 0.0, (n, p) -> {
            if (a.moveFeet && first[0] < 0) {
                first[0] = n;
                wIn[0] = a.feetOut.w;
            }
            // the lock takes the feet within FEET_NOW (half a tick, not in a frame: a straight knee would snap to the
            // lock's soft bend): from the next tick on, held
            if (first[0] >= 0 && n == first[0] + 1 && w0[0] < 0F) w0[0] = a.feetOut.w;
            for (int k = 0; k < 2; k++) {
                double[] l = BotAnimator.toWorld(a.feetOut.at[k * 4], a.feetOut.at[k * 4 + 1], a.bodyYaw);
                l = new double[] { p[1] + l[0], p[0] + l[1] };
                if (!a.moveFeet || !a.moveDown[k] || n <= first[0]) {
                    at[k] = null;
                    continue;
                }
                if (at[k] == null) at[k] = l;
                assertEquals(at[k][0], l[0], 1e-5, "foot " + k + " at tick " + n);
                assertEquals(at[k][1], l[1], 1e-5, "foot " + k + " at tick " + n);
                kept[0]++;
            }
        });
        assertEquals(41, first[0], "it stops from the second tick it is slow (one is the AI finding its way on)");
        assertEquals(1F, w0[0], 1e-4F, "the feet held by the next tick");
        assertTrue(wIn[0] < 0.9F, "the feet taken in a frame (" + wIn[0] + ")");
        assertTrue(kept[0] > 6, "feet kept " + kept[0]);
        // stood: the move gone, the feet standing (the lock's), drawn standing
        assertEquals(0, a.moving().length);
        assertEquals(1F, a.feetOut.w, 1e-4F);
        assertEquals(1F, a.still(), 1e-3F);
    }

    @Test
    void theStopPlayedHasItsLegsNearestTheGaits() {
        BotAnimator a = new BotAnimator();
        double[] steps = concat(repeat(0.19, 40), new double[] { 0.07, 0.07, 0.06, 0.01, 0.01 }, repeat(0.0, 10));
        float[] off = { -1F, Float.MAX_VALUE };
        float[][] gaitAt = { null };
        walk(a, steps, 0.0, (n, p) -> {
            if (n == 39) {
                float[] g = new float[12];
                assertTrue(BotPoser.gaitLegs(a.speed, a.gait, a.heading, a.walkPos, BotGaitOverride.of(0), g, new float[3]));
                gaitAt[0] = g;
            }
            BotAnimator.Moving[] mv = a.moving();
            if (mv.length == 1 && off[0] < 0F) {
                float[] c = new float[12], s = new float[3];
                assertTrue(BotAnimator.legs(mv[0].clip(), mv[0].t(), c, s));
                off[0] = BotAnimator.legsOff(gaitAt[0], c);
                for (BotMoves.Move m : BotMoves.stops()) {
                    assertTrue(BotAnimator.legs(m.clip(), m.timeAt(m.total() - BotAnimator.STOP_REST * 0.07F), c, s));
                    off[1] = Math.min(off[1], BotAnimator.legsOff(gaitAt[0], c));
                }
            }
        });
        assertTrue(off[0] >= 0F, "stopped by a clip");
        // the gait goes on a third of a tick between: its legs a little on
        assertTrue(off[0] <= off[1] * 1.5F + 100F, "its legs " + off[0] + " off the gait's, the nearest stop's " + off[1]);
    }

    @Test
    void stoppedAndTurningRoundOrGoingBackTheFeetAreTheLocksAgainBeforeTheyWouldCross() {
        // (aside: run so long, and gone aside so many ticks after slowing down, that the stop has a foot in a step or
        // setting it down as it goes aside)
        for (int w = 0; w < 26; w++) {
            int way = Math.min(w, 2), run = 40 + Math.max(0, w - 2) % 8, after = 2 + Math.max(0, w - 2) / 8;
            boolean back = way == 1, aside = way == 2;
            BotAnimator a = new BotAnimator();
            // a run stopped (BotFeelStand start_stop: 0.04, 0.03, 0.01), then turned round over two ticks, or going back;
            // or (start_stop's last stop) slowed down to 0.06 a tick and gone aside as fast the third tick
            double[] steps = aside ? concat(repeat(0.32, run), repeat(0.06, after + 1), repeat(0.0, 24))
                    : concat(repeat(0.32, 40), new double[] { 0.21, 0.20, 0.19, 0.04, 0.03, 0.01, 0.0 },
                            repeat(back ? -0.05 : 0.0, 12), repeat(0.0, 10));
            double[] right = new double[steps.length];
            if (aside) for (int n = run + after + 1; n < run + after + 13; n++) right[n] = 0.06;
            float[] most = { 0F }, wWas = { 0F };
            boolean[] stopped = { false }, held = { false };
            double[][] last = new double[2][];
            walk(a, steps, right, now -> way > 0 || now < 47.0 ? 0F : (float) Math.min(180.0, (now - 47.0) * 90.0), (n, p) -> {
                stopped[0] |= a.moveFeet;
                BotAnimator.Moving[] mv = a.moving();
                BotMoves.Move stop = mv.length != 1 ? null
                        : BotMoves.stops().stream().filter(m -> m.clip() == mv[0].clip()).findFirst().orElse(null);
                // a stop standing lets go of the feet with both down in the clip (it sets a foot in a step down first;
                // gone out on the move, its weight goes first)
                if (held[0] && !a.moveFeet && stop != null)
                    assertTrue(stop.bothDown(mv[0].t()), "let go of a foot in the air at " + n + " (" + way + ", run " + run + ", aside after " + after + ")");
                held[0] = a.moveFeet && stop != null;
                // the feet a move holds turned with the body no farther than MOVE_TWIST_MOST from where it stopped
                // (stood and turned round, the turn on the spot may step the feet after it: not the stop's)
                if (a.moveFeet && stop != null && n >= 47) most[0] = Math.max(most[0], Math.abs(a.bodyYaw));
                for (int k = 0; k < 2; k++) {
                    double[] l = BotAnimator.toWorld(a.feetOut.at[k * 4], a.feetOut.at[k * 4 + 1], a.bodyYaw);
                    l = new double[] { p[1] + l[0], p[0] + l[1] };
                    // the lock's feet go on from where they were (the lock's own steps: no more than a step a frame)
                    // (held the frame before too: a lock let go has its feet where it last had them)
                    if (last[k] != null && a.feetOut.w > 0.5F && wWas[0] > 0.5F)
                        assertTrue(Math.hypot(l[0] - last[k][0], l[1] - last[k][1]) < 0.15, "foot " + k + " jumped at " + n + " (" + way + ", run " + run + ", aside after " + after + ")");
                    last[k] = l;
                }
                wWas[0] = a.feetOut.w;
            });
            assertTrue(stopped[0], "stopped by a clip");
            assertTrue(most[0] <= BotAnimator.MOVE_TWIST_MOST + 10F, "turned " + most[0] + " over the feet it held");
            if (way > 0) assertEquals(0, a.moving().length);
        }
    }

    @Test
    void feetAMoveLetsGoFarFromThePosesAreSteppedThereNotDragged() {
        BotAnimator a = new BotAnimator();
        double now = 0.0;
        // standing: the lock has its feet
        for (int i = 0; i < 30; i++, now += 1.0 / 3.0) {
            a.turn(0F, 0F, 1F, now);
            pose(a);
            a.feet(0.0, 0.0, 1F, now);
        }
        // a move holds them (both down) as the body goes on 0.6 blocks (a stop's last steps, the clip behind the body),
        // then lets go: each foot goes on from where it is, stepped
        a.moveFeet = true;
        a.moveDown[0] = a.moveDown[1] = true;
        double z = 0.0, most = 0.0;
        double[][] last = new double[2][];
        for (int i = 0; i < 66; i++, now += 1.0 / 3.0) {
            if (i < 6) z += 0.1;
            a.moveFeet = i < 6;
            a.turn(0F, 0F, 1F, now);
            a.feet(0.0, z, 1F, now);
            for (int k = 0; k < 2; k++) {
                double[] l = BotAnimator.toWorld(a.feetOut.at[k * 4], a.feetOut.at[k * 4 + 1], a.bodyYaw);
                l = new double[] { l[0], z + l[1] };
                if (last[k] != null) most = Math.max(most, Math.hypot(l[0] - last[k][0], l[1] - last[k][1]));
                last[k] = l;
            }
        }
        // (the lock's own step of 0.6 blocks goes 0.12 a frame at most)
        assertTrue(most < 0.15, "a foot went " + most + " blocks in a frame");
        // and under the body again
        for (int k = 0; k < 2; k++) assertTrue(Math.abs(last[k][1] - z) < 0.1, "foot " + k + " " + (last[k][1] - z) + " blocks off");
    }

    @Test
    void setGoingFastAtOnceStartsNoMove() {
        // BotFeelStand walk_crouch_walk: 0.19 a tick from the first
        BotAnimator a = new BotAnimator();
        boolean[] moved = { false };
        walk(a, fromStanding(repeat(0.19, 20)), 0.0, (n, p) -> moved[0] |= a.moving().length > 0);
        assertFalse(moved[0]);
    }

    @Test
    void steppingOffTurningRoundStartsNoMove() {
        BotAnimator a = new BotAnimator();
        boolean[] moved = { false };
        // turning round at 400 degrees a second as it steps off (an overshot stop walked back)
        walk(a, fromStanding(repeat(0.05, 20)), 0.0, now -> now < 27.0 ? 0F : (float) Math.min(180.0, (now - 27.0) * 20.0),
                (n, p) -> moved[0] |= a.moving().length > 0);
        assertFalse(moved[0]);
    }

    @Test
    void aTickStandingOnTheWayStopsNothing() {
        BotAnimator a = new BotAnimator();
        boolean[] moved = { false };
        walk(a, concat(repeat(0.19, 40), new double[] { 0.0 }, repeat(0.19, 20)), 0.0, (n, p) -> moved[0] |= n >= 40 && a.moving().length > 0);
        assertFalse(moved[0]);
    }

    @Test
    void theWalkGoesOnWithTheStartsStepsAndOnFromThemAsItGoesOut() {
        BotAnimator a = new BotAnimator();
        double[] steps = fromStanding(concat(new double[] { 0.03, 0.03, 0.05, 0.16, 0.16, 0.16, 0.2 }, repeat(0.19, 30)));
        float[] last = { Float.NaN }, most = { 0F };
        boolean[] seen = { false };
        walk(a, steps, 0.0, (n, p) -> {
            seen[0] |= a.moving().length > 0;
            // from its second tick on (its first comes to the clip's step from a stand, the walk still nought)
            if (n >= 32 && !Float.isNaN(last[0])) {
                float d = a.walkPos - last[0];
                assertTrue(d >= -1e-4F, "walkPos back by " + d + " at tick " + n);
                most[0] = Math.max(most[0], d);
            }
            last[0] = a.walkPos;
        });
        assertTrue(seen[0]);
        // a frame of the walk's own pace (0.19 a tick: some 0.25) or the clip's, never a jump
        assertTrue(most[0] < 0.8F, "walkPos on by " + most[0] + " in a frame");
    }

    @Test
    void aStartsAndAStopsStepsGoOnThroughTheWalksCycle() {
        java.util.List<BotMoves.Move> all = new java.util.ArrayList<>(BotMoves.stops());
        all.add(BotMoves.start(false));
        all.add(BotMoves.start(true));
        float[] r = new float[3], l = new float[3];
        for (BotMoves.Move m : all) {
            float[] p = BotMoves.passes(m);
            // a start steps off and on; a quick stop may pass once and come to stand
            assertTrue(p.length >= ("start".equals(m.kind()) ? 4 : 2), m.name() + ": passes " + p.length / 2);
            float before = -Float.MAX_VALUE;
            for (float t = 0F; t <= m.clip().length(); t += 0.01F) {
                float c = m.cycle(t);
                assertTrue(c >= before - 1e-5F, m.name() + " back at " + t);
                before = c;
            }
            for (int i = 0; i < p.length; i += 2) {
                // the legs pass there: the right going forward a quarter on, the left three quarters
                m.clip().rotation("right_leg", p[i] + 0.03F, r);
                m.clip().rotation("left_leg", p[i] + 0.03F, l);
                float frac = p[i + 1] - (float) Math.floor(p[i + 1]);
                assertEquals(frac < 0.5F ? 0.25F : 0.75F, frac, 1e-5F);
                // just after: the right ahead (its xRot the lower) a quarter on
                assertEquals(frac < 0.5F, r[0] < l[0], m.name() + " pass at " + p[i]);
            }
        }
        // mirrored, the other leg goes forward at each pass
        float[] a = BotMoves.passes(BotMoves.start(false)), b = BotMoves.passes(BotMoves.start(true));
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i += 2) assertEquals(a[i], b[i], 0.01F);
    }

    @Test
    void goingAsideOrBackStartsNorStopsAMove() {
        for (double aside : new double[] { 1.0, -1.0 }) {
            // twice as far aside as on (45 degrees each way of +z is the most): 63 degrees, stepping off (0.09 a tick,
            // a start's pace) and stopping (from 0.22)
            for (double[] steps : new double[][] { fromStanding(repeat(0.04, 20)),
                    concat(repeat(0.1, 20), new double[] { 0.03, 0.01 }, repeat(0.0, 10)) }) {
                BotAnimator a = new BotAnimator();
                boolean[] moved = { false };
                walk(a, steps, 2.0 * aside, (n, p) -> moved[0] |= a.moving().length > 0);
                assertFalse(moved[0], "aside " + aside);
            }
        }
        BotAnimator a = new BotAnimator();
        boolean[] moved = { false };
        walk(a, fromStanding(repeat(-0.05, 20)), 0.0, (n, p) -> moved[0] |= a.moving().length > 0);
        assertFalse(moved[0], "back");
    }

    @Test
    void oldMovesNeitherStartNorStop() {
        String was = System.getProperty("ntmgunfeel.botMoves");
        System.setProperty("ntmgunfeel.botMoves", "old");
        try {
            BotAnimator a = new BotAnimator();
            boolean[] moved = { false };
            walk(a, fromStanding(concat(repeat(0.19, 20), new double[] { 0.07, 0.07, 0.06, 0.01 }, repeat(0.0, 10))), 0.0,
                    (n, p) -> moved[0] |= a.moving().length > 0);
            assertFalse(moved[0]);
        } finally {
            if (was == null) System.clearProperty("ntmgunfeel.botMoves");
            else System.setProperty("ntmgunfeel.botMoves", was);
        }
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

    /**
     * the largest turn of a part between two frames (48 a tick: a fast screen) of a mob setting off from a stand to a
     * walk (its steps 0.06, 0.13, then 0.19 a tick, as the client gets them), as BotAnimator.frame draws it: a start
     * under it, the feet's lock holding its feet; its gait the mob's own (BotGaits) or the mod's
     */
    static float startJump(boolean own) {
        if (!own) BotGaitOverride.BY_ENTITY = id -> null;
        try {
            return startJump();
        } finally {
            BotGaitOverride.BY_ENTITY = BotGaitOverride.OWN;
        }
    }

    private static float startJump() {
        BotGaitOverride swap = BotGaitOverride.of(7);
        BotAnimator a = new BotAnimator();
        double x = 0.0, xo = 0.0, ramp[] = { 0.06, 0.13 };
        float yaw = 90F, most = 0F;
        BotPoser.Parts prev = null;
        long last = -1;
        acup.gunfeel.ai.AiPose p = BotPoserTest.pose(acup.gunfeel.ai.AiPose.STAND, 0, 0, 0, acup.gunfeel.ai.AiPose.HOLD,
                acup.gunfeel.ai.AiPose.NONE, 0, 0);
        for (int frame = 0; frame < 48 * 40; frame++) {
            double now = 100 + frame / 48.0;
            long tick = (long) Math.floor(now);
            float partial = (float) (now - tick);
            if (tick != last) {
                last = tick;
                int k = (int) (tick - 120);
                xo = x;
                x -= k < 0 ? 0.0 : k < ramp.length ? ramp[k] : 0.19;
            }
            a.turn(yaw, yaw, 0F, now);
            a.move(x - xo, 0, a.bodyYaw, now, swap, tick);
            a.fly(false, 0, now);
            BotAnimator.Frame f = a.step(p, tick, partial, 0, false, (float) now, 7, false);
            double at = xo + (x - xo) * partial;
            a.moves(true, a.still() >= 1F - 1e-4F, now, yaw, at, 0.0);
            a.feet(at, 0.0, Math.max(a.still(), a.pathFeet), now);
            f = new BotAnimator.Frame(f.crouch(), f.lean(), f.arms(), f.act(), f.fading(), f.hits(), f.idleSec(), f.entity(), a.speed,
                    a.heading, -1F, a.walk, a.walkPos, f.alert(), a.air, f.dashed(), Mth.wrapDegrees(a.bodyYaw), Mth.wrapDegrees(a.headYaw),
                    a.feetOut.snapshot(), a.gait, null, a.moving());
            BotPoser.Parts parts = BotPoserTest.parts();
            BotPoser.apply(parts, f, 0F, a.walk, a.walkPos);
            if (prev != null) most = Math.max(most, BotPoserTest.jump(prev, parts));
            prev = parts;
        }
        return most;
    }

    @Test
    void aRunsStartOffTheFloorDropsNoFootAtOnce() {
        // the run's start (CMU 143_03) leaves the floor with both feet: the step lifted over the foot standing went
        // down to the clip's own in a frame as that one went up, the knee 0.14 rad at once. A part turns 0.05 rad a
        // frame at most here (2.4 a tick: the walk's shin coming in), the mod's own gait or the mob's own
        float own = startJump(false), runs = startJump(true);
        System.out.println("bot moves: a start's most a frame at 48 a tick " + own + " (the mod's gait), " + runs + " (the mob's own)");
        assertTrue(own < 0.07F, "the mod's gait " + own);
        assertTrue(runs < 0.07F, "the mob's own " + runs);
    }

    /**
     * a stand's run to a place, its stop and the AI walking it back over the place it ran past, turning it round in
     * four ticks (BotFeelStand start_stop, ticks 60 to 139): each tick the yaw sent and the place at its end (blocks)
     */
    static final float[] RUN_PAST = {
            -90.0F, 0.000F, 0.000F, -90.0F, 0.000F, 0.000F, -90.0F, 0.000F, 0.000F, -90.0F, 0.000F, 0.000F,
            -90.0F, 0.000F, 0.000F, -90.0F, 0.000F, 0.000F, -90.0F, 0.000F, 0.000F, -90.0F, 0.000F, 0.000F,
            -90.0F, 0.000F, 0.000F, -90.0F, 0.000F, 0.000F, -90.0F, 0.000F, 0.000F, -90.0F, 0.000F, 0.000F,
            -90.0F, 0.051F, 0.000F, -90.0F, 0.101F, 0.000F, -90.0F, 0.152F, 0.000F, -90.0F, 0.426F, 0.000F,
            -90.0F, 0.699F, 0.000F, -90.0F, 0.973F, 0.000F, -90.0F, 1.298F, 0.000F, -90.0F, 1.623F, 0.000F,
            -90.0F, 1.948F, 0.000F, -90.0F, 2.281F, 0.000F, -90.0F, 2.615F, 0.000F, -90.0F, 2.948F, 0.000F,
            -90.0F, 3.283F, 0.000F, -90.0F, 3.618F, 0.000F, -90.0F, 3.952F, 0.000F, -90.0F, 4.287F, 0.000F,
            -90.0F, 4.622F, 0.000F, -90.0F, 4.957F, 0.000F, -90.0F, 5.292F, 0.000F, -90.0F, 5.627F, 0.000F,
            -90.0F, 5.962F, 0.000F, -90.0F, 6.297F, 0.000F, -90.0F, 6.632F, 0.000F, -90.0F, 6.968F, 0.000F,
            -90.0F, 7.303F, 0.000F, -90.0F, 7.638F, 0.000F, -90.0F, 7.973F, 0.000F, -90.0F, 8.308F, 0.000F,
            -90.0F, 8.643F, 0.000F, -90.0F, 8.978F, 0.000F, -90.0F, 9.090F, 0.000F, -90.0F, 9.202F, 0.000F,
            -90.0F, 9.315F, 0.000F, -180.0F, 9.333F, 0.000F, 90.0F, 9.347F, -0.051F, 90.0F, 9.306F, -0.112F,
            90.0F, 9.250F, -0.168F, 90.0F, 9.195F, -0.224F, 2.8F, 9.140F, -0.281F, 2.8F, 9.109F, -0.245F,
            2.8F, 9.078F, -0.210F, -357.2F, 9.047F, -0.174F, -357.2F, 9.039F, -0.126F, -357.2F, 9.032F, -0.077F,
            -357.2F, 9.024F, -0.029F, -357.2F, 9.024F, -0.021F, -357.2F, 9.024F, -0.013F, -357.2F, 9.024F, -0.005F,
            -357.2F, 9.024F, -0.005F, -357.2F, 9.024F, -0.005F, -357.2F, 9.024F, -0.005F, -357.2F, 9.024F, -0.005F,
            -357.2F, 9.024F, -0.005F, -357.2F, 9.024F, -0.005F, -357.2F, 9.024F, -0.005F, -357.2F, 9.024F, -0.005F,
            -357.2F, 9.024F, -0.005F, -357.2F, 9.024F, -0.005F, -357.2F, 9.024F, -0.005F, -357.2F, 9.024F, -0.005F,
            -357.2F, 9.024F, -0.005F, -357.2F, 9.024F, -0.005F, -357.2F, 9.024F, -0.005F, -357.2F, 9.024F, -0.005F,
            -357.2F, 9.024F, -0.005F, -357.2F, 9.024F, -0.005F, -357.2F, 9.024F, -0.005F, -357.2F, 9.024F, -0.005F
    };

    /** the largest turn of a part between two frames (48 a tick) through RUN_PAST, as BotAnimator.frame draws it */
    static float runPastJump(@org.jspecify.annotations.Nullable BotGaitOverride gait) {
        return runPast(gait)[0];
    }

    /** RUN_PAST: the largest turn of a part a frame, and frames with a stop under way from tick 100 to 110 */
    static float[] runPast(@org.jspecify.annotations.Nullable BotGaitOverride gait) {
        BotGaitOverride.BY_ENTITY = id -> gait;
        try {
            BotAnimator a = new BotAnimator();
            acup.gunfeel.ai.AiPose p = BotPoserTest.pose(acup.gunfeel.ai.AiPose.STAND, 0, 0, 0, acup.gunfeel.ai.AiPose.HOLD,
                    acup.gunfeel.ai.AiPose.NONE, 0, 0).withAlert(acup.gunfeel.ai.AiPose.FIGHT);
            BotPoser.Parts prev = null;
            float most = 0F, stopped = 0F;
            for (int k = 3; k < RUN_PAST.length; k += 3) {
                float yaw = RUN_PAST[k];
                double x = RUN_PAST[k + 1], z = RUN_PAST[k + 2], xo = RUN_PAST[k - 2], zo = RUN_PAST[k - 1];
                long tick = 60 + k / 3;
                for (int fr = 0; fr < 48; fr++) {
                    float partial = fr / 48F;
                    double now = tick + partial;
                    float sent = a.sent.at(yaw, now), head = a.sentHead.at(yaw, now);
                    a.turn(sent, head, 1F, now);
                    a.move(x - xo, z - zo, a.bodyYaw, now, BotGaitOverride.of(7), tick);
                    a.fly(false, 0, now);
                    BotAnimator.Frame f = a.step(p, tick, partial, 0, false, (float) now, 7, false);
                    double px = xo + (x - xo) * partial, pz = zo + (z - zo) * partial;
                    a.moves(true, a.still() >= 1F - 1e-4F, now, sent, px, pz);
                    a.feet(px, pz, Math.max(a.still(), a.pathFeet), now);
                    f = new BotAnimator.Frame(f.crouch(), f.lean(), f.arms(), f.act(), f.fading(), f.hits(), f.idleSec(), f.entity(),
                            a.speed, a.heading, -1F, a.walk, a.walkPos, f.alert(), a.air, f.dashed(), Mth.wrapDegrees(a.bodyYaw),
                            Mth.wrapDegrees(a.headYaw), a.feetOut.snapshot(), a.gait, null, a.moving());
                    BotPoser.Parts parts = BotPoserTest.parts();
                    BotPoser.apply(parts, f, 0F, a.walk, a.walkPos);
                    if (prev != null) most = Math.max(most, BotPoserTest.jump(prev, parts));
                    prev = parts;
                    for (BotAnimator.Moving mv : a.moving())
                        if (tick >= 100 && tick < 110 && BotMoves.stops().stream().anyMatch(s -> s.clip() == mv.clip())) stopped++;
                }
            }
            return new float[] { most, stopped };
        } finally {
            BotGaitOverride.BY_ENTITY = BotGaitOverride.OWN;
        }
    }

    @Test
    void aStopTheAIWalksBackLetsItsFeetGo() {
        // the stop gone back has stood, the AI walking it back and round: the lock held its feet whole, a foot it
        // stepped waited where it stood (its step at the turn's pace, six ticks) as the body went off and round, the
        // leg flung out a block, till the lock lost it and put it back in a frame (1.47 rad, 16_56's run under it). Its
        // weight now goes with the stop's as the mob goes on, and a foot far from its place steps quicker: a part turns
        // under 0.25 rad a frame of 48 a tick, whatever the gait
        BotGaitOverride quick = null;
        for (int n = 0; n < BotGaits.count(); n++) {
            BotGaitOverride o = BotGaits.variant(n);
            if ("run_cmu16_56".equals(o.jog()) && "sprint_cmu127_08_long".equals(o.sprint()) && "walk_swat_fw".equals(o.stroll().clip())) quick = o;
        }
        assertNotNull(quick);
        float most = 0F;
        StringBuilder all = new StringBuilder();
        for (BotGaitOverride o : new BotGaitOverride[] { quick, null, BotGaits.variant(0), BotGaits.variant(21) }) {
            float[] r = runPast(o);
            all.append(' ').append(r[0]);
            most = Math.max(most, r[0]);
            // the run past it ends in a stop, whatever the gait
            assertTrue(r[1] > 48F, "a stop played " + r[1] + " frames");
        }
        System.out.println("bot moves: run past and walked back, the most a frame at 48 a tick:" + all);
        assertTrue(most < 0.25F, "a part turned " + all + " rad in a frame");
    }
}
