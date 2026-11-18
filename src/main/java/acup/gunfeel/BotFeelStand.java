package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntFunction;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import acup.gunfeel.ai.AiPose;
import acup.gunfeel.ai.client.BotAnimation;
import acup.gunfeel.ai.client.BotAnimator;
import acup.gunfeel.ai.client.BotPoser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;

/**
 * -PbotFeelStand: how an AI mob's body moves, frame by frame, in the moves a player sees most: a turn on the spot of
 * 90 and 180 degrees at the aim's speed, a turn on the walk and on the run, a start and a stop, a crouch on the walk,
 * the gun raised, a strafe turned back, a hit. Nothing is judged here: each frame drawn (the client's own, at 60 a
 * second) goes to {@code <label>-feel.tsv} as numbers (where the model is, how its body and head face, each part's
 * turn in the world, where its feet, hands, hips and head are), each server tick to {@code <label>-feel-ticks.tsv}
 * (the yaw and the place the server has). tools/motion/feel.py works out the turn's speed, its lag, the jerk and the
 * feet's slide, the same way as from a recording of people.
 *
 * <p>Two agents: one the stand moves and turns itself each tick (no AI: the turn of the aim, AiGunner.face, at 30
 * degrees a tick; a walk set step by step), one with its own legs (its goals taken away) the stand sends somewhere at
 * the mob AI's walk and run (the game's own start, stop and turn of the path).
 */
final class BotFeelStand {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.botFeelStand");
    private static final Identifier FBI = Identifier.fromNamespaceAndPath("hbm", "entity_fbi");
    private static final BlockPos O = new BlockPos(0, 100, 0);
    /** the aim's turn, degrees a tick (AiSkill regular and veteran) */
    static final float AIM_TURN = 30F;
    /** the mob AI's walk and run, as the move control's speed (AiSoldier.WALK, RUN) */
    static final double WALK = 1.0, RUN = 1.3;
    /** ticks each scene is set up and left still before it is recorded */
    static final int SETTLE = 25;

    /** a scene: who is watched, how long, what the server does each tick, the pose it is drawn in */
    private record Scene(String name, boolean live, int ticks, Step step, IntFunction<AiPose> pose) {
    }

    @FunctionalInterface
    private interface Step {
        /** tick t of the scene (below 0: settling) on the server, with the agent watched */
        void at(ServerLevel level, Mob m, int t);
    }

    private static AiPose pose(byte stance, byte arms, byte alert) {
        return new AiPose(stance, (byte) 0, (byte) 0, 0, arms, AiPose.NONE, 0, (short) 0, alert);
    }

    /** ... with an action from the game's tick {@code at} for {@code ticks} */
    private static AiPose pose(byte stance, byte arms, byte alert, byte action, long at, int ticks) {
        return new AiPose(stance, (byte) 0, (byte) 0, 0, arms, action, (int) at, (short) ticks, alert);
    }

    private static final AiPose AIMED = pose(AiPose.STAND, AiPose.AIM, AiPose.FIGHT),
            READY = pose(AiPose.STAND, AiPose.HOLD, AiPose.FIGHT), CALM = pose(AiPose.STAND, AiPose.HOLD, AiPose.CALM);

    /** run_turn180: on its way back */
    private static boolean turned;

    /** where the scripted agent stands, facing -Z (yaw 180) */
    private static final double AX = 0.5, AZ = 24.5;

