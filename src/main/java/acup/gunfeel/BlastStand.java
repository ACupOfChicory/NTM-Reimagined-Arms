package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.explosion.vanillant.standard.EntityProcessorCrossSmooth;
import com.hbm.explosion.vanillant.standard.EntityProcessorStandard;
import com.hbm.explosion.vanillant.standard.PlayerProcessorStandard;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.ai.AiPose;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The blast stand (-PblastStandLabel, runBlastStand), in numbers: NTM's blasts by the body's parts (BlastParts) against
 * NTM's own, on an FBI agent and the test dummy.
 * <ol>
 * <li>A hand grenade's HE filling (NTM's ItemGrenadeFilling.standardExplode: CrossSmooth, range 7.5, 25 damage, DT 10
 * off, DR 10 % off) lying on flat ground at 1, 2 and 3 blocks, the mob standing, crouching, lying with its head toward it
 * and lying with its feet toward it; and a dynamite charge (EntityProcessorStandard, size 4) at 2 blocks. Standing and
 * crouching the distance is from the mob's middle line; lying, from the end of its body on that side (its head, its
 * feet). Each blast twice, NTM's and by the parts: the health taken, and each part's distance over the range, the share
 * of its rays clear, its share and NTM's damage for it.</li>
 * <li>The same grenade at 2 blocks behind a wall one block high, standing and lying head toward it.</li>
 * <li>The agent in SECURITY standing at 1, 2, 3 blocks: the armour first, then the parts' multiplier.</li>
 * <li>The cost: one blast among 1, 10 and 50 agents (its time, ms), and MK108's 40 mm HE shells (its own round, as the
 * gun fires it) one a tick into 100 agents for 10 s (the blasts' time a tick and the server's tick).</li>
 * </ol>
 */
final class BlastStand {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.blastStand");
    private static final Identifier FBI = Identifier.fromNamespaceAndPath("hbm", "entity_fbi"),
            DUMMY = Identifier.fromNamespaceAndPath("hbm", "entity_test_dummy");
    private static final BlockPos O = new BlockPos(0, 100, 0);
    /** where the mob stands, facing north (-z) */
    private static final Vec3 A = new Vec3(0.5, O.getY(), 4.5);
    private static final float GROUND = 0.15F;
    private static final List<String> ROWS = new ArrayList<>();

    private record Step(int ticks, Runnable server) {
    }

    private static final List<Step> PLAN = new ArrayList<>();
    private static int at;
    private static long until;
    private static volatile Mob one;
    private static final List<Mob> crowd = new ArrayList<>();
    private static final List<Vec3> homes = new ArrayList<>();

