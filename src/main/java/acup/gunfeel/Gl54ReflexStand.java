package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.BooleanSupplier;

import org.joml.Matrix3f;
import org.joml.Vector3f;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.gl54.Gl54Ballistics;
import acup.gunfeel.gl54.Gl54Grenade;
import acup.gunfeel.gl54.Gl54Items;
import acup.gunfeel.gl54.Gl54Laser;
import acup.gunfeel.gl54.Gl54LaserWorld;
import acup.gunfeel.gl54.Gl54Range;
import acup.gunfeel.gl54.ItemRenderGl54;
import acup.gunfeel.gl54.ReflexDraw;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

// runCheckClient -Pgl54ReflexStand=<dir> -PstandSize=1920x1080: the GL-54's reflex sight and laser in the game, in numbers
// (the frames, with the GUI hidden, go to <dir> for the Python scripts to count; the pictures are not looked at):
//  - the aim: where the sight's dot is on the screen aimed, at five turns of the view (the middle is the goal), where the
//    gun is at the hip;
//  - the second key: outside the sight it measures nothing, in it the wall 40 blocks off, the number on the gun;
//  - the hints: the mark below the dot against the holdover angle, the digits, none at the hip;
//  - the fuse: a grenade fired with a stored range bursts at it (hip), with none it bursts on the wall;
//  - the flight against Gl54Ballistics, the laser beam.
// -Pgl54ReflexSolve: the aim pose is solved from the probe's points (the result is printed to put in ItemRenderGl54).
final class Gl54ReflexStand {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.gl54ReflexStand");
    private static final Path DIR = Path.of(System.getProperty("ntmgunfeel.gl54ReflexStandDir", "../gl54-reflex-stand"));
    private static final boolean SOLVE = Boolean.getBoolean("ntmgunfeel.gl54ReflexSolve");
    private static final BlockPos O = new BlockPos(8, 100, 8);
    // the arena: 6 blocks to each side and behind, a wall this far in front (blocks from the player's block)
    private static final int HALF = 6, WALL = 40;
    private static final float[][] POSES = {{0, 0}, {135, 0}, {-135, 0}, {0, 30}, {0, -30}};
    // the eye relief the solver aims for: model units between the eye and the dot, along the view
    private static final double RELIEF = Double.parseDouble(System.getProperty("ntmgunfeel.gl54ReflexRelief", "0.8"));

    private static final Deque<BooleanSupplier> TODO = new ArrayDeque<>();
    private static final List<String> OUT = new ArrayList<>();
    private static final List<Object[]> BLASTS = new ArrayList<>();
    private static boolean started;
    private static int wait;
    private static volatile int pending;
    private static volatile float[] view;