    private static final Scene[] SCENES = { turn("turn90", 90F), turn("turn180", 180F),
            // a slow turn on the spot (a calm look round; the moves from recordings, BotMoves, play under 150 a second)
            turn("turn90_slow", 90F, 5F), turn("turn180_slow", 180F, 6F),
            new Scene("walk_turn90", true, 100, (level, m, t) -> {
                if (t < 0) {
                    place(m, -6.5, 4.5, -90F);
                    return;
                }
                // ahead, then to its left: the path turns a right angle half a block before the corner
                go(m, m.getX() < 0.0 && m.getZ() < 5.0 ? new double[] { 0.5, 4.5 } : new double[] { 0.5, 12.5 }, WALK);
            }, t -> CALM),
            new Scene("run_turn180", true, 90, (level, m, t) -> {
                if (t < 0) {
                    place(m, -8.5, 9.5, -90F);
                    return;
                }
                if (t == 0) turned = false;
                if (!turned && m.getX() > 3.8) turned = true;
                go(m, turned ? new double[] { -4.5, 9.5 } : new double[] { 4.5, 9.5 }, RUN);
            }, t -> READY),
            new Scene("start_stop", true, 140, (level, m, t) -> {
                if (t < 0) {
                    place(m, -8.5, 14.5, -90F);
                    return;
                }
                if (t >= 15 && t < 70) go(m, new double[] { -3.5, 14.5 }, WALK);
                else if (t >= 70) go(m, new double[] { 5.5, 14.5 }, RUN);
            }, t -> READY),
            new Scene("walk_crouch_walk", false, 75, (level, m, t) -> {
                if (t < 0) {
                    place(m, -5.5, AZ, -90F);
                    return;
                }
                // a walk, a crouch on the walk (slower), a walk again: as the AI asks for it, at once
                double v = t >= 20 && t < 45 ? 0.1 : 0.19;
                m.setPos(m.getX() + v, m.getY(), m.getZ());
            }, t -> t >= 20 && t < 45 ? pose(AiPose.CROUCH, AiPose.HOLD, AiPose.FIGHT) : READY),
            new Scene("raise", false, 100, (level, m, t) -> {
                if (t < 0) place(m, AX, AZ, 180F);
            }, t -> t < 20 ? CALM : t < 50 ? AIMED : t < 75 ? READY : CALM),
            new Scene("strafe", false, 90, (level, m, t) -> {
                if (t < 0) {
                    place(m, AX, AZ, 180F);
                    return;
                }
                // facing -Z its right is -X: right, left, right, then still
                double v = t < 20 ? -0.1 : t < 40 ? 0.1 : t < 60 ? -0.1 : 0.0;
                if (v != 0.0) m.setPos(m.getX() + v, m.getY(), m.getZ());
            }, t -> AIMED),
            new Scene("hit", false, 90, (level, m, t) -> {
                if (t < 0) {
                    place(m, AX, AZ, 180F);
                    // the agent's health, the trunk's push (BotImpulse) going by the share of it taken
                    m.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(20);
                    m.setHealth(20F);
                    return;
                }
                // a rifle bullet's damage
                if (t == 10 || t == 40) m.hurtServer(level, level.damageSources().generic(), 6F);
            }, t -> AIMED),
            // aimed, a reload (its clip: the arms only, the head on its aim), aimed again
            new Scene("reload", false, 90, (level, m, t) -> {
                if (t < 0) place(m, AX, AZ, 180F);
            }, t -> t >= 20 && t < 60 ? pose(AiPose.STAND, AiPose.AIM, AiPose.FIGHT, AiPose.RELOAD, BotFeelStand.sceneStart + 20, 40) : AIMED) };

    /** a turn on the spot by the aim, from facing -Z */
    private static Scene turn(String name, float by) {
        return turn(name, by, AIM_TURN);
    }

    /** ... at most {@code most} degrees a tick */
    private static Scene turn(String name, float by, float most) {
        return new Scene(name, false, 70, (level, m, t) -> {
            if (t < 0) {
                place(m, AX, AZ, 180F);
                return;
            }
            float to = t < 15 ? 180F : 180F + by, y = m.getYRot();
            float d = Mth.clamp(Mth.wrapDegrees(to - y), -most, most);
            // AiGunner.face: the head, the body and the yaw all at the aim's
            face(m, y + d);
        }, t -> AIMED);
    }

    private static void face(Mob m, float yaw) {
        m.setYRot(yaw);
        m.yBodyRot = yaw;
        m.setYHeadRot(yaw);
    }

    private static void place(Mob m, double x, double z, float yaw) {
        m.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        m.snapTo(x, O.getY(), z, yaw, 0F);
        face(m, yaw);
    }

