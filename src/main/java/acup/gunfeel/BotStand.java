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
import acup.gunfeel.ai.client.BotClips;
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
import net.minecraft.world.phys.AABB;
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
 * <li>the clips: one agent moved by the stand ahead at a walk, a jog and a sprint, back, to each side, crawling,
 * sliding and rolling, then still as it reloads, hit, hit by a blast, killed: the clip each plays (its weight), no jumps on the way; and an agent the
 * game moves at the speeds the mob AI asks for (AiSoldier.WALK, RUN), the speeds the gait's clips are set to;</li>
 * <li>the cost: 100 agents, each always changing pose, the frame time and the server's tick with the player on and
 * off (on, off, off, on).</li>
 * </ol>
 * With the first part, in each pose: a grid of rays over the model the client draws, against what the server takes
 * for a hit (BotStandHits: hits on the model, past it, the zone; the plain box's for the model hitboxes off), and how
 * long the server is left with no pose it can use while the pose changes. After it, before the jumps: a carbine's
 * shots to kill the agent by the part hit, in each of its loadouts, the part's multiplier before the armour and after;
 * in each loadout first the grid over the agent as drawn, its armour too (a shot on a helmet is in the head), and two
 * shots through the helmet alone, half a pixel past the head, as the server and the shooter's client take them.
 * <ol>
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
            // down on a knee aiming, and the gun close beside a doorway, standing and crouched
            new Case("kneel_aim", AiPose.CROUCH, 0, AiPose.AIM, false),
            new Case("stack", AiPose.STAND, 0, AiPose.STACK, false),
            new Case("crouch_stack", AiPose.CROUCH, 0, AiPose.STACK, false),
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
    private static final float[] seen = new float[30], prev = new float[30];
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
                case 3 -> damage();
                case 4 -> clips();
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
        if (now() < until) {
            if (phase == 1) watchPose();
            return;
        }
        Case c = CASES[caseIndex];
        float yaw = YAWS[yawIndex];
        if (until > 0 && phase == 1) {
            measure(c, yaw);
            phase = 0;
            if (++caseIndex == CASES.length) {
                caseIndex = 0;
                if (++yawIndex == YAWS.length) {
                    write();
                    startDamage();
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
            m.setData(AiPose.Sync.TYPE, new AiPose(cc.stance(), (byte) cc.lean(), (byte) 0, at, cc.arms(), AiPose.NONE, 0, (short) 0, AiPose.FIGHT));
            m.setPose(lying ? Pose.SWIMMING : cc.stance() == AiPose.CROUCH ? Pose.CROUCHING : Pose.STANDING);
        }).join();
        long t = now();
        until = t + 30;
        phase = 1;
        watchFrames = watchGaps = 0;
        watchAge = 0;
        watchFrom = t;
    }

    // H3: from the change on, on each frame, the pose the server keeps for the agent: none it can use (a bullet then
    // takes the plain box), and how old the one it has is
    private static int watchFrames, watchGaps;
    private static long watchAge, watchFrom, watchLastGap;

    private static void watchPose() {
        var level = mc().getSingleplayerServer().overworld();
        watchFrames++;
        if (!ModelHits.hasPose(one)) {
            watchGaps++;
            watchLastGap = now() - watchFrom;
        }
        long caught = ModelHits.caught(level, one);
        if (caught != Long.MIN_VALUE) watchAge = Math.max(watchAge, level.getGameTime() - caught);
    }

    private static void measure(Case c, float yaw) {
        var server = mc().getSingleplayerServer();
        double[][] got = new double[1][];
        server.submit(() -> {
            Mob m = (Mob) server.overworld().getEntity(one.getId());
            got[0] = numbers(m, ModelHits.worldBoxes(m), yaw);
        }).join();
        double[] n = got[0];
        grid(c, yaw);
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
        if (c.lean() == 0 && !c.lying() && c.arms() != AiPose.HOLD && c.arms() != AiPose.BLIND && base != null) {
            // other arms over the same stance (down on a knee aiming, the gun close beside a doorway): the head where
            // the stance has it
            err = Math.sqrt(sq(n[0] - base[0]) + sq(n[1] - base[1]) + sq(n[2] - base[2])) * 100;
            ClientSmoke.check(err < 3, String.format(Locale.ROOT, "%s поворот %.0f: голова там же, где в стойке с оружием у груди: ошибка %.1f см",
                    c.name(), yaw, err));
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

    /** the grid of shots over the drawn model, and the server's pose while it changed */
    private static void grid(Case c, float yaw) {
        grid(c.name(), yaw, true);
    }

    /** the grid over the agent as the client draws it (its armour too); {@code watched}: with the H3 line */
    private static void grid(String name, float yaw, boolean watched) {
        var seenBy = mc().level.getEntity(one.getId());
        var drawn = seenBy == null ? List.<ModelBox>of() : ModelHits.worldBoxes(seenBy);
        if (drawn.isEmpty()) {
            ClientSmoke.check(false, name + " поворот " + yaw + ": клиент не нарисовал модель");
            return;
        }
        var rays = BotStandHits.grid(drawn, one.getBoundingBox(), yaw);
        var server = mc().getSingleplayerServer();
        BotStandHits.Grid[] got = new BotStandHits.Grid[1];
        server.submit(() -> got[0] = BotStandHits.server((Mob) server.overworld().getEntity(one.getId()), rays)).join();
        var g = got[0];
        // T-51 on the agent: its suit's arms, legs and chest are not the agent's split limbs (zones apart: written down);
        // its helmet is checked, every ray on it the head
        boolean suit = name.endsWith("T51");
        if (suit) ClientSmoke.check(g.helmets() > 0 && g.helmetsWrong() == 0, String.format(Locale.ROOT, "сетка %s поворот %.0f: шлем — голова у сервера, %d лучей из %d",
                name, yaw, g.helmets() - g.helmetsWrong(), g.helmets()));
        ClientSmoke.check(g.inHit() >= 0.99 && g.outHit() <= 0.01 && (suit || g.zoneSame() >= 0.99), String.format(Locale.ROOT,
                "сетка %s поворот %.0f: по модели попаданий %.2f%% из %d, мимо модели %.2f%% из %d, та же зона %.2f%% %s;"
                        + " без точных хитбоксов (коробка): %.1f%% и %.1f%%",
                name, yaw, g.inHit() * 100, g.in(), g.outHit() * 100, g.out(), g.zoneSame() * 100, g.wrong(), g.boxIn() * 100, g.boxOut() * 100));
        // H3, written down: after a change of stance the server has no pose it can use until the client's next one
        // comes (a bullet then takes the plain box), and how old the one it has gets
        if (watched) ClientSmoke.check(true, String.format(Locale.ROOT, "%s поворот %.0f (сведения): поза сервера после смены — без годной позы %d кадров из %d"
                + " (последний на тике %d), самая старая %d тиков", name, yaw, watchGaps, watchFrames, watchLastGap, watchAge));
        ROWS.add(String.format(Locale.ROOT, "сетка\t%s\t%.0f\tпо модели %.2f%%\tмимо %.2f%%\tзона %.2f%%\tкоробка %.1f%%/%.1f%%\tбез позы %d/%d\tвозраст %d\t%s",
                name, yaw, g.inHit() * 100, g.outHit() * 100, g.zoneSame() * 100, g.boxIn() * 100, g.boxOut() * 100, watchGaps, watchFrames,
                watchAge, g.wrong()));
    }

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

    // ---------------------------------------------------------------- the carbine's shots to kill

    private static int loadout;
    private static double agentHealth;

    private static void startDamage() {
        step = 3;
        var server = mc().getSingleplayerServer();
        server.submit(() -> {
            Mob m = (Mob) server.overworld().getEntity(one.getId());
            m.setYRot(180F);
            m.setYBodyRot(180F);
            m.setYHeadRot(180F);
            m.setData(AiPose.Sync.TYPE, AiPose.IDLE);
            m.setPose(Pose.STANDING);
            agentHealth = BotStandHits.maxHealth(m);
            m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(1000);
            m.setInvulnerable(false);
            BotStandHits.wear(m, BotStandHits.LOADOUTS[0]);
        }).join();
        loadout = 0;
        until = now() + 30;
        ROWS.add("урон\tнабор\tпорядок\tчасть\tзона\tурон_выстрела\tвыстрелов_до_смерти\tDT/DR\tброня\tкарабин");
    }

    private static void damage() {
        if (now() < until) return;
        var server = mc().getSingleplayerServer();
        var l = BotStandHits.LOADOUTS[loadout];
        armour(l);
        // the agent in this loadout as the player sees it: the armour drawn (compared by its pixels after the stand)
        int shotIndex = loadout;
        net.minecraft.client.Screenshot.takeScreenshot(mc().gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                Path f = Path.of(System.getProperty("ntmgunfeel.botStandFile"));
                image.writeToFile(f.resolveSibling(f.getFileName().toString().replace(".tsv", "") + "-armour-" + shotIndex + ".png"));
            } catch (java.io.IOException e) {
                ClientSmoke.check(false, "снимок брони: " + e);
            }
        });
        server.submit(() -> {
            ServerLevel level = server.overworld();
            Mob m = (Mob) level.getEntity(one.getId());
            var round = BotStandHits.carbine();
            var boxes = ModelHits.worldBoxes(m);
            String dtdr = BotStandHits.dtdr(m, round);
            String carbine = String.format(Locale.ROOT, "%.1f (DT-%.1f, DR-%.0f%%)", round.damage(), round.config().armorThresholdNegation,
                    round.config().armorPiercingPercent * 100);
            String armor = m.getArmorValue() + "/" + String.format(Locale.ROOT, "%.1f",
                    m.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS));
            for (boolean before : new boolean[] { true, false }) {
                HeadDamage.standBeforeArmor = before;
                float body = 0F;
                try {
                    for (String[] part : BotStandHits.PARTS) {
                        Vec3 at = BotStandHits.centre(boxes, part[1]);
                        if (at == null) {
                            ClientSmoke.check(false, "урон: у сервера нет части " + part[1]);
                            continue;
                        }
                        String zone = ModelHits.head(m, at).head() ? "голова" : ModelHits.limb(m, at) ? "конечность" : "тело";
                        float hit = (BotStandHits.shot(level, m, round, at) + BotStandHits.shot(level, m, round, at)) / 2;
                        if (part[1].equals("root/body")) body = hit;
                        int kill = hit <= 0 ? -1 : (int) Math.ceil(agentHealth / hit - 1e-4);
                        String order = before ? "до брони" : "после брони";
                        ROWS.add(String.format(Locale.ROOT, "урон\t%s\t%s\t%s\t%s\t%.2f\t%s\t%s\t%s\t%s", l.name(), order, part[0], zone, hit,
                                kill < 0 ? "∞" : String.valueOf(kill), dtdr, armor, carbine));
                        ClientSmoke.check(true, String.format(Locale.ROOT, "урон %s, %s, %s (%s): %.2f за выстрел, до смерти %s (здоровье %.0f)", l.name(),
                                order, part[0], zone, hit, kill < 0 ? "∞" : String.valueOf(kill), agentHealth));
                        if (!before && !part[1].equals("root/body")) {
                            double want = body * (zone.equals("голова") ? 1.75 : zone.equals("конечность") ? 0.5 : 1);
                            ClientSmoke.check(Math.abs(hit - want) < 0.02, String.format(Locale.ROOT,
                                    "урон %s после брони: %s = тело × множитель: %.2f против %.2f", l.name(), part[0], hit, want));
                        }
                    }
                } finally {
                    HeadDamage.standBeforeArmor = false;
                }
            }
            ClientSmoke.check(true, "урон " + l.name() + ": DT/DR " + dtdr + ", ванильная броня/прочность " + armor + ", карабин " + carbine);
            m.setHealth(1000);
            if (loadout + 1 < BotStandHits.LOADOUTS.length) BotStandHits.wear(m, BotStandHits.LOADOUTS[loadout + 1]);
            else {
                BotStandHits.wear(m, BotStandHits.LOADOUTS[0]);
                m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(agentHealth);
                m.setHealth((float) agentHealth);
                m.setInvulnerable(true);
            }
        }).join();
        write();
        if (++loadout < BotStandHits.LOADOUTS.length) {
            until = now() + 30;
            return;
        }
        startJumps();
    }

    /**
     * The agent in this loadout: the grid over it as the client draws it, the armour too (a shot on a piece of armour
     * is in the part under it); the sizes of the parts and of what is drawn over them; two shots through the helmet
     * alone, half a pixel past the head, as the server and the shooter's client take them.
     */
    private static void armour(BotStandHits.Loadout l) {
        grid("броня " + l.name(), 180F, false);
        var server = mc().getSingleplayerServer();
        List<BotStandHits.Edge> edges = new ArrayList<>();
        String[][] zones = new String[2][];
        server.submit(() -> {
            Mob m = (Mob) server.overworld().getEntity(one.getId());
            var boxes = ModelHits.worldBoxes(m);
            var sizes = BotStandHits.sizes(boxes, 180F);
            ClientSmoke.check(true, "броня " + l.name() + " (сведения), px поперёк×вверх×вдоль: " + sizes);
            ROWS.add("размеры\t" + l.name() + "\t" + sizes);
            var fit = BotStandHits.fit(boxes);
            if (!fit.isEmpty()) {
                ClientSmoke.check(true, "броня " + l.name() + " (сведения), броня против части: " + fit);
                ROWS.add("посадка\t" + l.name() + "\t" + fit);
            }
            edges.addAll(BotStandHits.helmetEdges(boxes, 180F));
            for (int i = 0; i < edges.size(); i++) zones[i] = edgeZone(m, edges.get(i));
        }).join();
        var seenBy = mc().level.getEntity(one.getId());
        for (int i = 0; i < edges.size(); i++) {
            var e = edges.get(i);
            String[] client = seenBy == null ? new String[] { "нет моба", "" } : edgeZone(seenBy, e), at = zones[i];
            // a helmet: the head; nothing worn: past the head; a glass block: written down
            // (a suit's own helmet, T-51's, stands lower than a layer's: half a pixel over the head may pass it by)
            boolean helmet = l.head() != null && !l.head().getPath().equals("glass") && !l.head().getPath().equals("t51_helmet");
            boolean ok = client[0].equals(at[0]) && (helmet ? at[0].equals("head") : l.head() == null ? at[0].equals("мимо") : true);
            ClientSmoke.check(ok, String.format(Locale.ROOT, "шлем %s, выстрел %s: сервер %s%s, клиент %s%s", l.name(), e.name(), at[0], at[1],
                    client[0], client[1]));
            ROWS.add(String.format(Locale.ROOT, "шлем\t%s\t%s\tсервер %s%s\tклиент %s%s", l.name(), e.name(), at[0], at[1], client[0], client[1]));
        }
    }

    /** where a shot along {@code e} hits the mob, and the part it takes: head, limb, body or past ("мимо") */
    private static String[] edgeZone(net.minecraft.world.entity.Entity m, BotStandHits.Edge e) {
        AABB model = m.getBoundingBox(), box = new AABB(model.minX, model.minY, model.minZ, model.maxX, model.maxY, model.maxZ);
        var hit = ModelHits.clip(m, e.from(), e.to(), box);
        if (hit.isEmpty()) return new String[] { "мимо", "" };
        Vec3 p = hit.get();
        return new String[] { ModelHits.head(m, p).head() ? "head" : ModelHits.limb(m, p) ? "limb" : "body",
                String.format(Locale.ROOT, " (%.3f %.3f %.3f)", p.x, p.y, p.z) };
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
            m.setData(AiPose.Sync.TYPE, AiPose.IDLE);
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
                    maxWhat = what(f);
                }
            } else t0Age = time;
            System.arraycopy(seen, 0, prev, 0, seen.length);
            seenFrame = 1;
            seenTime = time;
        };
        measuring = 0;
        until = 0;
    }

    private static void store(BotPoser.Parts p) {
        ModelPart[] all = { p.head(), p.body(), p.rightArm(), p.leftArm(), p.rightLeg(), p.leftLeg(), p.rightForearm(),
                p.leftForearm(), p.rightShin(), p.leftShin() };
        for (int i = 0; i < all.length; i++) {
            if (all[i] == null) throw new IllegalStateException("the bot's model has no forearm or shin");
            seen[i * 3] = all[i].xRot;
            seen[i * 3 + 1] = all[i].yRot;
            seen[i * 3 + 2] = all[i].zRot;
        }
    }

    private static final String[] PART_NAMES = { "голова", "тело", "правая рука", "левая рука", "правая нога", "левая нога",
            "правое предплечье", "левое предплечье", "правая голень", "левая голень" };
    /** the part move() last found turning the most */
    private static int movePart;
    /** the part, and the frame, of the fastest change so far */
    private static String maxWhat = "";

    private static float move(float[] a, float[] b) {
        float m = 0F;
        Quaternionf qa = new Quaternionf(), qb = new Quaternionf();
        for (int i = 0; i < a.length / 3; i++) {
            qa.rotationZYX(a[i * 3 + 2], a[i * 3 + 1], a[i * 3]);
            qb.rotationZYX(b[i * 3 + 2], b[i * 3 + 1], b[i * 3]);
            float d = 2F * (float) Math.acos(Math.min(1F, Math.abs(qa.dot(qb))));
            if (d > m) {
                m = d;
                movePart = i;
            }
        }
        return m;
    }

    private static String what(acup.gunfeel.ai.client.BotAnimator.Frame f) {
        StringBuilder sb = new StringBuilder(PART_NAMES[movePart]);
        int i = movePart * 3;
        sb.append(String.format(Locale.ROOT, " (%.2f %.2f %.2f → %.2f %.2f %.2f)", prev[i], prev[i + 1], prev[i + 2], seen[i], seen[i + 1], seen[i + 2]));
        sb.append(String.format(Locale.ROOT, "; присед %.2f, скорость %.3f, смерть %.3f с", f.crouch(), f.speed(), f.deathSec()));
        if (f.act() != null) sb.append(String.format(Locale.ROOT, ", действие %d u %.2f w %.2f", f.act().kind(), f.act().u(), f.act().w()));
        for (var a : f.fading()) sb.append(String.format(Locale.ROOT, ", гаснет %d w %.2f", a.kind(), a.w()));
        for (var h : f.hits()) sb.append(String.format(Locale.ROOT, ", удар %s %.2f с w %.2f", h.clip(), h.sec(), h.w()));
        sb.append(", руки");
        for (float a : f.arms()) sb.append(String.format(Locale.ROOT, " %.2f", a));
        return sb.toString();
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
                "переходы на кадрах игры: самая быстрая часть %.2f рад/тик (тик %.1f сценария: %s), самый большой шаг %.3f рад, поз %d, %.0f в секунду",
                maxSpeed, maxMoveAt, maxWhat, maxMove, moveFrames, moveFrames / seconds));
        ROWS.add(String.format(Locale.ROOT, "переходы\tсценарий\t\t\t\t\t%.4f рад/тик\t\t\t\t\t%d\t%.1f", maxSpeed, moveFrames, moveFrames / seconds));
        write();
        startClips();
    }

    private static AiPose pose(byte stance, int lean, int prevLean, int leanTick, byte arms, byte action, int actionTick,
            int actionTicks) {
        return new AiPose(stance, (byte) lean, (byte) prevLean, leanTick, arms, action, actionTick, (short) actionTicks, AiPose.FIGHT);
    }

    // ---------------------------------------------------------------- 2b. the clips on the move, a hit, death

    /** one stretch of the clips' script: from this tick on, moving so far a tick (forward, to the right) */
    private record Leg(String name, int from, double ahead, double right, String want) {
        /** lying, at the AI's crawl (AiProne.CRAWL_SPEED of its walk): the crawl (BotPoser.crawl), not a clip */
        static final String CRAWL = "ползок";
    }

    /** facing -X: ahead is -X, its right -Z */
    /** the walk and the run at the speeds the stand's agent goes at as the mob AI tells it (0.194, 0.327 blocks a tick) */
    private static final Leg[] LEGS = { new Leg("стоит", 0, 0, 0, null), new Leg("шаг", 20, 0.19, 0, BotClips.WALK),
            new Leg("трусца", 45, 0.27, 0, BotClips.JOG), new Leg("бег", 70, 0.33, 0, BotClips.SPRINT),
            new Leg("назад", 95, -0.10, 0, BotClips.BACK), new Leg("влево", 125, 0, -0.10, BotClips.STRAFE_LEFT),
            new Leg("вправо", 155, 0, 0.10, BotClips.STRAFE_RIGHT), new Leg("ползком", 185, 0.057, 0, Leg.CRAWL),
            new Leg("подкат", 215, 0.2, 0, BotClips.SLIDE), new Leg("кувырок", 240, 0.16, 0, BotClips.ROLL),
            new Leg("стоит, перезарядка", 265, 0, 0, null) };
    private static final int CLIPS_CRAWL = 185, CLIPS_UP = 215, CLIPS_SLIDE = 217, CLIPS_ROLL = 242;
    private static final int CLIPS_RELOAD = 285, CLIPS_HIT = 330, CLIPS_KNOCK = 355, CLIPS_DEATH = 380, CLIPS_END = 420;
    /** the agent with its own AI on another line, told to walk then run as the mob AI tells it (AiSoldier.WALK, RUN) */
    private static final double[] CAL = { 1.0, 1.3 };
    private static final int CAL_EACH = 70;
    private static volatile Mob cal;
    private static long clipsT0, lastServerTick;
    // per stretch: frames in its last 10 ticks, the wanted clip's weight summed, the speed summed
    private static final double[][] LEG_SUMS = new double[LEGS.length][3];
    private static final double[][] CAL_SUMS = new double[CAL.length][2];
    private static boolean reloadSeen, hitSeen, knockSeen, knockWrong, deathSeen;
    private static String hitClip = "-";
    private static double knockPush, knockSpeed;
    private static final java.util.Set<String> knockClips = new java.util.TreeSet<>();

    private static void startClips() {
        step = 4;
        var server = mc().getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            Mob m = (Mob) level.getEntity(one.getId());
            m.snapTo(10.5, O.getY(), 8.5, 90F, 0F);
            m.setYBodyRot(90F);
            m.setYHeadRot(90F);
            m.setData(AiPose.Sync.TYPE, AiPose.IDLE);
            m.setPose(Pose.STANDING);
            m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(1000);
            m.setHealth(1000);
            m.setInvulnerable(false);
            // an agent with its own legs: the game moves it at the speed the mob AI asks for
            var created = BuiltInRegistries.ENTITY_TYPE.getValue(FBI).create(level, EntitySpawnReason.COMMAND);
            if (!(created instanceof Mob c)) throw new IllegalStateException("no FBI agent");
            c.snapTo(8.5, O.getY(), 16.5, 90F, 0F);
            c.setInvulnerable(true);
            c.setPersistenceRequired();
            level.addFreshEntity(c);
            c.goalSelector.removeAllGoals(g -> true);
            c.targetSelector.removeAllGoals(g -> true);
            cal = c;
            var p = server.getPlayerList().getPlayers().getFirst();
            p.teleportTo(level, 0.5, O.getY() + 18, 11.5, Set.of(), 0F, 90F, false);
        }).join();
        int id = one.getId(), calId = cal.getId();
        clipsT0 = now() + 20;
        lastServerTick = Long.MIN_VALUE;
        posed = AiPose.IDLE;
        BotAnimation.standPose = m -> m.getId() == id ? posed : AiPose.IDLE;
        BotAnimation.standStill = false;
        seenFrame = -1;
        maxMove = maxSpeed = maxMoveAt = 0F;
        moveFrames = 0;
        maxWhat = "";
        BotAnimation.standWatch = (f, parts) -> {
            long t = now() - clipsT0;
            if (t < 0) return;
            if (f.entity() == calId) {
                int k = (int) (t / CAL_EACH);
                if (k < CAL.length && t % CAL_EACH >= CAL_EACH - 25) {
                    CAL_SUMS[k][0]++;
                    CAL_SUMS[k][1] += f.speed();
                }
                return;
            }
            if (f.entity() != id) return;
            float time = f.idleSec() * 20F;
            if (seenFrame >= 0 && time <= seenTime + 1e-4F) return;
            int leg = LEGS.length - 1;
            while (leg > 0 && t < LEGS[leg].from) leg--;
            int end = leg + 1 < LEGS.length ? LEGS[leg + 1].from : CLIPS_HIT;
            // a slide or a roll is measured in its middle (it starts 2 ticks into its stretch: 3 to 10 ticks in)
            boolean dash = LEGS[leg].want == BotClips.SLIDE || LEGS[leg].want == BotClips.ROLL;
            int from = dash ? LEGS[leg].from + 5 : end - 10, to = dash ? LEGS[leg].from + 12 : end;
            if (t >= from && t < to) {
                LEG_SUMS[leg][0]++;
                LEG_SUMS[leg][2] += f.speed();
                if (LEGS[leg].want == Leg.CRAWL) {
                    if (mc().level.getEntity(id) instanceof net.minecraft.world.entity.LivingEntity e)
                        LEG_SUMS[leg][1] += BotPoser.crawlWeight(e.getSwimAmount(1F), f.speed());
                } else if (LEGS[leg].want == BotClips.SLIDE || LEGS[leg].want == BotClips.ROLL) {
                    // the action's weight at its middle (3 to 10 ticks in)
                    byte kind = LEGS[leg].want == BotClips.SLIDE ? AiPose.SLIDE : AiPose.ROLL;
                    if (f.act() != null && f.act().kind() == kind) LEG_SUMS[leg][1] += f.act().w();
                } else if (LEGS[leg].want != null) {
                    String[] names = BotPoser.gaitClips(f.heading());
                    float[] ws = BotPoser.gaitWeights(f.speed(), f.heading());
                    for (int i = 0; i < names.length; i++) if (names[i].equals(LEGS[leg].want)) LEG_SUMS[leg][1] += ws[i];
                }
            }
            if (f.act() != null && f.act().kind() == AiPose.RELOAD) reloadSeen = true;
            for (var h : f.hits()) {
                if (t >= CLIPS_HIT && t < CLIPS_KNOCK) {
                    hitSeen = true;
                    hitClip = h.clip();
                    knockWrong |= h.clip().equals(BotClips.HIT_KNOCK);
                } else if (t >= CLIPS_KNOCK && t < CLIPS_DEATH && h.sec() < 0.3F) knockSeen |= h.clip().equals(BotClips.HIT_KNOCK);
            }
            if (f.deathSec() >= 0F) deathSeen = true;
            if (t >= CLIPS_KNOCK && t < CLIPS_KNOCK + 5) {
                var e = mc().level.getEntity(id);
                if (e != null) knockPush = Math.max(knockPush, e.getDeltaMovement().horizontalDistance());
                knockSpeed = Math.max(knockSpeed, f.speed());
                for (var h : f.hits()) knockClips.add(h.clip());
            }
            store(parts);
            if (seenFrame >= 0) {
                float m = move(prev, seen), speed = m / (time - seenTime);
                moveFrames++;
                maxMove = Math.max(maxMove, m);
                if (speed > maxSpeed) {
                    maxSpeed = speed;
                    maxMoveAt = t;
                    maxWhat = what(f);
                }
            }
            System.arraycopy(seen, 0, prev, 0, seen.length);
            seenFrame = 1;
            seenTime = time;
        };
        measuring = 0;
    }

    private static void clips() {
        long t = now() - clipsT0;
        if (t < 0 || now() == lastServerTick) return;
        lastServerTick = now();
        if (measuring == 0) measuring = System.nanoTime();
        var server = mc().getSingleplayerServer();
        posed = t >= CLIPS_RELOAD && t < CLIPS_HIT ? new AiPose(AiPose.STAND, (byte) 0, (byte) 0, 0, AiPose.HOLD, AiPose.RELOAD,
                (int) (clipsT0 + CLIPS_RELOAD), (short) 30, AiPose.FIGHT)
                : t >= CLIPS_CRAWL && t < CLIPS_UP ? new AiPose(AiPose.PRONE, (byte) 0, (byte) 0, 0, AiPose.HOLD, AiPose.NONE, 0, (short) 0, AiPose.FIGHT)
                // a slide, a roll: crouched as the server keeps it (pose-contract §4), from a run
                : t >= CLIPS_SLIDE && t < CLIPS_ROLL ? new AiPose(AiPose.CROUCH, (byte) 0, (byte) 0, 0, AiPose.HOLD, AiPose.SLIDE,
                        (int) (clipsT0 + CLIPS_SLIDE), (short) acup.gunfeel.ai.AiDash.TICKS, AiPose.FIGHT)
                : t >= CLIPS_ROLL && t < CLIPS_ROLL + acup.gunfeel.ai.AiDash.TICKS + 2 ? new AiPose(AiPose.CROUCH, (byte) 0, (byte) 0, 0,
                        AiPose.HOLD, AiPose.ROLL, (int) (clipsT0 + CLIPS_ROLL), (short) acup.gunfeel.ai.AiDash.TICKS, AiPose.FIGHT) : AiPose.IDLE;
        int leg = LEGS.length - 1;
        while (leg > 0 && t < LEGS[leg].from) leg--;
        Leg l = LEGS[leg];
        long tt = t;
        server.submit(() -> {
            ServerLevel level = server.overworld();
            Mob m = (Mob) level.getEntity(one.getId());
            if (m != null && m.isAlive() && tt < CLIPS_RELOAD && (l.ahead != 0 || l.right != 0))
                m.setPos(m.getX() - l.ahead, m.getY(), m.getZ() - l.right);
            // the crawl: lying as the mob AI lays it (AiProne), up again after
            if (m != null && tt == CLIPS_CRAWL) m.setPose(Pose.SWIMMING);
            if (m != null && tt == CLIPS_UP) m.setPose(Pose.STANDING);
            if (m != null && tt == CLIPS_HIT) m.hurtServer(level, level.damageSources().generic(), 1F);
            if (m != null && tt == CLIPS_KNOCK) {
                // a blast: hit, then thrown back as vanilla's explosion does it (hurt, then pushed)
                m.hurtServer(level, level.damageSources().generic(), 1F);
                m.setDeltaMovement(1.0, 0.4, 0);
                m.hurtMarked = true;
            }
            if (m != null && tt == CLIPS_DEATH) m.kill(level);
            Mob c = cal;
            int k = (int) (tt / CAL_EACH);
            if (c != null && k < CAL.length) {
                // to and fro along its line, at the AI's speed for this stretch
                double goal = (tt / 35) % 2 == 0 ? -8.5 : 8.5;
                c.getMoveControl().setWantedPosition(goal, c.getY(), 16.5, CAL[k]);
            }
        });
        if (t < CLIPS_END) return;
        BotAnimation.standWatch = null;
        server.submit(() -> {
            if (cal != null) cal.discard();
        }).join();
        double seconds = (System.nanoTime() - measuring) / 1e9;
        for (int i = 0; i < LEGS.length; i++) {
            double[] sm = LEG_SUMS[i];
            double n = Math.max(1, sm[0]), w = sm[1] / n, v = sm[2] / n;
            Leg g = LEGS[i];
            ROWS.add(String.format(Locale.ROOT, "клипы	%s	%s	вес %.2f	скорость %.3f бл/тик							%d", g.name, g.want == null ? "-" : g.want, w, v,
                    (long) sm[0]));
            if (g.want == null) ClientSmoke.check(sm[0] > 0 && v < 0.01, String.format(Locale.ROOT, "клипы: %s, ноги не идут (скорость %.3f, кадров %d)", g.name, v, (long) sm[0]));
            else ClientSmoke.check(sm[0] > 0 && w >= 0.7, String.format(Locale.ROOT, "клипы: %s играет %s (вес %.2f, скорость %.3f, кадров %d)", g.name, g.want, w, v,
                    (long) sm[0]));
        }
        for (int i = 0; i < CAL.length; i++) {
            double n = Math.max(1, CAL_SUMS[i][0]);
            float v = (float) (CAL_SUMS[i][1] / n);
            float[] ws = BotPoser.gaitWeights(v, 0F);
            ROWS.add(String.format(Locale.ROOT, "клипы	агент ×%.1f		скорость %.3f бл/тик	шаг %.2f трусца %.2f бег %.2f							%d", CAL[i], v, ws[0], ws[1], ws[2],
                    (long) CAL_SUMS[i][0]));
            // the AI's walk is a walk, its run a run (a jog or a sprint)
            boolean right = i == 0 ? ws[0] >= 0.9F : ws[1] + ws[2] >= 0.9F;
            ClientSmoke.check(CAL_SUMS[i][0] > 0 && right, String.format(Locale.ROOT, "клипы: агент на скорости ИИ ×%.1f идёт %.3f бл/тик: шаг %.2f, трусца %.2f, бег %.2f",
                    CAL[i], v, ws[0], ws[1], ws[2]));
        }
        ClientSmoke.check(reloadSeen && BotClips.get(BotClips.RELOAD) != null, "клипы: перезарядка идёт клипом");
        ClientSmoke.check(hitSeen && !knockWrong, "клипы: попадание на месте — вздрагивание (" + hitClip + "), не отброс");
        ClientSmoke.check(knockSeen, String.format(Locale.ROOT, "клипы: попадание с отбросом — клип отброса (в первые 5 тиков: скорость в пакете до %.3f,"
                + " по ходу до %.3f бл/тик, клипы %s)", knockPush, knockSpeed, knockClips));
        ClientSmoke.check(deathSeen && BotClips.get(BotClips.DEATH) != null, "клипы: смерть идёт клипом");
        // the clips move faster than the script's changes: the sprint's shin swings 2.5 radians a tick at full speed
        // (BotMoveClipsTest); a jump in a frame (a thousandth of a second) is 7 and more
        ClientSmoke.check(moveFrames > 200 && maxSpeed < 4F, String.format(Locale.ROOT,
                "клипы без рывков (меньше 4 рад/тик): самая быстрая часть %.2f рад/тик (тик %.0f сценария: %s), самый большой шаг %.3f рад, поз %d, %.0f в секунду",
                maxSpeed, maxMoveAt, maxWhat, maxMove, moveFrames, moveFrames / seconds));
        ROWS.add(String.format(Locale.ROOT, "переходы	клипы					%.4f рад/тик					%d	%.1f", maxSpeed, moveFrames, moveFrames / seconds));
        write();
        startCost();
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
            return new AiPose(stance, lean, prevLean, leanTick, arms, AiPose.RELOAD, actTick, (short) 30, AiPose.FIGHT);
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