    private BlastStand() {
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    static void start() {
        Minecraft mc = mc();
        mc.options.framerateLimit().set(120);
        mc.options.enableVsync().set(false);
        mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
        mc.options.pauseOnLostFocus = false;
        ROWS.add("# стоя и присев расстояние от средней линии моба, лёжа — от конца тела с той стороны; ds — расстояние части / радиус,"
                + " видно — доля лучей части без блоков, доля — по площади, NTM — урон NTM для части");
        ROWS.add("строка\tмоб\tпоза\tвзрыв\tрасстояние\tброня\tNTM_урон\tпо_частям_урон\tсумма_частей_до_множителя\tсредний_множитель"
                + "\tчасть\tмножитель\tдоля\tds\tвидно\tNTM_части");
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server);
            level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
            level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER, false, server);
            for (int x = -14; x <= 14; x++) for (int z = -10; z <= 24; z++) {
                level.setBlock(O.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 0; y <= 8; y++) level.setBlock(O.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
            var p = server.getPlayerList().getPlayers().getFirst();
            MobStageCleanup.clear(level, p);
            p.setGameMode(GameType.CREATIVE);
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
            look(level, A);
        }).join();
        plan();
        at = 0;
        until = now() + 20;
    }

    private static long now() {
        return mc().level.getGameTime();
    }

    static void frame() {
        try {
            if (now() < until) return;
            if (at >= PLAN.size()) {
                write();
                ClientSmoke.check(true, "стенд взрыва завершён");
                ClientSmoke.finish();
                at = Integer.MAX_VALUE;
                return;
            }
            Step s = PLAN.get(at++);
            mc().getSingleplayerServer().submit(s.server()).join();
            until = now() + s.ticks();
        } catch (Throwable t) {
            ClientSmoke.check(false, "стенд взрыва: " + t);
            write();
            ClientSmoke.finish();
            at = Integer.MAX_VALUE;
        }
    }

    // ---------------------------------------------------------------- the plan

    private enum Stance {
        STAND("стоя"), CROUCH("присев"), PRONE_HEAD("лёжа головой"), PRONE_FEET("лёжа ногами");

        final String name;

        Stance(String name) {
            this.name = name;
        }
    }

    private static void plan() {
        String boxes = System.getProperty("ntmgunfeel.blastStandBoxes");
        if (boxes != null && !boxes.isEmpty()) {
            boxes(Path.of(boxes));
            return;
        }
        for (Identifier type : new Identifier[] { FBI, DUMMY }) {
            String mob = type == FBI ? "агент" : "манекен";
            PLAN.add(new Step(5, () -> {
                if (one != null) one.discard();
                one = spawn(level(), type, A, 180F);
            }));
            for (Stance st : Stance.values()) {
                PLAN.add(new Step(30, () -> pose(one, st)));
                for (int d = 1; d <= 3; d++) {
                    int dd = d;
                    PLAN.add(new Step(12, () -> blast(mob, st, "граната HE", dd, false, "", BlastStand::grenade)));
                }
                PLAN.add(new Step(12, () -> blast(mob, st, "динамит", 2, false, "", BlastStand::dynamite)));
            }
            for (Stance st : new Stance[] { Stance.STAND, Stance.PRONE_HEAD }) {
                PLAN.add(new Step(30, () -> pose(one, st)));
                PLAN.add(new Step(12, () -> blast(mob, st, "граната HE", 2, true, "", BlastStand::grenade)));
            }
            PLAN.add(new Step(30, () -> pose(one, Stance.STAND)));
        }
        // the agent in SECURITY
        PLAN.add(new Step(5, () -> {
            one.discard();
            one = spawn(level(), FBI, A, 180F);
            BotStandHits.wear(one, BotStandHits.LOADOUTS[1]);
        }));
        PLAN.add(new Step(40, () -> pose(one, Stance.STAND)));
        for (int d = 1; d <= 3; d++) {
            int dd = d;
            PLAN.add(new Step(12, () -> blast("агент", Stance.STAND, "граната HE", dd, false, "SECURITY", BlastStand::grenade)));
        }
        // the cost
        for (int n : new int[] { 1, 10, 50 }) {
            PLAN.add(new Step(80, () -> ring(n)));
            PLAN.add(new Step(5, () -> cost(n)));
        }
        PLAN.add(new Step(100, () -> grid(100)));
        PLAN.add(new Step(5, BlastStand::mk108));
    }

    // ---------------------------------------------------------------- the boxes for the mob AI (c7)

    /** the agent's boxes as the server keeps them, crouching and lying, in bot-boxes-10.txt's form, written to {@code out} */
    private static void boxes(Path out) {
        List<String> lines = new ArrayList<>();
        lines.add("# Bot model of 10 parts (BotModel), boxes as ModelHits keeps them on the server (from the client's pose): px of the");
        lines.add("# model (16 a block), from the feet, yBodyRot 0 (facing +z; the bot's right is -x; up +y). c = centre; u, v, w = the");
        lines.add("# box's edges (corner = c - (u+v+w)/2). Two boxes named .../forearm and .../shin: the second is the 2-px overlap.");
        lines.add("# CROUCH = AiPose CROUCH + Pose.CROUCHING; PRONE = AiPose PRONE + Pose.SWIMMING (the bots' lying). No lean.");
        String[] names = { "CROUCH_HOLD", "CROUCH_AIM", "CROUCH_BLIND", "PRONE_HOLD", "PRONE_AIM" };
        byte[] stances = { AiPose.CROUCH, AiPose.CROUCH, AiPose.CROUCH, AiPose.PRONE, AiPose.PRONE };
        byte[] arms = { AiPose.HOLD, AiPose.AIM, AiPose.BLIND, AiPose.HOLD, AiPose.AIM };
        PLAN.add(new Step(5, () -> one = spawn(level(), FBI, A, 0F)));
        for (int i = 0; i < names.length; i++) {
            int k = i;
            PLAN.add(new Step(40, () -> {
                int t = (int) one.level().getGameTime();
                one.setData(AiPose.Sync.TYPE, new AiPose(stances[k], (byte) 0, (byte) 0, t, arms[k], AiPose.NONE, 0, (short) 0));
                one.setPose(stances[k] == AiPose.PRONE ? Pose.SWIMMING : Pose.CROUCHING);
            }));
            PLAN.add(new Step(1, () -> {
                var got = ModelHits.worldBoxes(one);
                if (got.isEmpty()) ClientSmoke.check(false, "коробки " + names[k] + ": у сервера нет позы");
                for (ModelBox b : got) {
                    double cx = b.x() + (b.ux() + b.vx() + b.wx()) / 2 - A.x, cy = b.y() + (b.uy() + b.vy() + b.wy()) / 2 - A.y,
                            cz = b.z() + (b.uz() + b.vz() + b.wz()) / 2 - A.z;
                    lines.add(String.format(Locale.ROOT, "%s %s c=(%.3f,%.3f,%.3f) u=(%.3f,%.3f,%.3f) v=(%.3f,%.3f,%.3f) w=(%.3f,%.3f,%.3f)", names[k],
                            b.part(), cx * 16, cy * 16, cz * 16, b.ux() * 16, b.uy() * 16, b.uz() * 16, b.vx() * 16, b.vy() * 16, b.vz() * 16,
                            b.wx() * 16, b.wy() * 16, b.wz() * 16));
                }
                ClientSmoke.check(!got.isEmpty(), "коробки " + names[k] + ": " + got.size());
                try {
                    Files.write(out, lines);
                } catch (java.io.IOException e) {
                    ClientSmoke.check(false, "коробки: " + e);
                }
            }));
        }
    }

    private static ServerLevel level() {
        return mc().getSingleplayerServer().overworld();
    }

    private static void look(ServerLevel level, Vec3 target) {
        var p = level.getServer().getPlayerList().getPlayers().getFirst();
        Vec3 eye = new Vec3(target.x, O.getY() + 10, target.z - 14);
        double dx = target.x - eye.x, dy = target.y + 0.5 - eye.y, dz = target.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        p.teleportTo(level, eye.x, eye.y - p.getEyeHeight(), eye.z, Set.of(), yaw, pitch, false);
    }

    private static Mob spawn(ServerLevel level, Identifier type, Vec3 at, float yaw) {
        var created = BuiltInRegistries.ENTITY_TYPE.getValue(type).create(level, EntitySpawnReason.COMMAND);
        if (!(created instanceof Mob mob)) throw new IllegalStateException("no " + type);
        mob.snapTo(at.x, at.y, at.z, yaw, 0F);
        mob.setYBodyRot(yaw);
        mob.setYHeadRot(yaw);
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100_000);
        mob.setHealth(100_000);
        level.addFreshEntity(mob);
        return mob;
    }

    private static void pose(Mob m, Stance st) {
        byte stance = st == Stance.CROUCH ? AiPose.CROUCH : st == Stance.STAND ? AiPose.STAND : AiPose.PRONE;
        int t = (int) m.level().getGameTime();
        m.setData(AiPose.Sync.TYPE, new AiPose(stance, (byte) 0, (byte) 0, t, AiPose.HOLD, AiPose.NONE, 0, (short) 0));
        m.setPose(stance == AiPose.PRONE ? Pose.SWIMMING : stance == AiPose.CROUCH ? Pose.CROUCHING : Pose.STANDING);
    }

    // ---------------------------------------------------------------- one blast

    private static void reset(Mob m, Vec3 home) {
        m.snapTo(home.x, home.y, home.z, m.getYRot(), 0F);
        m.setDeltaMovement(Vec3.ZERO);
        m.setHealth(m.getMaxHealth());
        m.invulnerableTime = 0;
        m.hurtTime = 0;
    }

    private static void blast(String mob, Stance st, String kind, int d, boolean wall, String armour, Consumer<Vec3> boom) {
        ServerLevel level = level();
        Mob m = one;
        reset(m, A);
        var boxes = ModelHits.worldBoxes(m);
        if (boxes.isEmpty()) {
            ClientSmoke.check(false, "взрыв: у сервера нет позы " + mob + " " + st.name);
            return;
        }
        // the body's axis on the ground: the head's way from the feet (lying), the mob's facing (standing)
        Vec3 face = new Vec3(0, 0, -1), axis = face;
        double headEnd = 0, feetEnd = 0;
        if (st == Stance.PRONE_HEAD || st == Stance.PRONE_FEET) {
            Vec3 head = BotStandHits.centre(boxes, "root/head");
            if (head == null) head = BotStandHits.centre(boxes, boxes.getFirst().part());
            axis = new Vec3(head.x - A.x, 0, head.z - A.z).normalize();
            headEnd = Double.NEGATIVE_INFINITY;
            feetEnd = Double.POSITIVE_INFINITY;
            for (ModelBox b : boxes) for (int i = 0; i < 8; i++) {
                Vec3 c = ModelContours.point(b, i & 1, (i >> 1) & 1, (i >> 2) & 1);
                double s = (c.x - A.x) * axis.x + (c.z - A.z) * axis.z;
                headEnd = Math.max(headEnd, s);
                feetEnd = Math.min(feetEnd, s);
            }
        }
        double along = switch (st) {
            case STAND, CROUCH -> d;
            case PRONE_HEAD -> headEnd + d;
            case PRONE_FEET -> feetEnd - d;
        };
        Vec3 g = new Vec3(A.x + axis.x * along, O.getY() + GROUND, A.z + axis.z * along);
        List<BlockPos> walls = new ArrayList<>();
        if (wall) {
            // a row of stone one high halfway between the grenade and the near end of the mob, across the axis
            double near = st == Stance.STAND ? 0.3 : headEnd;
            Vec3 mid = new Vec3(A.x + axis.x * (near + along) / 2, O.getY(), A.z + axis.z * (near + along) / 2);
            Vec3 across = new Vec3(-axis.z, 0, axis.x);
            for (int k = -3; k <= 3; k++) {
                BlockPos p = BlockPos.containing(mid.x + across.x * k, mid.y, mid.z + across.z * k);
                if (!walls.contains(p)) walls.add(p);
            }
            for (BlockPos p : walls) level.setBlock(p, Blocks.STONE.defaultBlockState(), 3);
        }
        try {
            float[] taken = new float[2];
            List<BlastParts.Counted> parts = List.of();
            for (int mode = 0; mode < 2; mode++) {
                reset(m, A);
                BlastParts.stand = mode == 1;
                BlastParts.standParts = List.of();
                boom.accept(g);
                taken[mode] = m.getMaxHealth() - m.getHealth();
                if (mode == 1) parts = BlastParts.standParts;
            }
            double sum = 0, weighted = 0;
            for (var p : parts) {
                sum += p.share() * p.raw();
                weighted += p.share() * p.raw() * p.multiplier();
            }
            String where = (wall ? "за стенкой " : "") + d;
            String head = String.format(Locale.ROOT, "взрыв\t%s\t%s\t%s\t%s\t%s\t%.2f\t%.2f\t%.2f\t%.3f", mob, st.name, kind, where,
                    armour.isEmpty() ? "нет" : armour, taken[0], taken[1], sum, sum > 0 ? weighted / sum : 1);
            ROWS.add(head + "\t\t\t\t\t\t");
            for (var p : parts)
                ROWS.add(String.format(Locale.ROOT, "часть\t%s\t%s\t%s\t%s\t\t\t\t\t\t%s\t%.2f\t%.3f\t%.3f\t%.2f\t%.2f", mob, st.name, kind, where,
                        p.part(), p.multiplier(), p.share(), p.scaled(), p.seen(), p.raw()));
            ClientSmoke.check(true, String.format(Locale.ROOT, "%s %s, %s %s, броня %s: NTM %.2f, по частям %.2f (сумма частей %.2f, множитель %.3f)",
                    mob, st.name, kind, where, armour.isEmpty() ? "нет" : armour, taken[0], taken[1], sum, sum > 0 ? weighted / sum : 1));
        } finally {
            BlastParts.stand = null;
            for (BlockPos p : walls) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            reset(m, A);
        }
    }

    /** NTM's hand grenade's HE filling (ItemGrenadeFilling.standardExplode(grenade, 7.5, 25, 10, 0.1)), thrown by no one */
    private static void grenade(Vec3 g) {
        var v = new ExplosionVNT(level(), g.x, g.y, g.z, 7.5F);
        v.setEntityProcessor(new EntityProcessorCrossSmooth(1, 25F).setupPiercing(10F, 0.1F));
        v.setPlayerProcessor(new PlayerProcessorStandard());
        v.explode();
    }

    /** NTM's dynamite charge's blast (BlockChargeDynamite: size 4, EntityProcessorStandard), no blocks broken */
    private static void dynamite(Vec3 g) {
        var v = new ExplosionVNT(level(), g.x, g.y, g.z, 4F);
        v.setEntityProcessor(new EntityProcessorStandard());
        v.setPlayerProcessor(new PlayerProcessorStandard());
        v.explode();
    }

    // ---------------------------------------------------------------- the cost

    private static void clearCrowd() {
        if (one != null) {
            one.discard();
            one = null;
        }
        for (Mob m : crowd) m.discard();
        crowd.clear();
        homes.clear();
    }

    /** {@code n} agents on rings 1.5 to 3.5 blocks around the blast's point */
    private static void ring(int n) {
        clearCrowd();
        ServerLevel level = level();
        Random r = new Random(n);
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n + r.nextDouble() * 0.3, rad = 1.5 + 2.0 * r.nextDouble();
            Vec3 h = new Vec3(A.x + Math.cos(a) * rad, A.y, A.z + Math.sin(a) * rad);
            Mob m = spawn(level, FBI, h, (float) Math.toDegrees(a) + 90F);
            crowd.add(m);
            homes.add(h);
        }
        look(level, A);
    }

    private static int posed() {
        int k = 0;
        for (Mob m : crowd) if (ModelHits.hasPose(m)) k++;
        return k;
    }

    private static void cost(int n) {
        Vec3 g = new Vec3(A.x, O.getY() + GROUND, A.z);
        int poses = posed();
        double[] mean = new double[2], p95 = new double[2];
        for (int mode = 0; mode < 2; mode++) {
            BlastParts.stand = mode == 1;
            BlastParts.standQuiet = true;
            long[] t = new long[40];
            try {
                for (int i = 0; i < t.length; i++) {
                    for (int j = 0; j < crowd.size(); j++) reset(crowd.get(j), homes.get(j));
                    long s = System.nanoTime();
                    grenade(g);
                    t[i] = System.nanoTime() - s;
                }
            } finally {
                BlastParts.stand = null;
                BlastParts.standQuiet = false;
            }
            // the first ten warm up
            long[] w = Arrays.copyOfRange(t, 10, t.length);
            Arrays.sort(w);
            mean[mode] = Arrays.stream(w).average().orElse(0) / 1e6;
            p95[mode] = w[(int) ((w.length - 1) * .95)] / 1e6;
        }
        for (int j = 0; j < crowd.size(); j++) reset(crowd.get(j), homes.get(j));
        ROWS.add(String.format(Locale.ROOT, "нагрузка\t%d агентов (с позой %d)\tграната HE\tNTM %.3f мс (p95 %.3f)\tпо частям %.3f мс (p95 %.3f)", n, poses,
                mean[0], p95[0], mean[1], p95[1]));
        ClientSmoke.check(true, String.format(Locale.ROOT, "нагрузка, %d агентов (с позой %d): взрыв NTM %.3f мс, по частям %.3f мс", n, poses, mean[0],
                mean[1]));
        write();
    }

    /** {@code n} agents 1.5 blocks apart in a square around the blasts' point */
    private static void grid(int n) {
        clearCrowd();
        ServerLevel level = level();
        int side = (int) Math.ceil(Math.sqrt(n));
        for (int i = 0; i < n; i++) {
            Vec3 h = new Vec3(A.x + (i % side - (side - 1) / 2.0) * 1.5, A.y, A.z + (i / side - (side - 1) / 2.0) * 1.5);
            crowd.add(spawn(level, FBI, h, 180F));
            homes.add(h);
        }
        look(level, A);
    }

    private static long mkUntil, mkNanos;
    private static int mkMode, mkBlasts;
    private static final double[] MK_MEAN = new double[2], MK_TICK = new double[2];
    private static final Random MK_RANDOM = new Random(108);

    /** MK108's 40 mm HE shells, one a tick for 10 s into the crowd stepping to and fro, NTM's then by the parts */
    private static void mk108() {
        mkMode = 0;
        startMk();
    }

    private static void startMk() {
        mkUntil = level().getGameTime() + 200;
        mkNanos = 0;
        mkBlasts = 0;
        MK_RANDOM.setSeed(108);
        PLAN.add(at, new Step(1, BlastStand::mkTick));
    }

    private static void mkTick() {
        ServerLevel level = level();
        BlastParts.stand = mkMode == 1;
        BlastParts.standQuiet = true;
        try {
            // the crowd steps 0.05 block each tick, as a moving one would: the server's pose of each is made anew
            double step = (level.getGameTime() & 1) == 0 ? 0 : 0.05;
            for (int j = 0; j < crowd.size(); j++) reset(crowd.get(j), homes.get(j).add(step, 0, 0));
            Vec3 h = homes.get(MK_RANDOM.nextInt(homes.size()));
            Vec3 g = new Vec3(h.x + MK_RANDOM.nextDouble() - 0.5, O.getY() + GROUND, h.z + MK_RANDOM.nextDouble() - 0.5);
            long s = System.nanoTime();
            shell(level, g);
            mkNanos += System.nanoTime() - s;
            mkBlasts++;
        } finally {
            BlastParts.stand = null;
            BlastParts.standQuiet = false;
        }
        if (level.getGameTime() < mkUntil) {
            PLAN.add(at, new Step(1, BlastStand::mkTick));
            return;
        }
        MK_MEAN[mkMode] = mkNanos / 1e6 / mkBlasts;
        MK_TICK[mkMode] = level.getServer().getAverageTickTimeNanos() / 1e6;
        ROWS.add(String.format(Locale.ROOT, "MK108\t%d агентов (с позой %d)\t%s\t%d выстрелов по одному на тик\tвзрыв %.3f мс\tтик сервера без задач стенда %.3f мс",
                crowd.size(), posed(), mkMode == 0 ? "NTM" : "по частям", mkBlasts, MK_MEAN[mkMode], MK_TICK[mkMode]));
        ClientSmoke.check(true, String.format(Locale.ROOT, "MK108, %d агентов, %s: взрыв %.3f мс, тик сервера %.3f мс", crowd.size(),
                mkMode == 0 ? "NTM" : "по частям", MK_MEAN[mkMode], MK_TICK[mkMode]));
        write();
        if (++mkMode < 2) startMk();
    }

    private static BulletConfig mkConfig;
    private static float mkDamage;

    /** one MK108 HE shell hitting the ground at {@code g}: its own impact (Lego.standardExplode) */
    private static void shell(ServerLevel level, Vec3 g) {
        if (mkConfig == null) {
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.fromNamespaceAndPath("hbm", "gun_mk108")));
            ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
            var receiver = gun.getConfig(stack, 0).getReceivers(stack)[0];
            mkConfig = (BulletConfig) receiver.getMagazine(stack).getType(stack, null);
            mkDamage = receiver.getBaseDamage(stack) * mkConfig.damageMult;
            ROWS.add(String.format(Locale.ROOT, "# MK108: патрон %s, урон %.1f", mkConfig.id, mkDamage));
        }
        var bullet = new EntityBulletBaseMK4(level, mkConfig, mkDamage, 0, 0, 0);
        bullet.damage = mkDamage;
        bullet.setPos(g.x, g.y + 0.5, g.z);
        try {
            mkConfig.onImpact.accept(bullet, new BlockHitResult(g, Direction.UP, BlockPos.containing(g.x, O.getY() - 1, g.z), false));
        } finally {
            bullet.discard();
        }
    }

    private static void write() {
        try {
            Path f = Path.of(System.getProperty("ntmgunfeel.blastStandFile"));
            Files.createDirectories(f.getParent());
            Files.write(f, ROWS);
        } catch (Exception e) {
            ClientSmoke.check(false, "запись: " + e);
        }
    }
}
