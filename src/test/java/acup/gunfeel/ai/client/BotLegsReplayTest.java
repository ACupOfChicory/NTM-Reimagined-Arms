package acup.gunfeel.ai.client;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import acup.gunfeel.ai.AiPose;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

// The legs' replay: the AI check's fighters as they moved (AiLegsLog, -PaiLegs=file), each tick through the client's
// walk and pose (BotAnimator, vanilla's leg swing, BotPoser) as the client would draw them; the feet's height over the
// ground and the shins' slant, at cover above all. Only with LEGS_TSV=<that file>; the numbers go to <file>.stats.txt.
class BotLegsReplayTest {
    /** the client gets a mob's place every this many ticks and goes there over as many (EntityType's update interval) */
    static final int SENT = 3;
    /** the foot of a leg straight down, model pixels (the hip at 12, the thigh and the shin 6 each) */
    static final float GROUND = 24F;

    record Row(int fight, long tick, int id, double x, double z, float bodyYaw, AiPose pose, String mode, double spot) {
    }

    @Test
    void replay() throws IOException {
        String file = System.getenv("LEGS_TSV");
        Assumptions.assumeTrue(file != null && Files.exists(Path.of(file)), "no LEGS_TSV");
        Map<String, List<Row>> bots = new LinkedHashMap<>();
        for (String line : Files.readAllLines(Path.of(file), StandardCharsets.UTF_8)) {
            if (line.startsWith("fight")) continue;
            String[] c = line.split("\t");
            AiPose p = new AiPose(Byte.parseByte(c[6]), Byte.parseByte(c[7]), Byte.parseByte(c[8]), Integer.parseInt(c[9]), Byte.parseByte(c[10]),
                    Byte.parseByte(c[11]), Integer.parseInt(c[12]), Short.parseShort(c[13]));
            Row r = new Row(Integer.parseInt(c[0]), Long.parseLong(c[1]), Integer.parseInt(c[2]), Double.parseDouble(c[3]), Double.parseDouble(c[4]),
                    Float.parseFloat(c[5]), p, c[14], Double.parseDouble(c[15]));
            bots.computeIfAbsent(r.fight + "/" + r.id, k -> new ArrayList<>()).add(r);
        }
        Stats all = new Stats(), cover = new Stats(), crouched = new Stats(), upright = new Stats();
        try (PrintWriter frames = new PrintWriter(Files.newBufferedWriter(Path.of(file + ".frames.tsv"), StandardCharsets.UTF_8))) {
            frames.println("fight\ttick\tid\tmode\tatCover\tstep\tspeed\theadingDeg\twalk\twalkPos\tu\tcrouch\tmove\tfootR\tfootL\tshinR\tshinL"
                    + "\tthighRx\tthighLx\tkneeR\tkneeL\tjerk");
            for (List<Row> rows : bots.values()) replay(rows, new Stats[] { all, cover, crouched, upright }, frames);
        }
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "fighters %d; left out, sliding or rolling (their own legs): %d ticks%n", bots.size(), slides[0]));
        out.append(all.report("all ticks"));
        out.append(cover.report("at cover (COVER, PEEK, HOLD, or on the way within 2.5 blocks of its cover)"));
        out.append(crouched.report("at cover, crouched (crouch weight over 0.5)"));
        out.append(upright.report("at cover, upright"));
        System.out.println(out);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(file + ".stats.txt"), StandardCharsets.UTF_8))) {
            w.print(out);
        }
    }

    static final class Stats {
        int ticks, lifted, slanted, lifted3, still, stillLifted, stillMid, stillLong, stillLongMid, moving, side, jerky, offHeading, kicks;
        double footSum, slantSum, jerkSum, moveSum;
        /** the dashes (ticks on the move in a row): how many, their ticks, the share of a step's cycle each went through */
        int dashes, dashTicks;
        double dashCycle;
        final int[] dashHist = new int[8];
        /** the clip weights summed over moving ticks: walk, jog, sprint, back, strafe */
        final double[] clips = new double[5];

        String report(String what) {
            return String.format(Locale.ROOT,
                    "%s: ticks %d; the lower foot over the ground: mean %.2f px, above 2 px %.1f%%, above 3 px %.1f%%; its shin off the upright: mean %.1f deg,"
                            + " over 30 deg %.1f%%%n  still (no step this tick) %d ticks, of them the lower foot above 2 px %.1f%%; moving %d ticks, of them sideways"
                            + " (heading over 45 deg off ahead and back) %.1f%%; clip weights on the move: walk %.2f jog %.2f sprint %.2f back %.2f strafe %.2f%n"
                            + "  dashes %d, ticks %.1f on average, a step's cycle gone through %.2f on average; by ticks 1,2,3,4-5,6-9,10-19,20-39,40+: %s%n"
                            + "  still mid-step (the thighs over 20 deg apart or a foot above 2 px) %.1f%% of still ticks, %.1f%% of %d still 5 ticks or more; a leg's jerk (largest change of a"
                            + " leg's angle in a tick): mean %.1f deg, over 15 deg %.1f%% of ticks; the clips' weight (move) mean %.2f%n"
                            + "  on the move, the clips' heading over 45 deg off the way it goes %.1f%%; a foot's kick (its speed changing over 3 px a tick in a tick) %.1f%% of ticks%n",
                    what, ticks, ticks == 0 ? 0 : footSum / ticks, pct(lifted, ticks), pct(lifted3, ticks), ticks == 0 ? 0 : slantSum / ticks, pct(slanted, ticks), still,
                    pct(stillLifted, still), moving, pct(side, moving), clips[0] / Math.max(1, moving), clips[1] / Math.max(1, moving), clips[2] / Math.max(1, moving),
                    clips[3] / Math.max(1, moving), clips[4] / Math.max(1, moving), dashes, dashes == 0 ? 0 : (double) dashTicks / dashes,
                    dashes == 0 ? 0 : dashCycle / dashes, java.util.Arrays.toString(dashHist), pct(stillMid, still), pct(stillLongMid, stillLong), stillLong, ticks == 0 ? 0 : jerkSum / ticks,
                    pct(jerky, ticks), ticks == 0 ? 0 : moveSum / ticks, pct(offHeading, moving), pct(kicks, ticks));
        }

        void dash(int ticks, double cycle) {
            dashes++;
            dashTicks += ticks;
            dashCycle += cycle;
            int b = ticks <= 3 ? ticks - 1 : ticks <= 5 ? 3 : ticks <= 9 ? 4 : ticks <= 19 ? 5 : ticks <= 39 ? 6 : 7;
            dashHist[b]++;
        }
    }

    static double pct(int a, int b) {
        return b == 0 ? 0 : 100.0 * a / b;
    }

    static boolean atCover(Row r) {
        return r.mode.equals("COVER") || r.mode.equals("PEEK") || r.mode.equals("HOLD") || r.mode.equals("TO_COVER") && r.spot >= 0 && r.spot <= 2.5;
    }

    /** ticks of a slide or a roll, left out */
    static final int[] slides = new int[1];

    static void replay(List<Row> rows, Stats[] st, PrintWriter frames) {
        Stats all = st[0], cover = st[1];
        BotAnimator a = new BotAnimator();
        BotPoser.Parts p = BotPoserTest.parts();
        double cx = rows.get(0).x, cz = rows.get(0).z, tx = cx, tz = cz;
        int steps = 0;
        int dash = 0;
        float dashFrom = 0F;
        boolean dashCover = false;
        float[] prev = null;
        Vector3f[] feet = null, feetV = null;
        int stillFor = 0;
        long first = rows.get(0).tick;
        long prevTick = first - 1;
        for (Row r : rows) {
            if (r.tick != prevTick + 1) {
                // a gap (dead, another fight): start again
                a = new BotAnimator();
                cx = tx = r.x;
                cz = tz = r.z;
                steps = 0;
                prev = null;
                feet = feetV = null;
                stillFor = 0;
                dash = 0;
            }
            prevTick = r.tick;
            // the client: a new place every SENT ticks, gone to over SENT ticks
            if ((r.tick - first) % SENT == 0) {
                tx = r.x;
                tz = r.z;
                steps = SENT;
            }
            double ox = cx, oz = cz;
            if (steps > 0) {
                cx += (tx - cx) / steps;
                cz += (tz - cz) / steps;
                steps--;
            }
            a.move(cx - ox, cz - oz, r.bodyYaw, r.tick);
            BotAnimator.Frame s = a.step(r.pose, r.tick, 0F, 0, false, r.tick - first, r.id, false);
            BotAnimator.Frame f = new BotAnimator.Frame(s.crouch(), s.lean(), s.arms(), s.act(), s.fading(), s.hits(), s.idleSec(), r.id, a.speed, a.heading,
                    -1F, a.walk, a.walkPos);
            for (ModelPart part : BotPoserTest.all(p)) part.resetPose();
            // vanilla's swing (HumanoidModel.setupAnim) before the poser, as the game runs them
            float swing = Mth.cos(a.walkPos * 0.6662F) * 1.4F * a.walk;
            p.rightLeg().xRot = swing;
            p.leftLeg().xRot = -swing;
            BotPoser.apply(p, f, 0F, a.walk, a.walkPos);
            float[] rf = foot(p.rightLeg(), p.rightShin()), lf = foot(p.leftLeg(), p.leftShin());
            float[] low = rf[0] <= lf[0] ? rf : lf;
            float[] now = { p.rightLeg().xRot, p.rightLeg().yRot, p.rightLeg().zRot, p.rightShin().xRot, p.leftLeg().xRot, p.leftLeg().yRot, p.leftLeg().zRot,
                    p.leftShin().xRot };
            float jerk = 0F;
            if (prev != null) for (int k = 0; k < now.length; k++) jerk = Math.max(jerk, Math.abs(now[k] - prev[k]) * Mth.RAD_TO_DEG);
            prev = now;
            // a foot's kick: its velocity (model pixels a tick) changing by over 3 a tick
            Vector3f[] ft = { footAt(p.rightLeg(), p.rightShin()), footAt(p.leftLeg(), p.leftShin()) };
            boolean kick = false;
            if (feet != null) {
                Vector3f[] v = { new Vector3f(ft[0]).sub(feet[0]), new Vector3f(ft[1]).sub(feet[1]) };
                if (feetV != null) for (int k = 0; k < 2; k++) kick |= new Vector3f(v[k]).sub(feetV[k]).length() > 3F;
                feetV = v;
            }
            feet = ft;
            // the way it goes from where it faces, against the clips' heading
            double dx = cx - ox, dz = cz - oz;
            float way = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-dx, dz)) - r.bodyYaw);
            boolean off = Math.abs(Mth.wrapDegrees(way - a.heading * Mth.RAD_TO_DEG)) > 45F;
            float crouch = BotAnimator.smooth(f.crouch());
            float move = (1F - crouch) * BotAnimator.smooth((f.speed() - BotPoser.MOVE_FROM) / (BotPoser.MOVE_FULL - BotPoser.MOVE_FROM));
            double step = Math.hypot(cx - ox, cz - oz);
            boolean moving = step > 0.01;
            boolean at = atCover(r);
            boolean mid = Math.max(rf[0], lf[0]) > 2F || Math.abs(now[0] - now[4]) * Mth.RAD_TO_DEG > 20F;
            stillFor = step > 0.01 ? 0 : stillFor + 1;
            frames.printf(Locale.ROOT, "%d\t%d\t%d\t%s\t%d\t%.3f\t%.3f\t%.0f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.1f\t%.1f\t%.0f\t%.0f\t%.0f\t%.0f\t%.0f\t%.0f\t%.1f%n",
                    r.fight, r.tick, r.id, r.mode, at ? 1 : 0, step, a.speed, a.heading * Mth.RAD_TO_DEG, a.walk, a.walkPos, a.walkPos / BotPoser.CYCLE % 1F,
                    crouch, move, rf[0], lf[0], rf[1], lf[1], now[0] * Mth.RAD_TO_DEG, now[4] * Mth.RAD_TO_DEG, now[3] * Mth.RAD_TO_DEG, now[7] * Mth.RAD_TO_DEG, jerk);
            // a slide or a roll (and its fade) poses the legs itself: not a step's
            boolean own = (r.pose.action() == AiPose.SLIDE || r.pose.action() == AiPose.ROLL) && r.tick - r.pose.actionTick() < r.pose.actionTicks() + 10;
            if (own) {
                slides[0]++;
                continue;
            }
            Stats[] to = !at ? new Stats[] { all } : new Stats[] { all, cover, crouch > 0.5F ? st[2] : st[3] };
            for (Stats t : to) {
                t.ticks++;
                t.footSum += low[0];
                t.slantSum += low[1];
                t.jerkSum += jerk;
                t.moveSum += move;
                if (jerk > 15F) t.jerky++;
                if (kick) t.kicks++;
                if (low[0] > 2F) t.lifted++;
                if (low[0] > 3F) t.lifted3++;
                if (low[1] > 30F) t.slanted++;
                if (!moving) {
                    t.still++;
                    if (low[0] > 2F) t.stillLifted++;
                    if (mid) t.stillMid++;
                    if (stillFor >= 5) {
                        t.stillLong++;
                        if (mid) t.stillLongMid++;
                    }
                } else {
                    t.moving++;
                    if (off) t.offHeading++;
                    if (Math.abs(Mth.sin(a.heading)) > Mth.SQRT_OF_TWO / 2F) t.side++;
                    float[] w = BotPoser.gaitWeights(a.speed, a.heading);
                    for (int k = 0; k < 5; k++) t.clips[k] += w[k];
                }
            }
            // the dashes: ticks on the move in a row
            if (moving) {
                if (dash == 0) {
                    dashFrom = a.walkPos;
                    dashCover = at;
                }
                dash++;
            } else if (dash > 0) {
                double cyc = (a.walkPos - dashFrom) / BotPoser.CYCLE;
                all.dash(dash, cyc);
                if (dashCover) cover.dash(dash, cyc);
                dash = 0;
            }
        }
    }

    /** a leg's foot (the shin's end), model pixels */
    static Vector3f footAt(ModelPart thigh, ModelPart shin) {
        Matrix4f m = new Matrix4f().translate(thigh.x, thigh.y, thigh.z).rotateZYX(thigh.zRot, thigh.yRot, thigh.xRot);
        m.translate(shin.x, shin.y, shin.z).rotateZYX(shin.zRot, shin.yRot, shin.xRot);
        return m.transformPosition(new Vector3f(0F, 6F, 0F));
    }

    /** a leg's foot (the shin's end): its height over the ground, model pixels, and the shin's slant off the upright, degrees */
    static float[] foot(ModelPart thigh, ModelPart shin) {
        Matrix4f m = new Matrix4f().translate(thigh.x, thigh.y, thigh.z).rotateZYX(thigh.zRot, thigh.yRot, thigh.xRot);
        Vector3f knee = m.transformPosition(new Vector3f(shin.x, shin.y, shin.z));
        m.translate(shin.x, shin.y, shin.z).rotateZYX(shin.zRot, shin.yRot, shin.xRot);
        Vector3f foot = m.transformPosition(new Vector3f(0F, 6F, 0F));
        float dy = foot.y - knee.y;
        float slant = (float) Math.toDegrees(Math.acos(Mth.clamp(dy / 6F, -1F, 1F)));
        return new float[] { GROUND - foot.y, slant };
    }
}
