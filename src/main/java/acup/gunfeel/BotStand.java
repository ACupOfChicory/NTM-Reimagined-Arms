package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.joml.Quaternionf;

import acup.gunfeel.ai.AiPose;
import acup.gunfeel.ai.AiPoseGeometry;
import acup.gunfeel.ai.client.BotAnimation;
import acup.gunfeel.ai.client.BotPoser;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The AI mob animation stand (-PbotStand, runBenchBots), in numbers:
 * <ol>
 * <li>the hitbox takes the pose: an NTM FBI agent posed standing, leaning either way, crouching, crouching and
 * leaning, firing blind and lying with its gun, at three turns; the server's model boxes (what a bullet is tested
 * against) are read back. A leaned head must sit where AiPoseGeometry puts the eye (the server's own formula, the
 * one NTM's shot leaves from), a crouch must lower the head as vanilla's does, a blind hold must put the hands over
 * it, lying must keep it low;</li>
 * <li>no jumps on the game's own frames: one agent through a script of changes, its parts' largest turn from one
 * frame to the next;</li>
 * <li>the cost: 100 agents, each always changing pose, the frame time and the server's tick with the player on and
 * off (on, off, off, on).</li>
 * </ol>
 * In the first part the server sets each pose as the mob AI does (AiPose's attachment, synced to the client, and the
 * game's crouching pose with it); the jumps and the cost give theirs in the stand (BotAnimation.standPose), changing
 * them more often than a sync could.
 */
final class BotStand {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.botStand");
    private static final Identifier FBI = Identifier.fromNamespaceAndPath("hbm", "entity_fbi");
    private static final BlockPos O = new BlockPos(0, 100, 0);
    private static final float[] YAWS = { 0F, 90F, -135F };
    private static final List<String> ROWS = new ArrayList<>();
    private static final long[] TIMES = new long[200_000];

    private record Case(String name, byte stance, int lean, byte arms, boolean lying) {
    }

    private static final Case[] CASES = { new Case("stand", AiPose.STAND, 0, AiPose.HOLD, false),
            new Case("lean_right", AiPose.STAND, 100, AiPose.AIM, false),
            new Case("lean_left", AiPose.STAND, -100, AiPose.AIM, false),
            new Case("crouch", AiPose.CROUCH, 0, AiPose.HOLD, false),
            new Case("crouch_lean_right", AiPose.CROUCH, 100, AiPose.AIM, false),
            new Case("blind", AiPose.CROUCH, 0, AiPose.BLIND, false),
            new Case("prone_gun", AiPose.PRONE, 0, AiPose.HOLD, true) };

    private static int step, caseIndex, yawIndex, phase;
    private static long until, measuring, last;
    private static int samples, frameNo;
    private static volatile Mob one;
    private static final List<Mob> crowd = new ArrayList<>();
    /** the case's pose, from this game tick */
    private static volatile AiPose posed = AiPose.IDLE;
    private static final Map<String, double[]> BASE = new HashMap<>();
    // the jump watch: the parts' turns on the last frame seen, and the largest move between two frames
    private static final float[] seen = new float[18], prev = new float[18];
    private static int seenFrame = -1, prevFrame = -1;
    private static float maxMove, maxMoveAt, maxSpeed, seenTime, t0Age;
    private static int moveFrames;
    private static final double[] MEANS = new double[2];

    private BotStand() {
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static long now() {
        return mc().level.getGameTime();
    }

    static void start() {
        Minecraft mc = mc();
        mc.options.framerateLimit().set(Integer.getInteger("ntmgunfeel.benchFps", 260));
        mc.options.enableVsync().set(false);
        mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
        mc.options.pauseOnLostFocus = false;
        mc.options.fov().set(70);
        ROWS.add("часть\tслучай\tповорот\tголова_вбок_см\tголова_вверх_см\tголова_вперёд_см\tголова_ошибка_см\tверх_бл"
                + "\tвправо_бл\tвлево_бл\tвперёд_бл\tкадров\tFPS\tсредний_мс\tp95_мс\tp99_мс\tтик_сервера_мс\tверх_часть");
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server);
            level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
            level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER, false, server);
            for (int x = -12; x <= 12; x++) for (int z = -6; z <= 30; z++) {
                level.setBlock(O.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 0; y <= 6; y++) level.setBlock(O.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
            var p = server.getPlayerList().getPlayers().getFirst();
            MobStageCleanup.clear(level, p);
            p.setGameMode(GameType.CREATIVE);
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
            p.teleportTo(level, 0.5, O.getY() + 0.6, -0.5, Set.of(), 0F, 12F, false);
            one = spawn(level, 0.5, 4.5, 180F);
        }).join();
        BotAnimation.standPose = null;
        BotAnimation.standStill = true;
        step = 0;
        caseIndex = yawIndex = 0;
        until = now() + 40;
    }

    private static Mob spawn(ServerLevel level, double x, double z, float yaw) {
        var created = BuiltInRegistries.ENTITY_TYPE.getValue(FBI).create(level, EntitySpawnReason.COMMAND);
        if (!(created instanceof Mob mob)) throw new IllegalStateException("no FBI agent");
        mob.snapTo(x, O.getY(), z, yaw, 0F);
        mob.setYBodyRot(yaw);
        mob.setYHeadRot(yaw);
        mob.setNoAi(true);
        mob.setInvulnerable(true);
        mob.setPersistenceRequired();
        level.addFreshEntity(mob);
        return mob;
    }

    static void frame() {
        frameNo++;
        try {
            switch (step) {
                case 0 -> poses();
                case 1 -> jumps();
                case 2 -> cost();
                default -> {
                }
            }
        } catch (Throwable t) {
            ClientSmoke.check(false, "стенд ботов: " + t);
            write();
            ClientSmoke.finish();
        }
    }

    // ---------------------------------------------------------------- 1. the hitbox takes the pose

    private static void poses() {
        if (now() < until) return;
        Case c = CASES[caseIndex];
        float yaw = YAWS[yawIndex];
        if (until > 0 && phase == 1) {
            measure(c, yaw);
            phase = 0;
            if (++caseIndex == CASES.length) {
                caseIndex = 0;
                if (++yawIndex == YAWS.length) {
                    write();
                    startJumps();
                    return;
                }
            }
            c = CASES[caseIndex];
            yaw = YAWS[yawIndex];
        }
        // set the case: the mob turned, lying or not, the pose from now (a lean takes 4 ticks, a crouch 4, the arms 4)
        float y = yaw;
        boolean lying = c.lying();
        Case cc = c;
        var server = mc().getSingleplayerServer();
        server.submit(() -> {
            Mob m = (Mob) server.overworld().getEntity(one.getId());
            m.setYRot(y);
            m.setYBodyRot(y);
            m.setYHeadRot(y);
            // as AiSoldier.body: the pose first, then the game's, so the crouching size finds it
            int at = (int) server.overworld().getGameTime();
            m.setData(AiPose.TYPE, new AiPose(cc.stance(), (byte) cc.lean(), (byte) 0, at, cc.arms(), AiPose.NONE, 0, (short) 0));
            m.setPose(lying ? Pose.SWIMMING : cc.stance() == AiPose.CROUCH ? Pose.CROUCHING : Pose.STANDING);
        }).join();
        long t = now();
        until = t + 30;
        phase = 1;
    }

    private static void measure(Case c, float yaw) {
        var server = mc().getSingleplayerServer();
        double[][] got = new double[1][];
        server.submit(() -> {
            Mob m = (Mob) server.overworld().getEntity(one.getId());
            got[0] = numbers(m, ModelHits.worldBoxes(m), yaw);
        }).join();
        double[] n = got[0];
        // the pose came to the client by the attachment's sync, and a crouch took a crouching player's size
        var seenBy = mc().level.getEntity(one.getId());
        AiPose synced = seenBy instanceof Mob sm ? AiPose.of(sm) : null;
        ClientSmoke.check(synced != null && synced.stance() == c.stance() && synced.lean() == c.lean()
                && synced.arms() == c.arms(), c.name() + " поворот " + yaw + ": клиент получил позу " + synced);
        if (c.stance() == AiPose.CROUCH && seenBy != null)
            ClientSmoke.check(Math.abs(seenBy.getBbHeight() - AiPose.CROUCH_SIZE.height()) < 1e-4,
                    c.name() + ": рост присевшего " + seenBy.getBbHeight());
        String key = yaw + "/" + c.stance();
        if (n == null) {
            ClientSmoke.check(false, c.name() + " поворот " + yaw + ": у сервера нет позы модели");
            return;
        }
        double err = Double.NaN;
        if (c.lean() == 0 && !c.lying() && c.arms() == AiPose.HOLD) BASE.put(key, n);
        double[] base = BASE.get(key);
        if (c.lean() != 0 && base != null) {
            // where the server's formula puts the upright head's centre with this lean
            Vec3 want = AiPoseGeometry.leanPoint(base[0], base[1], base[2], c.stance(), c.lean() / 100F, yaw);
            double r = -want.x * Math.cos(Math.toRadians(yaw)) - want.z * Math.sin(Math.toRadians(yaw));
            double f = -want.x * Math.sin(Math.toRadians(yaw)) + want.z * Math.cos(Math.toRadians(yaw));
            err = Math.sqrt(sq(n[0] - r) + sq(n[1] - want.y) + sq(n[2] - f)) * 100;
            ClientSmoke.check(err < 3, String.format(Locale.ROOT, "%s поворот %.0f: голова там, где глаз сервера: ошибка %.1f см (сдвиг вбок %.0f см)",
                    c.name(), yaw, err, (n[0] - base[0]) * 100));
        }
        double[] stand = BASE.get(yaw + "/" + AiPose.STAND);
        if (c.name().equals("crouch") && stand != null) {
            double drop = stand[3] - n[3];
            ClientSmoke.check(drop > 0.2 && drop < 0.32, String.format(Locale.ROOT, "присед поворот %.0f: верх ниже на %.3f бл (ванильный присед: голова на 4,2 px)", yaw, drop));
        }
        if (c.name().equals("blind") && base != null)
            // a Minecraft arm reaches 10 px past its shoulder: held straight up from a crouch the hands clear the head
            // by about a pixel (the gun, drawn above them, is no part of the hitbox)
            ClientSmoke.check(n[3] - base[3] > 0.04 && n[1] <= base[1] + 0.01, String.format(Locale.ROOT,
                    "вслепую поворот %.0f: руки выше головы на %.2f бл, голова не выше приседа (%.1f см против %.1f)", yaw, n[3] - base[3], n[1] * 100, base[1] * 100));
        if (c.lying())
            // the arms out along the ground; the height is vanilla's crawl (and the prone render), only written down
            ClientSmoke.check(n[6] > 1.0, String.format(Locale.ROOT, "лёжа поворот %.0f: руки вперёд на %.2f бл; верх %.2f бл (%s)", yaw, n[6], n[3], topPart));
        ROWS.add(String.format(Locale.ROOT, "поза\t%s\t%.0f\t%.1f\t%.1f\t%.1f\t%s\t%.3f\t%.3f\t%.3f\t%.3f\t\t\t\t\t\t\t%s", c.name(), yaw, n[0] * 100,
                n[1] * 100, n[2] * 100, Double.isNaN(err) ? "" : String.format(Locale.ROOT, "%.2f", err), n[3], n[4], n[5], n[6], topPart));
    }

    private static String topPart = "";

    private static double sq(double x) {
        return x * x;
    }

    /**
     * The head box's centre in the mob's frame (right, up, forward; blocks from its feet), then the highest point,
     * the farthest right, the farthest left (as a distance) and the farthest forward of all boxes; null with no pose.
     */
    private static double[] numbers(Mob m, List<ModelBox> boxes, float yaw) {
        if (boxes.isEmpty()) return null;
        double a = Math.toRadians(yaw), cos = Math.cos(a), sin = Math.sin(a);
        Vec3 o = m.position();
        double[] head = null;
        double top = -9, right = -9, left = -9, fwd = -9;
        for (ModelBox b : boxes) {
            for (int i = 0; i < 8; i++) {
                double u = (i & 1), v = (i >> 1) & 1, w = (i >> 2) & 1;
                double x = b.x() + u * b.ux() + v * b.vx() + w * b.wx() - o.x, y = b.y() + u * b.uy() + v * b.vy() + w * b.wy() - o.y,
                        z = b.z() + u * b.uz() + v * b.vz() + w * b.wz() - o.z;
                double r = -x * cos - z * sin, f = -x * sin + z * cos;
                if (y > top) topPart = b.part();
                top = Math.max(top, y);
                right = Math.max(right, r);
                left = Math.max(left, -r);
                fwd = Math.max(fwd, f);
            }
            String part = b.part();
            if (head == null && (part.equals("head") || part.endsWith("/head") || part.endsWith(".head"))) {
                double x = b.x() + (b.ux() + b.vx() + b.wx()) / 2 - o.x, y = b.y() + (b.uy() + b.vy() + b.wy()) / 2 - o.y,
                        z = b.z() + (b.uz() + b.vz() + b.wz()) / 2 - o.z;
                head = new double[] { -x * cos - z * sin, y, -x * sin + z * cos };
            }
        }
        if (head == null) {
            StringBuilder names = new StringBuilder();
            for (ModelBox b : boxes) names.append(b.part()).append(' ');
            throw new IllegalStateException("нет коробки головы: " + names);
        }
        return new double[] { head[0], head[1], head[2], top, right, left, fwd };
    }

    // ---------------------------------------------------------------- 2. no jumps on the game's frames

    private static long t0;

    private static void startJumps() {
        step = 1;
        var server = mc().getSingleplayerServer();
        server.submit(() -> {
            Mob m = (Mob) server.overworld().getEntity(one.getId());
            m.setYRot(180F);
            m.setYBodyRot(180F);
            m.setYHeadRot(180F);
            m.setData(AiPose.TYPE, AiPose.IDLE);
            m.setPose(Pose.STANDING);
        }).join();
        t0 = now() + 10;
        posed = AiPose.IDLE;
        BotAnimation.standPose = m -> m == one || one != null && m.getId() == one.getId() ? posed : AiPose.IDLE;
        int id = one.getId();
        BotAnimation.standStill = false;
        // setupAnim runs more than once a frame (the render, the hit capture, the armour), not always on the newest
        // state: each pose is kept by its own time, and the move counted against the time between two poses
        BotAnimation.standWatch = (f, parts) -> {
            if (f.entity() != id || now() < t0) return;
            float time = f.idleSec() * 20F;
            if (seenFrame >= 0 && time <= seenTime + 1e-4F) return;
            store(parts);
            if (seenFrame >= 0) {
                float m = move(prev, seen), speed = m / (time - seenTime);
                moveFrames++;
                maxMove = Math.max(maxMove, m);
                if (speed > maxSpeed) {
                    maxSpeed = speed;
                    maxMoveAt = time - t0Age;
                }
            } else t0Age = time;
            System.arraycopy(seen, 0, prev, 0, 18);
            seenFrame = 1;
            seenTime = time;
        };
        measuring = 0;
        until = 0;
    }

    private static void store(BotPoser.Parts p) {
        ModelPart[] all = { p.head(), p.body(), p.rightArm(), p.leftArm(), p.rightLeg(), p.leftLeg() };
        for (int i = 0; i < 6; i++) {
            seen[i * 3] = all[i].xRot;
            seen[i * 3 + 1] = all[i].yRot;
            seen[i * 3 + 2] = all[i].zRot;
        }
    }

    private static float move(float[] a, float[] b) {
        float m = 0F;
        Quaternionf qa = new Quaternionf(), qb = new Quaternionf();
        for (int i = 0; i < 6; i++) {
            qa.rotationZYX(a[i * 3 + 2], a[i * 3 + 1], a[i * 3]);
            qb.rotationZYX(b[i * 3 + 2], b[i * 3 + 1], b[i * 3]);
            m = Math.max(m, 2F * (float) Math.acos(Math.min(1F, Math.abs(qa.dot(qb)))));
        }
        return m;
    }

    private static void jumps() {
        long t = now() - t0;
        if (t < 0) return;
        if (measuring == 0) measuring = System.nanoTime();
        // the same script as BotPoserTest, on the game's own frames: changes halfway through others
        int base = (int) t0;
        AiPose p;
        if (t < 10) p = pose(AiPose.STAND, 0, 0, 0, AiPose.HOLD, AiPose.NONE, 0, 0);
        else if (t < 11) p = pose(AiPose.CROUCH, 0, 0, 0, AiPose.AIM, AiPose.NONE, 0, 0);
        else if (t < 20) p = pose(AiPose.STAND, 100, 0, base + 11, AiPose.BLIND, AiPose.NONE, 0, 0);
        else if (t < 21) p = pose(AiPose.STAND, -100, 100, base + 20, AiPose.AIM, AiPose.RELOAD, base + 20, 30);
        else if (t < 40) p = pose(AiPose.CROUCH, -100, 100, base + 20, AiPose.HOLD, AiPose.RELOAD, base + 20, 30);
        else if (t < 50) p = pose(AiPose.CROUCH, 0, -100, base + 40, AiPose.HOLD, AiPose.SIGNAL_POINT, base + 40, 20);
        else if (t < 70) p = pose(AiPose.STAND, 0, 0, base + 40, AiPose.AIM, AiPose.SHOT, base + 50, 0);
        else p = pose(AiPose.STAND, 0, 0, base + 40, AiPose.NO_GUN, AiPose.SIGNAL_HALT, base + 70, 20);
        posed = p;
        if (t < 95) return;
        BotAnimation.standWatch = null;
        double seconds = (System.nanoTime() - measuring) / 1e9;
        // the fastest change of the script, the arms from over the head to the hip in 4 ticks eased in, is about
        // 1 rad a tick; a jump is a turn in no time
        ClientSmoke.check(moveFrames > 60 && maxSpeed < 2F, String.format(Locale.ROOT,
                "переходы на кадрах игры: самая быстрая часть %.2f рад/тик (тик %.1f сценария), самый большой шаг %.3f рад, поз %d, %.0f в секунду",
                maxSpeed, maxMoveAt, maxMove, moveFrames, moveFrames / seconds));
        ROWS.add(String.format(Locale.ROOT, "переходы\tсценарий\t\t\t\t\t%.4f рад/тик\t\t\t\t\t%d\t%.1f", maxSpeed, moveFrames, moveFrames / seconds));
        write();
        startCost();
    }

    private static AiPose pose(byte stance, int lean, int prevLean, int leanTick, byte arms, byte action, int actionTick,
            int actionTicks) {
        return new AiPose(stance, (byte) lean, (byte) prevLean, leanTick, arms, action, actionTick, (short) actionTicks);
    }

    // ---------------------------------------------------------------- 3. the cost of 100

    private static final boolean[] PLAN = { true, false, false, true };
    private static int planIndex;

    private static void startCost() {
        step = 2;
        var server = mc().getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            for (int i = 0; i < 100; i++) crowd.add(spawn(level, -6.75 + (i % 10) * 1.5, 4.5 + (i / 10) * 1.5, 180F));
            var p = server.getPlayerList().getPlayers().getFirst();
            p.teleportTo(level, 0.5, O.getY() + 2.5, -3, Set.of(), 0F, 20F, false);
        }).join();
        // every agent always on the move: a lean back and forth, a crouch, a reload, each in its own time
        Function<Mob, AiPose> busy = m -> {
            long t = m.level().getGameTime();
            int k = m.getId() * 7, c = (int) ((t + k) / 40);
            int leanTick = (int) (t - (t + k) % 40);
            byte lean = (byte) ((c & 1) == 0 ? 100 : -100), prevLean = (byte) -lean;
            byte stance = (c & 2) == 0 ? AiPose.STAND : AiPose.CROUCH;
            byte arms = (c % 3) == 0 ? AiPose.AIM : AiPose.HOLD;
            int actTick = (int) (t - (t + k) % 60);
            return new AiPose(stance, lean, prevLean, leanTick, arms, AiPose.RELOAD, actTick, (short) 30);
        };
        BotAnimation.standPose = busy;
        planIndex = -1;
        nextCost();
    }

    private static void nextCost() {
        if (++planIndex == PLAN.length) {
            double on = MEANS[1] / 2, off = MEANS[0] / 2;
            ClientSmoke.check(true, String.format(Locale.ROOT, "100 ботов: кадр %.3f мс с проигрывателем, %.3f мс без (%+.3f мс)", on, off, on - off));
            write();
            ClientSmoke.check(true, "стенд ботов завершён");
            ClientSmoke.finish();
            return;
        }
        BotAnimation.standOff = !PLAN[planIndex];
        until = System.nanoTime() + 3_000_000_000L;
        measuring = 0;
        samples = 0;
    }

    private static void cost() {
        long t = System.nanoTime();
        if (measuring == 0) {
            if (t < until) return;
            int seen = 0;
            for (var e : mc().level.entitiesForRendering()) if (e instanceof Mob) seen++;
            if (seen < 100) {
                until = t + 1_000_000_000L;
                return;
            }
            measuring = last = t;
            return;
        }
        if (samples < TIMES.length) TIMES[samples++] = t - last;
        last = t;
        if ((t - measuring) / 1e9 < 6) return;
        double seconds = (t - measuring) / 1e9;
        long[] s = Arrays.copyOf(TIMES, samples);
        Arrays.sort(s);
        double mean = seconds * 1000 / samples;
        boolean on = PLAN[planIndex];
        MEANS[on ? 1 : 0] += mean;
        double mspt = mc().getSingleplayerServer().getAverageTickTimeNanos() / 1e6;
        ROWS.add(String.format(Locale.ROOT, "100 ботов\t%s\t\t\t\t\t\t\t\t\t\t%d\t%.1f\t%.3f\t%.3f\t%.3f\t%.3f", on ? "вкл" : "выкл", samples,
                samples / seconds, mean, s[(int) ((samples - 1) * .95)] / 1e6, s[(int) ((samples - 1) * .99)] / 1e6, mspt));
        write();
        nextCost();
    }

    private static void write() {
        try {
            Path f = Path.of(System.getProperty("ntmgunfeel.botStandFile"));
            Files.createDirectories(f.getParent());
            Files.write(f, ROWS);
        } catch (Exception e) {
            ClientSmoke.check(false, "запись: " + e);
        }
    }
}