    private Gl54ReflexStand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        Gl54Probe.ON = true;
        ReflexDraw.PROBE = true;
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            for (int x = -HALF; x <= HALF; x++) for (int z = -HALF; z <= WALL; z++) {
                level.setBlock(O.offset(x, -1, z), Blocks.SNOW_BLOCK.defaultBlockState(), 3);
                for (int y = 0; y <= 14; y++) {
                    boolean wall = Math.abs(x) == HALF || z == -HALF || z == WALL;
                    level.setBlock(O.offset(x, y, z), wall ? Blocks.SNOW_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
                }
            }
            Gl54Grenade.watch = o -> {
                synchronized (BLASTS) {
                    BLASTS.add(o);
                }
            };
        }).join();
        try {
            Files.createDirectories(DIR);
        } catch (java.io.IOException e) {
            ClientSmoke.check(false, "gl54 reflex: папка " + e);
        }
    }

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        float[] v = view;
        if (v != null && mc.player != null) {
            mc.player.setYRot(v[0]);
            mc.player.yRotO = v[0];
            mc.player.setXRot(v[1]);
            mc.player.xRotO = v[1];
        }
        if (!started) {
            started = true;
            plan(mc);
        }
        if (wait > 0) {
            wait--;
            return;
        }
        var next = TODO.peek();
        try {
            if (next != null && next.getAsBoolean()) TODO.poll();
        } catch (Throwable t) {
            GunFeel.LOGGER.error("[gl54reflex] failed", t);
            ClientSmoke.check(false, "gl54 reflex: " + t);
            TODO.clear();
            TODO.add(Gl54ReflexStand::done);
        }
    }

    private static void sleep(int frames) {
        TODO.add(() -> {
            wait = frames;
            return true;
        });
    }

    private static void run(Runnable r) {
        TODO.add(() -> {
            r.run();
            return true;
        });
    }

    private static void line(Object... kv) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < kv.length; i++) {
            if (i > 0) b.append('\t');
            Object o = kv[i];
            b.append(o instanceof Double d ? String.format(Locale.ROOT, "%.4f", d) : o instanceof Float f ? String.format(Locale.ROOT, "%.4f", f) : String.valueOf(o));
        }
        OUT.add(b.toString());
        GunFeel.LOGGER.info("[gl54reflex] {}", b);
    }

    // ---------------------------------------------------------------- the plan

    private static void plan(Minecraft mc) {
        run(() -> {
            noon(mc);
            Config.cornerHud = false;
            Config.arms = false;
        });
        sleep(200);
        run(() -> {
            give(mc, false);
            turn(mc, 0, 0);
        });
        sleep(120);
        // the same view with an empty hand: what the gun covers is what differs from this
        run(() -> shot("nogun.png"));
        TODO.add(() -> pending == 0);
        run(() -> give(mc, true));
        TODO.add(() -> mc.player.getMainHandItem().getItem() == Gl54Items.GUN.get());
        sleep(200);

        // the key outside the sight measures nothing
        run(() -> {
            key(EnumKeybind.GUN_SECONDARY);
            float stored = onServer(() -> Gl54Range.get(player().getMainHandItem()));
            line("key outside the sight", "stored", stored);
            ClientSmoke.check(Float.isNaN(stored), "gl54 reflex: клавиша вне прицела что-то измерила: " + stored);
        });

        // the hip: no hints, only the dot; the gun's box on the screen
        sleep(40);
        run(() -> {
            probeLines("hip", false);
            ClientSmoke.check(ReflexDraw.POINTS.containsKey("dot") && !ReflexDraw.POINTS.containsKey("mark"), "gl54 reflex: от бедра нет только точки");
        });
        run(() -> shot("hip.png"));
        TODO.add(() -> pending == 0);

        // aimed, settled
        run(() -> aim(mc, true));
        sleep(90);
        if (SOLVE) {
            for (int i = 0; i < 4; i++) {
                run(() -> solve());
                sleep(12);
            }
        }
        run(() -> line("aim pose", "aimX", ItemRenderGl54.aimX, "aimY", ItemRenderGl54.aimY, "aimZ", ItemRenderGl54.aimZ, "progress", ItemGunBaseNT.aimingProgress));

        // five turns of the view, aimed, nothing measured yet (digits "---")
        for (float[] pose : POSES) {
            String tag = "aimed-" + (int) pose[0] + "-" + (int) pose[1];
            run(() -> turn(mc, pose[0], pose[1]));
            sleep(40);
            run(() -> probeLines(tag, true));
            run(() -> shot(tag + ".png"));
            TODO.add(() -> pending == 0);
        }
        run(() -> turn(mc, 0, 0));
        sleep(30);
        // the arms on, for the one look at what the hands cover
        run(() -> Config.arms = true);
        sleep(20);
        run(() -> shot("aimed-arms.png"));
        TODO.add(() -> pending == 0);
        run(() -> Config.arms = false);

        // the measurement: the wall
        run(() -> {
            key(EnumKeybind.GUN_SECONDARY);
            float stored = onServer(() -> Gl54Range.get(player().getMainHandItem()));
            // the eye at the middle of the player's block, the wall's near face WALL blocks from the block's near face
            double expected = O.getZ() + WALL - (O.getZ() + 0.5);
            line("measured", "stored", stored, "expected", expected);
            ClientSmoke.check(Math.abs(stored - expected) <= Gl54Range.NOISE + 1e-4, "gl54 reflex: замер (с шумом до полублока) " + stored + " вместо " + expected);
        });
        sleep(20);
        for (float[] pose : POSES) {
            String tag = "measured-" + (int) pose[0] + "-" + (int) pose[1];
            run(() -> turn(mc, pose[0], pose[1]));
            sleep(40);
            run(() -> probeLines(tag, true));
            run(() -> shot(tag + ".png"));
            TODO.add(() -> pending == 0);
        }
        run(() -> turn(mc, 0, 0));
        sleep(30);
        // a measurement that hits nothing (up at the sky) clears the number
        run(() -> {
            turn(mc, 0, -89);
        });
        sleep(10);
        run(() -> {
            key(EnumKeybind.GUN_SECONDARY);
            float stored = onServer(() -> Gl54Range.get(player().getMainHandItem()));
            line("measure at the sky", "stored", stored);
            ClientSmoke.check(Float.isNaN(stored), "gl54 reflex: замер в небо оставил " + stored);
        });
        run(() -> turn(mc, 0, 0));
        sleep(10);
        run(() -> key(EnumKeybind.GUN_SECONDARY));
        sleep(10);

        // the hip with the number stored: the dot only
        run(() -> aim(mc, false));
        sleep(90);
        run(() -> {
            probeLines("hip-measured", false);
            ClientSmoke.check(ReflexDraw.POINTS.containsKey("dot") && !ReflexDraw.POINTS.containsKey("mark") && !ReflexDraw.POINTS.containsKey("holdover"),
                    "gl54 reflex: от бедра с замером есть подсказки");
        });
        run(() -> shot("hip-measured.png"));
        TODO.add(() -> pending == 0);

        // the flight against the sums: a shot at the holdover for 20 blocks, no number on the gun
        run(() -> setRange(OptionalDouble.empty()));
        double angle20 = Gl54Ballistics.holdover(20).angle();
        run(() -> {
            BLASTS.clear();
            // aimed: the hip shot has NTM's hip spread
            aim(mc, true);
            turn(mc, 0, (float) -Math.toDegrees(angle20));
        });
        sleep(90);
        run(() -> fire());
        for (int i = 0; i < 120; i++) run(() -> sample());
        sleep(90);
        run(() -> flight("flight-20", angle20));
        // the fuse: a stored range of 25 and a shot raised for the wall, from the hip
        double angle39 = Gl54Ballistics.holdover(WALL - 1.0).angle();
        run(() -> {
            BLASTS.clear();
            setRange(OptionalDouble.of(25.0));
            turn(mc, 0, (float) -Math.toDegrees(angle39));
        });
        sleep(90);
        run(() -> fire());
        sleep(90);
        run(() -> fuse("fuse-hip-25", 25.0));
        // no stored range: the grenade goes on to the wall
        run(() -> {
            BLASTS.clear();
            setRange(OptionalDouble.empty());
        });
        sleep(90);
        run(() -> fire());
        sleep(90);
        run(() -> fuse("fuse-none", Double.NaN));
        // a stored range under the arming distance fuses at 3 blocks
        run(() -> {
            BLASTS.clear();
            setRange(OptionalDouble.of(1.5));
        });
        sleep(90);
        run(() -> fire());
        sleep(90);
        run(() -> fuse("fuse-1.5", 3.0));

        // the burst's shift: measure a wall 20 blocks off (the noise is within half a block, the shift starts at +1 for a block),
        // turn the wheel twice up through the payload, take the wall away, fire: it bursts at the stored range plus the shift
        run(() -> {
            BLASTS.clear();
            wall(20, true);
            aim(mc, true);
            turn(mc, 0, 0);
        });
        sleep(60);
        run(() -> key(EnumKeybind.GUN_SECONDARY));
        sleep(10);
        run(() -> {
            float stored = onServer(() -> Gl54Range.get(player().getMainHandItem()));
            int offset = onServer(() -> Gl54Range.offset(player().getMainHandItem()));
            line("wall 20", "stored", stored, "offset", offset);
            ClientSmoke.check(Math.abs(stored - 19.5) <= 0.5 + 1e-4, "gl54 reflex: замер стены в 20 бл. с шумом: " + stored);
            ClientSmoke.check(offset == 1, "gl54 reflex: сдвиг по блоку не 1: " + offset);
            for (int i = 0; i < 2; i++) net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new acup.gunfeel.gl54.Gl54OffsetPayload(1));
        });
        sleep(30);
        run(() -> {
            int offset = onServer(() -> Gl54Range.offset(player().getMainHandItem()));
            line("wall 20", "offset after the wheel", offset);
            ClientSmoke.check(offset == 3, "gl54 reflex: колесо дважды вверх дало сдвиг " + offset);
            // a third turn is held at the limit
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(new acup.gunfeel.gl54.Gl54OffsetPayload(1));
        });
        sleep(20);
        run(() -> {
            int offset = onServer(() -> Gl54Range.offset(player().getMainHandItem()));
            ClientSmoke.check(offset == 3, "gl54 reflex: сдвиг вышел за предел: " + offset);
            shot("wall20.png");
        });
        TODO.add(() -> pending == 0);
        run(() -> {
            wall(20, false);
            // two turns down from the limit: the flat 3 minus 2 = 1 is checked first by the fire below with the shift at 3
        });
        sleep(90);
        run(() -> fire());
        sleep(90);
        run(() -> {
            float stored = onServer(() -> Gl54Range.get(player().getMainHandItem()));
            int offset = onServer(() -> Gl54Range.offset(player().getMainHandItem()));
            fuse("fuse-offset", stored + offset);
        });

        // the laser: aimed with the beam and without it (the frames are set against each other), in the hand and in the third person
        run(() -> {
            setRange(OptionalDouble.empty());
            aim(mc, true);
            turn(mc, 0, 0);
        });
        sleep(90);
        run(() -> shot("laser-on.png"));
        TODO.add(() -> pending == 0);
        run(() -> Gl54Laser.ON = false);
        sleep(6);
        run(() -> shot("laser-off.png"));
        TODO.add(() -> pending == 0);
        run(() -> {
            Gl54Laser.ON = true;
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        });
        for (float[] pose : new float[][] {{0, 0}, {135, 0}, {-135, 30}, {0, 0, 1}, {135, 0, 1}, {-135, 30, 1}}) {
            String tag = (pose.length > 2 ? "front-" : "third-") + (int) pose[0] + "-" + (int) pose[1];
            run(() -> mc.options.setCameraType(pose.length > 2 ? CameraType.THIRD_PERSON_FRONT : CameraType.THIRD_PERSON_BACK));
            run(() -> turn(mc, pose[0], pose[1]));
            sleep(100);
            run(() -> {
                shot(tag + "-on.png");
                var cam = mc.gameRenderer.mainCamera();
                Vec3 s = Gl54LaserWorld.lastStart;
                if (s != null) {
                    Vec3 d = s.subtract(cam.position());
                    org.joml.Vector3f v = cam.rotation().conjugate(new org.joml.Quaternionf()).transform(new org.joml.Vector3f((float) d.x, (float) d.y, (float) d.z));
                    float f = (mc.getWindow().getHeight() / 2F) / (float) Math.tan(Math.toRadians(cam.getFov() * 0.5));
                    line("laser start", tag, "px", mc.getWindow().getWidth() / 2F + v.x / -v.z * f, mc.getWindow().getHeight() / 2F - v.y / -v.z * f, "view z", v.z);
                    // other forward reaches, for the one that meets the lens
                    double was = Gl54LaserWorld.forward;
                    float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
                    for (double fw : new double[] {0.3, 0.5, 0.7, 0.9, 1.1, 1.3}) {
                        Gl54LaserWorld.forward = fw;
                        Vec3 c = Gl54LaserWorld.line(mc.player, partial)[0].subtract(cam.position());
                        org.joml.Vector3f w = cam.rotation().conjugate(new org.joml.Quaternionf()).transform(new org.joml.Vector3f((float) c.x, (float) c.y, (float) c.z));
                        line("laser candidate", tag, "forward", fw, "px", mc.getWindow().getWidth() / 2F + w.x / -w.z * f, mc.getWindow().getHeight() / 2F - w.y / -w.z * f);
                    }
                    Gl54LaserWorld.forward = was;
                }
            });
            TODO.add(() -> pending == 0);
            run(() -> Gl54Laser.ON = false);
            sleep(6);
            run(() -> shot(tag + "-off.png"));
            TODO.add(() -> pending == 0);
            run(() -> Gl54Laser.ON = true);
        }
        run(() -> {
            Gl54Laser.ON = true;
            aim(mc, false);
        });
        sleep(120);
        run(() -> shot("third-hip.png"));
        TODO.add(() -> pending == 0);
        run(() -> mc.options.setCameraType(CameraType.FIRST_PERSON));
        TODO.add(Gl54ReflexStand::done);
    }

    // ---------------------------------------------------------------- the pieces

    private static float[] pt(String name) {
        return ReflexDraw.POINTS.get(name);
    }

    // The aim pose moved so that the dot is in the middle of the screen at RELIEF model units from the eye: the hand-space
    // translation of the pose moves a point of the model by the pose's turn (the probe's unit axes over their scale).
    private static void solve() {
        float[] o = pt("o"), ex = pt("ex"), ey = pt("ey"), ez = pt("ez"), dot = pt("dot");
        if (o == null || dot == null) {
            line("solve", "no probe");
            return;
        }
        // the pose's matrix: the columns are the model's axes in the view, the scale included; the translation is applied
        // before the 0.5 scale, so it moves the model by the same turn, 1 for 1
        Vector3f cx = new Vector3f(ex[0] - o[0], ex[1] - o[1], ex[2] - o[2]).mul(2F);
        Vector3f cy = new Vector3f(ey[0] - o[0], ey[1] - o[1], ey[2] - o[2]).mul(2F);
        Vector3f cz = new Vector3f(ez[0] - o[0], ez[1] - o[1], ez[2] - o[2]).mul(2F);
        float unit = new Vector3f(ex[0] - o[0], ex[1] - o[1], ex[2] - o[2]).length();
        Matrix3f turn = new Matrix3f(cx, cy, cz).invert();
        Vector3f want = new Vector3f(0F, 0F, (float) (-RELIEF * unit));
        Vector3f delta = turn.transform(want.sub(dot[0], dot[1], dot[2]));
        ItemRenderGl54.aimX += delta.x;
        ItemRenderGl54.aimY += delta.y;
        ItemRenderGl54.aimZ += delta.z;
        line("solve", "unit", unit, "dot view", dot[0], dot[1], dot[2], "delta", delta.x, delta.y, delta.z);
    }

    private static void probeLines(String tag, boolean aimed) {
        Minecraft mc = Minecraft.getInstance();
        float cx = mc.getWindow().getWidth() / 2F, cy = mc.getWindow().getHeight() / 2F;
        for (Map.Entry<String, float[]> e : ReflexDraw.POINTS.entrySet()) {
            float[] v = e.getValue();
            line("probe", tag, e.getKey(), "view", v[0], v[1], v[2], "px", v[3], v[4], "fov", v[5]);
        }
        float[] dot = pt("dot");
        if (dot != null) line("dot", tag, "offset px", dot[3] - cx, dot[4] - cy);
        float[] mark = pt("mark"), exp = pt("expected"), hold = pt("holdover");
        if (mark != null && dot != null && exp != null && hold != null)
            line("mark", tag, "below the dot px", mark[4] - dot[4], "expected px", exp[1], "theta deg", hold[0], "reachable", hold[2]);
        // the gun's box (Gl54Probe: the model's bounding cube)
        float x0 = 1e9F, y0 = 1e9F, x1 = -1e9F, y1 = -1e9F;
        for (int c = 0; c < 8; c++) {
            float[] p = Gl54Probe.POINTS.get("c" + c);
            if (p == null) continue;
            x0 = Math.min(x0, p[3]);
            x1 = Math.max(x1, p[3]);
            y0 = Math.min(y0, p[4]);
            y1 = Math.max(y1, p[4]);
        }
        line("gun box", tag, "x", x0, x1, "y", y0, y1);
    }

    private static void fire() {
        onServer(() -> {
            ServerPlayer p = player();
            ItemStack stack = p.getMainHandItem();
            ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
            gun.handleKeybind(p, p.getInventory(), stack, EnumKeybind.GUN_PRIMARY, true);
            gun.handleKeybind(p, p.getInventory(), stack, EnumKeybind.GUN_PRIMARY, false);
            return null;
        });
    }

    // the grenade that landed: where, against the sums for the angle it was fired at
    private static void flight(String tag, double angle) {
        Object[] b = lastBlast();
        if (b == null) {
            line(tag, "no blast");
            ClientSmoke.check(false, "gl54 reflex: " + tag + ": граната не упала");
            return;
        }
        Vec3 at = (Vec3) b[0], start = (Vec3) b[3];
        double flat = Math.hypot(at.x - start.x, at.z - start.z);
        double below = start.y - at.y;
        double sum = Gl54Ballistics.range(angle, below);
        line(tag, "horizontal", flat, "sum", sum, "fell", below, "start", start.x, start.y, start.z);
        ClientSmoke.check(Math.abs(flat - sum) < 0.8, "gl54 reflex: полёт " + String.format(Locale.ROOT, "%.2f", flat) + " вместо расчётных " + String.format(Locale.ROOT, "%.2f", sum));
    }

    private static void fuse(String tag, double expected) {
        Object[] b = lastBlast();
        if (b == null) {
            line(tag, "no blast");
            ClientSmoke.check(false, "gl54 reflex: " + tag + ": граната не взорвалась");
            return;
        }
        Vec3 at = (Vec3) b[0], start = (Vec3) b[3];
        double flat = Math.hypot(at.x - start.x, at.z - start.z);
        boolean burst = (Boolean) b[2];
        double wall = O.getZ() + WALL - start.z;
        line(tag, "horizontal", flat, "expected", Double.isNaN(expected) ? wall : expected, "burst", burst, "z", at.z);
        double want = Double.isNaN(expected) ? wall : expected;
        ClientSmoke.check(Math.abs(flat - want) < 0.3, "gl54 reflex: " + tag + ": " + String.format(Locale.ROOT, "%.2f", flat) + " вместо " + String.format(Locale.ROOT, "%.2f", want));
    }

    // the grenade in flight, once a tick: where it is (the server's entity)
    private static int lastTick = -1;

    private static void sample() {
        onServer(() -> {
            for (var e : player().level().getEntitiesOfClass(com.hbm.entity.projectile.EntityBulletBaseMK4.class, new net.minecraft.world.phys.AABB(O).inflate(80))) {
                if (e.tickCount == lastTick) continue;
                lastTick = e.tickCount;
                line("path", "tick", e.tickCount, "x", e.getX(), "y", e.getY(), "z", e.getZ(), "motion", e.getDeltaMovement().x, e.getDeltaMovement().y, e.getDeltaMovement().z);
            }
            return null;
        });
    }

    // a wall of snow across the arena `blocks` in front of the player's block, or the air back in its place
    private static void wall(int blocks, boolean on) {
        onServer(() -> {
            ServerLevel level = Minecraft.getInstance().getSingleplayerServer().overworld();
            for (int x = -HALF + 1; x < HALF; x++) for (int y = 0; y <= 14; y++)
                level.setBlock(O.offset(x, y, blocks), on ? Blocks.SNOW_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            return null;
        });
    }

    private static Object[] lastBlast() {
        synchronized (BLASTS) {
            return BLASTS.isEmpty() ? null : BLASTS.get(BLASTS.size() - 1);
        }
    }

    private static <T> T onServer(java.util.function.Supplier<T> f) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        Object[] out = new Object[1];
        server.submit(() -> out[0] = f.get()).join();
        @SuppressWarnings("unchecked")
        T t = (T) out[0];
        return t;
    }

    private static ServerPlayer player() {
        return Minecraft.getInstance().getSingleplayerServer().getPlayerList().getPlayers().getFirst();
    }

    private static void key(EnumKeybind k) {
        onServer(() -> {
            ServerPlayer p = player();
            ItemStack stack = p.getMainHandItem();
            ItemGunBaseNT gun = (ItemGunBaseNT) stack.getItem();
            gun.handleKeybind(p, p.getInventory(), stack, k, true);
            gun.handleKeybind(p, p.getInventory(), stack, k, false);
            return null;
        });
    }

    private static void setRange(OptionalDouble range) {
        onServer(() -> {
            Gl54Range.set(player().getMainHandItem(), range);
            return null;
        });
    }

    private static void turn(Minecraft mc, float yaw, float pitch) {
        view = new float[] {yaw, pitch};
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
            p.teleportTo(server.overworld(), O.getX() + 0.5, O.getY(), O.getZ() + 0.5, Set.of(), yaw, pitch, false);
            p.setDeltaMovement(0, 0, 0);
        }).join();
    }

    private static void noon(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            for (String c : new String[] {"time set noon", "weather clear", "gamerule advance_time false", "gamerule advance_weather false"}) {
                try {
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), c);
                } catch (RuntimeException e) {
                    GunFeel.LOGGER.warn("[gl54reflex] {}: {}", c, e.toString());
                }
            }
        }).join();
    }

    private static void give(Minecraft mc, boolean gun) {
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
            p.setGameMode(GameType.CREATIVE);
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
            p.getInventory().clearContent();
            if (!gun) {
                p.setDeltaMovement(0, 0, 0);
                return;
            }
            ItemStack stack = new ItemStack(Gl54Items.GUN.get());
            stack.set(Gl54Items.GUN_MAG.get(), new acup.gunfeel.gl54.GunMag(java.util.Collections.nCopies(7, "he"), "he", java.util.List.of(), true, false));
            ItemGunBaseNT.setIsDrawn(stack, true);
            ItemGunBaseNT.setIsEquipped(stack, true);
            ItemGunBaseNT.setState(stack, 0, ItemGunBaseNT.GunState.IDLE);
            ItemGunBaseNT.setTimer(stack, 0, 0);
            p.getInventory().setSelectedSlot(0);
            p.setItemInHand(InteractionHand.MAIN_HAND, stack);
            p.setDeltaMovement(0, 0, 0);
        }).join();
    }

    private static void aim(Minecraft mc, boolean on) {
        var server = mc.getSingleplayerServer();
        server.submit(() -> ItemGunBaseNT.setIsAiming(server.getPlayerList().getPlayers().getFirst().getMainHandItem(), on)).join();
    }

    private static boolean done() {
        if (pending != 0) return false;
        try {
            Files.write(DIR.resolve("stand.tsv"), OUT);
        } catch (java.io.IOException e) {
            ClientSmoke.check(false, "gl54 reflex: " + e);
        }
        ClientSmoke.check(true, "gl54 reflex: кадры и числа в " + DIR);
        ClientSmoke.finish();
        return true;
    }

    private static void shot(String name) {
        var mc = Minecraft.getInstance();
        pending++;
        net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(DIR.resolve(name));
                OUT.add("#file\t" + name + "\t" + image.getWidth() + "x" + image.getHeight());
            } catch (java.io.IOException e) {
                GunFeel.LOGGER.warn("[gl54reflex] кадр не записан", e);
            } finally {
                pending--;
            }
        });
    }
}