    /** the live agent's legs: there at this speed; within a third of a block it stops asking (the game's friction) */
    private static void go(Mob m, double[] to, double speed) {
        if (to == null) return;
        double dx = to[0] - m.getX(), dz = to[1] - m.getZ();
        if (dx * dx + dz * dz > 0.1) m.getMoveControl().setWantedPosition(to[0], m.getY(), to[1], speed);
    }

    private static Mob scripted, live;
    private static int scene, lastTick = Integer.MIN_VALUE;
    private static long sceneStart;
    private static final List<String> FRAMES = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> TICKS = Collections.synchronizedList(new ArrayList<>());
    private static final int[] COUNT = new int[SCENES.length];
    /** the last frame recorded, by its entity age: the model is posed more than once a frame */
    private static float lastAge = Float.NaN;

    private BotFeelStand() {
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static long now() {
        return mc().level.getGameTime();
    }

    static void start(Mob agent) {
        Minecraft mc = mc();
        mc.options.framerateLimit().set(Integer.getInteger("ntmgunfeel.benchFps", 60));
        StringBuilder head = new StringBuilder("сцена\tтик\tt\tкадр_мс\tx\ty\tz\tbody_rot\thead_rel\tyaw_received\tyaw_entity\thead_entity"
                + "\tприсед\tскорость\tкурс\tтревога\tприцел\twalk\twalk_pos");
        for (String p : PARTS) head.append('\t').append(p).append("_qw\t").append(p).append("_qx\t").append(p).append("_qy\t").append(p).append("_qz");
        for (String p : POINTS) head.append('\t').append(p).append("_x\t").append(p).append("_y\t").append(p).append("_z");
        head.append("\tright_sole\tleft_sole\tmoves\tmove_len\tmove_t\tmove_w\tmove_twist\tmove2_w");
        FRAMES.add(head.toString());
        TICKS.add("сцена\tтик\tx\ty\tz\tyaw\tbody\thead\tна_земле");
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            scripted = (Mob) level.getEntity(agent.getId());
            scripted.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(1000);
            scripted.setHealth(1000);
            scripted.setInvulnerable(false);
            var created = BuiltInRegistries.ENTITY_TYPE.getValue(FBI).create(level, EntitySpawnReason.COMMAND);
            if (!(created instanceof Mob c)) throw new IllegalStateException("no FBI agent");
            c.snapTo(-8.5, O.getY(), 4.5, -90F, 0F);
            c.setInvulnerable(true);
            c.setPersistenceRequired();
            level.addFreshEntity(c);
            c.goalSelector.removeAllGoals(g -> true);
            c.targetSelector.removeAllGoals(g -> true);
            // posed as an AI mob: its turn goes to the client every tick, as an AI mob's (MixinAiTurnSync)
            c.setData(AiPose.Sync.TYPE, AiPose.IDLE);
            live = c;
            place(scripted, AX, AZ, 180F);
            scripted.setData(AiPose.Sync.TYPE, AiPose.IDLE);
            scripted.setPose(Pose.STANDING);
            // over the floor, looking down on both
            var p = server.getPlayerList().getPlayers().getFirst();
            p.teleportTo(level, 0.5, O.getY() + 24, 14.5, Set.of(), 0F, 90F, false);
        }).join();
        int a = scripted.getId(), l = live.getId();
        BotAnimation.standStill = false;
        BotAnimation.standPose = m -> {
            Scene s = SCENES[Math.min(scene, SCENES.length - 1)];
            int id = s.live ? l : a;
            if (m.getId() != id) return m.getId() == a || m.getId() == l ? CALM : AiPose.IDLE;
            int t = (int) (now() - sceneStart);
            return s.pose.apply(Math.max(0, t));
        };
        BotAnimation.standFeel = BotFeelStand::record;
        scene = 0;
        sceneStart = now() + SETTLE;
    }

    static void frame() {
        long tick = now();
        if (tick == lastTick) return;
        boolean back = tick < lastTick;
        lastTick = (int) tick;
        if (scene >= SCENES.length) return;
        if (back) {
            // the world's time stepped back (the server, behind at the start, sent its own): the scene again from
            // its start, its frames so far left out
            String mark = SCENES[scene].name + "\t";
            FRAMES.removeIf(r -> r.startsWith(mark));
            TICKS.removeIf(r -> r.startsWith(mark));
            sceneStart = tick + SETTLE;
            return;
        }
        Scene s = SCENES[scene];
        int t = (int) (tick - sceneStart);
        if (t >= s.ticks) {
            scene++;
            if (scene >= SCENES.length) {
                finish();
                return;
            }
            sceneStart = tick + SETTLE;
            return;
        }
        int id = (s.live ? live : scripted).getId();
        String name = s.name;
        var server = mc().getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            if (!(level.getEntity(id) instanceof Mob m)) return;
            s.step.at(level, m, t);
            if (t >= 0) TICKS.add(String.format(Locale.ROOT, "%s\t%d\t%.4f\t%.4f\t%.4f\t%.3f\t%.3f\t%.3f\t%b", name, t, m.getX() - O.getX(),
                    m.getY() - O.getY(), m.getZ() - O.getZ(), m.getYRot(), m.yBodyRot, m.getYHeadRot(), m.onGround()));
        });
    }

    /** the model's ten parts, each upper limb before its lower (BotPoser.ALL) */
    private static final String[] PARTS = { "head", "body", "right_arm", "right_forearm", "left_arm", "left_forearm", "right_leg",
            "right_shin", "left_leg", "left_shin" };

    /** the points recorded in the world: on which part, where on it (px in its own frame) */
    private static final String[] POINTS = { "head", "neck", "pelvis", "right_ankle", "left_ankle", "right_hand", "left_hand" };
    private static final float[][] AT = { { 0F, -4F, 0F }, { 0F, 0F, 0F }, { 0F, 12F, 0F }, { 0F, 6F, 0F }, { 0F, 6F, 0F }, { 0F, 6F, 0F },
            { 0F, 6F, 0F } };
    private static final String[] ON = { "head", "body", "body", "right_shin", "left_shin", "right_forearm", "left_forearm" };

    private static void record(BotAnimator.Frame f, BotPoser.Parts p, HumanoidRenderState s) {
        if (scene >= SCENES.length) return;
        Scene sc = SCENES[scene];
        Mob who = sc.live ? live : scripted;
        if (who == null || f.entity() != who.getId()) return;
        float t = (float) (mc().level.getGameTime() - sceneStart) + s.partialTick;
        if (t < 0F || t >= sc.ticks) return;
        if (s.ageInTicks == lastAge) return;
        lastAge = s.ageInTicks;
        var e = mc().level.getEntity(who.getId());
        float received = e != null ? e.getInterpolation().yRot() : Float.NaN, yaw = e != null ? e.getYRot() : Float.NaN,
                head = e instanceof net.minecraft.world.entity.LivingEntity le ? le.getYHeadRot() : Float.NaN;
        StringBuilder row = new StringBuilder(64 * 16);
        row.append(sc.name).append('\t').append((int) Math.floor(t)).append('\t').append(fmt(t / 20F)).append('\t')
                .append(String.format(Locale.ROOT, "%.3f", System.nanoTime() / 1e6)).append('\t')
                .append(fmt(s.x - O.getX())).append('\t').append(fmt(s.y - O.getY())).append('\t').append(fmt(s.z - O.getZ())).append('\t')
                .append(fmt(s.bodyRot)).append('\t').append(fmt(s.yRot)).append('\t').append(fmt(received)).append('\t').append(fmt(yaw))
                .append('\t').append(fmt(head)).append('\t').append(fmt(f.crouch())).append('\t').append(fmt(f.speed())).append('\t')
                .append(fmt(f.heading())).append('\t').append(fmt(f.alert())).append('\t').append(fmt(f.arm(AiPose.AIM))).append('\t')
                .append(fmt(f.walk())).append('\t').append(fmt(f.walkPos()));
        Matrix4f base = new Matrix4f().translation((float) (s.x - O.getX()), (float) (s.y - O.getY()), (float) (s.z - O.getZ()))
                .scale(s.scale).rotateY((180F - s.bodyRot) * Mth.DEG_TO_RAD).scale(-1F, -1F, 1F).translate(0F, -1.501F, 0F);
        java.util.Map<String, Matrix4f> world = new java.util.HashMap<>();
        for (String name : PARTS) {
            ModelPart part = part(p, name);
            Matrix4f m = new Matrix4f(name.endsWith("forearm") || name.endsWith("shin") ? world.get(upper(name)) : base);
            if (part != null) m.translate(part.x / 16F, part.y / 16F, part.z / 16F)
                    .rotate(new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot));
            world.put(name, m);
            Quaternionf q = m.getNormalizedRotation(new Quaternionf());
            row.append('\t').append(fmt(q.w)).append('\t').append(fmt(q.x)).append('\t').append(fmt(q.y)).append('\t').append(fmt(q.z));
        }
        Vector3f v = new Vector3f();
        for (int i = 0; i < POINTS.length; i++) {
            world.get(ON[i]).transformPosition(v.set(AT[i][0] / 16F, AT[i][1] / 16F, AT[i][2] / 16F));
            row.append('\t').append(fmt(v.x)).append('\t').append(fmt(v.y)).append('\t').append(fmt(v.z));
        }
        // each sole's lowest corner, its height over the floor
        for (String shin : new String[] { "right_shin", "left_shin" }) {
            float low = Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                world.get(shin).transformPosition(v.set(((i & 1) * 4F - 2F) / 16F, 6F / 16F, ((i >> 1) * 4F - 2F) / 16F));
                low = Math.min(low, v.y);
            }
            row.append('\t').append(fmt(low));
        }
        // the moves from recordings (BotMoves): how many, the first's clip (its length), time, weight, legs' turn (degrees)
        BotAnimator.Moving[] mv = f.moves();
        row.append('\t').append(mv.length);
        if (mv.length > 0) row.append('\t').append(fmt(mv[0].clip().length())).append('\t').append(fmt(mv[0].t())).append('\t')
                .append(fmt(mv[0].w())).append('\t').append(fmt(mv[0].twist() * Mth.RAD_TO_DEG));
        else row.append("\t0\t0\t0\t0");
        row.append('\t').append(fmt(mv.length > 1 ? mv[1].w() : 0F));
        FRAMES.add(row.toString());
        COUNT[scene]++;
    }

    private static String upper(String lower) {
        return lower.replace("forearm", "arm").replace("shin", "leg");
    }

    private static ModelPart part(BotPoser.Parts p, String name) {
        return switch (name) {
            case "head" -> p.head();
            case "body" -> p.body();
            case "right_arm" -> p.rightArm();
            case "left_arm" -> p.leftArm();
            case "right_leg" -> p.rightLeg();
            case "left_leg" -> p.leftLeg();
            case "right_forearm" -> p.rightForearm();
            case "left_forearm" -> p.leftForearm();
            case "right_shin" -> p.rightShin();
            case "left_shin" -> p.leftShin();
            default -> null;
        };
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.5f", d);
    }

    private static void finish() {
        BotAnimation.standFeel = null;
        BotAnimation.standPose = null;
        String base = System.getProperty("ntmgunfeel.botStandFile").replaceFirst("\\.tsv$", "");
        try {
            Path f = Path.of(base + "-feel.tsv");
            Files.createDirectories(f.getParent());
            synchronized (FRAMES) {
                Files.write(f, FRAMES);
            }
            synchronized (TICKS) {
                Files.write(Path.of(base + "-feel-ticks.tsv"), TICKS);
            }
        } catch (Exception e) {
            ClientSmoke.check(false, "ощущение: запись " + e);
        }
        for (int i = 0; i < SCENES.length; i++) {
            // 60 frames a second wanted: at least 2 a tick
            ClientSmoke.check(COUNT[i] >= SCENES[i].ticks * 2, String.format(Locale.ROOT, "ощущение: сцена %s, кадров %d за %d тиков",
                    SCENES[i].name, COUNT[i], SCENES[i].ticks));
        }
        ClientSmoke.finish();
    }
}
