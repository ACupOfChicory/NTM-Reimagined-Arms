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
        Stats all = new Stats(), cover = new Stats();
        for (List<Row> rows : bots.values()) replay(rows, all, cover);
        StringBuilder out = new StringBuilder();
        out.append(String.format(Locale.ROOT, "fighters %d%n", bots.size()));
        out.append(all.report("all ticks"));
        out.append(cover.report("at cover (COVER, PEEK, HOLD, or on the way within 2.5 blocks of its cover)"));
        System.out.println(out);
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(file + ".stats.txt"), StandardCharsets.UTF_8))) {
            w.print(out);
        }
    }

    static final class Stats {
        int ticks, lifted, slanted, lifted3, still, stillLifted, moving, side, sideStrafe, sideWalk;
        double footSum, slantSum;
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
                            + "  dashes %d, ticks %.1f on average, a step's cycle gone through %.2f on average; by ticks 1,2,3,4-5,6-9,10-19,20-39,40+: %s%n",
                    what, ticks, ticks == 0 ? 0 : footSum / ticks, pct(lifted, ticks), pct(lifted3, ticks), ticks == 0 ? 0 : slantSum / ticks, pct(slanted, ticks), still,
                    pct(stillLifted, still), moving, pct(side, moving), clips[0] / Math.max(1, moving), clips[1] / Math.max(1, moving), clips[2] / Math.max(1, moving),
                    clips[3] / Math.max(1, moving), clips[4] / Math.max(1, moving), dashes, dashes == 0 ? 0 : (double) dashTicks / dashes,
                    dashes == 0 ? 0 : dashCycle / dashes, java.util.Arrays.toString(dashHist));
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

    static void replay(List<Row> rows, Stats all, Stats cover) {
        BotAnimator a = new BotAnimator();
        BotPoser.Parts p = BotPoserTest.parts();
        double cx = rows.get(0).x, cz = rows.get(0).z, tx = cx, tz = cz;
        int steps = 0;
        int dash = 0;
        float dashFrom = 0F;
        boolean dashCover = false;
        long first = rows.get(0).tick;
        long prevTick = first - 1;
        for (Row r : rows) {
            if (r.tick != prevTick + 1) {
                // a gap (dead, another fight): start again
                a = new BotAnimator();
                cx = tx = r.x;
                cz = tz = r.z;
                steps = 0;
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
            double step = Math.hypot(cx - ox, cz - oz);
            boolean moving = step > 0.01;
            boolean at = atCover(r);
            for (Stats st : at ? new Stats[] { all, cover } : new Stats[] { all }) {
                st.ticks++;
                st.footSum += low[0];
                st.slantSum += low[1];
                if (low[0] > 2F) st.lifted++;
                if (low[0] > 3F) st.lifted3++;
                if (low[1] > 30F) st.slanted++;
                if (!moving) {
                    st.still++;
                    if (low[0] > 2F) st.stillLifted++;
                } else {
                    st.moving++;
                    if (Math.abs(Mth.sin(a.heading)) > Mth.SQRT_OF_TWO / 2F) st.side++;
                    float[] w = BotPoser.gaitWeights(a.speed, a.heading);
                    for (int k = 0; k < 5; k++) st.clips[k] += w[k];
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
