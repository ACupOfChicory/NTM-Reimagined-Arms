package acup.gunfeel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.hbm.blocks.ModBlocks;
import com.hbm.blocks.bomb.BlockLandmineFat;
import com.hbm.client.particle.VisualParticle;
import com.hbm.entity.ModEntities;
import com.hbm.entity.effect.EntityNukeTorex;
import com.hbm.entity.grenade.EntityGrenadeUniversal;
import com.hbm.entity.item.EntityTntNtm;
import com.hbm.entity.projectile.EntityArtilleryShell;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.entity.projectile.EntityChemical;
import com.hbm.items.weapon.ItemAmmoArty;
import com.hbm.items.weapon.grenade.ItemGrenadeFilling.EnumGrenadeFilling;
import com.hbm.items.weapon.grenade.ItemGrenadeFuze.EnumGrenadeFuze;
import com.hbm.items.weapon.grenade.ItemGrenadeShell.EnumGrenadeShell;
import com.hbm.items.weapon.grenade.ItemGrenadeUniversal;
import com.hbm.items.weapon.sedna.factory.XFactory556mm;
import com.hbm.items.weapon.sedna.factory.XFactoryFlamer;
import com.hbm.particle.helper.CasingCreator;

import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeNet;
import acup.gunfeel.smoke.client.SmokeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

// runCheckClient -PparticleStand=<dir>: what particles cost a frame, source by source, through NTM's (and the mod's)
// own code on a flat field at clear noon, the HUD hidden, vsync and the frame limit off. Scenes: an NTM hand grenade
// (frag shell, HE filling) once and five at once; the white phosphorus grenade once and three (the mod's smoke on, as
// it ships); NTM's flamethrower (diesel) and chemical thrower (chlorine) for 10 s each from an armour stand, the camera
// at the thrower; four NTM 5.56 rifles at a stone wall for 10 s, their casings thrown as a gun does (CasingCreator), the
// mod's tracers and hit effects on; NTM's block of C4 (as semtex: a puff and a smoke a block it reached), its C4 charge
// and an HE artillery shell from 30 blocks; the Fat Mine from 40.
// The flamethrower once more at the thrower's own eye (flamerfp: the camera a player's, the flames out of the gun's
// muzzle as NTM's flamethrower has it, the thrower not drawn).
// Each scene twice a round: N as the game has it, X with every particle refused (ParticleEngine.createParticle and add
// cancelled, NTM's Flywheel visuals of them taken down): the frame without them. A scene with a trial a third time, T:
// N with a change the game does not have, to see what it would give (wp1, wp3 "notrail": the smoke the phosphorus'
// lava sparks let out as they tick refused; flamerfp "near": NTM's flames smaller near the camera, none within a
// block). Rounds alternate N first and X first. In the first round N and T are shot (<scene>-<mode>-<t>s.png). In N and
// T the particles' drawing is switched off every other 32 frames (ParticleEngine.extract cancelled; NTM's Flywheel visuals are
// still drawn), so drawn and hidden frames share the machine's load. Timers (the MixinParticleStand* mixins): the
// particle tick (all and by group), gathering them (extract, by group), handing them over (submit, by group), the quads'
// vertices (QuadParticleFeatureRenderer.prepareGroup) and their draw (executeGroup), Flywheel's frame and drawing.
// Census: every particle added, by class and by whose code made it (the first frame outside the game's, one in 8).
// Windows: before (−1.2…0 s), a burst's 0–3 s and 3–12 s, a stream's 2–10 s and 10–15 s.
// <dir>/runs.tsv (a row a window), groups.tsv, census.tsv, live.tsv (alive by class at a moment).
// -PparticleScenes=he1,wp3 only those, -PparticleRounds=1 once.
public final class ParticleStand {
    static final String DIR = System.getProperty("ntmgunfeel.particleStand", "");
    public static final boolean ENABLED = !DIR.isBlank();
    private static final int Y = 100;
    private static final double GX = 0.5, GZ = 24.5;
    private static final double PRE = 1.5;
    private static final int STREAM_TICKS = 200;
    private static final int ROUNDS = Integer.getInteger("ntmgunfeel.particleRounds", 3);
    private static final Set<String> ONLY = Set.copyOf(List.of(System.getProperty("ntmgunfeel.particleScenes", "").split(",")));

    private enum What { HE, WP, FLAMER, CHEM, GUNFIRE, C4_BLOCK, C4_CHARGE, ARTY, NUKE }

    private static final String NOTRAIL = "notrail", NEAR = "near";

    // count bursts (a row 4 or 8 blocks apart) or a stream of count throwers; the camera dist blocks short of the spot
    // and up blocks up (a stream: behind the thrower, fp at its eye); trial: the T run's change, or none
    private record Scene(String name, What what, int count, double dist, double up, boolean fp, String trial) {
        Scene(String name, What what, int count, double dist, double up) {
            this(name, what, count, dist, up, false, "");
        }

        boolean stream() {
            return what == What.FLAMER || what == What.CHEM || what == What.GUNFIRE;
        }

        double end() {
            return stream() ? 15 : 12;
        }
    }

    private static final List<Scene> SCENES = java.util.stream.Stream.of(new Scene("he1", What.HE, 1, 15, 2.5),
            new Scene("he5", What.HE, 5, 15, 2.5), new Scene("wp1", What.WP, 1, 15, 2.5, false, NOTRAIL),
            new Scene("wp3", What.WP, 3, 15, 2.5, false, NOTRAIL), new Scene("flamer", What.FLAMER, 1, 0, 0),
            new Scene("flamerfp", What.FLAMER, 1, 0, 0, true, NEAR), new Scene("chem", What.CHEM, 1, 0, 0), new Scene("gunfire", What.GUNFIRE, 4, 0, 0),
            new Scene("c4block", What.C4_BLOCK, 1, 30, 4), new Scene("c4charge", What.C4_CHARGE, 1, 30, 4), new Scene("arty", What.ARTY, 1, 30, 4),
            new Scene("nuke", What.NUKE, 1, 40, 6))
            .filter(s -> ONLY.contains("") || ONLY.contains(s.name)).toList();

    // N the game's, X no particle, T N with the scene's trial
    private enum Mode { N, X, T }

    private record Run(Scene scene, Mode mode, int round) {
        String label() {
            return scene.name + "-" + mode + "-" + round;
        }

        boolean refused() {
            return mode == Mode.X;
        }

        String trial() {
            return mode == Mode.T ? scene.trial : "";
        }
    }

    // ---------------------------------------------------------------- what the mixins feed (client thread)

    /** Every other 32 frames of an N run: ParticleEngine.extract skipped. */
    public static boolean hide;
    /** An X run: every particle refused. */
    public static boolean refuse;
    // a T run's trial ("" none); in the particle tick
    private static String trial = "";
    private static boolean ticking;

    // a window's sums
    private static final class Acc {
        final String name;
        final double from, to;
        // a frame's wall time, and the render thread's CPU time in it
        final List<Long> drawn = new ArrayList<>(), hidden = new ArrayList<>(), drawnCpu = new ArrayList<>(), hiddenCpu = new ArrayList<>();
        long tickNs, extractNs, submitNs, prepareNs, executeNs, fwFrameNs, fwRenderNs;
        int ticks, extracts, fwFrames;
        // the GPU's time (GL_TIMESTAMP pairs) of the quad particles' draw and of Flywheel's, in drawn and in hidden frames
        long gpuQuadsDrawn, gpuFwDrawn, gpuFwHidden;
        int gpuDrawn, gpuHidden;

        void gpu(boolean hid, long quads, long fw) {
            if (hid) {
                gpuFwHidden += fw;
                gpuHidden++;
            } else {
                gpuQuadsDrawn += quads;
                gpuFwDrawn += fw;
                gpuDrawn++;
            }
        }
        long aliveSum;
        int aliveSamples, aliveMax;
        long spawned, refused, trimmed;
        // group -> {tick ns, ticks, extract ns, extracts, submit ns, submits, alive sum, alive samples}
        final Map<String, long[]> groups = new TreeMap<>();

        Acc(String name, double from, double to) {
            this.name = name;
            this.from = from;
            this.to = to;
        }

        long[] group(String g) {
            return groups.computeIfAbsent(g, k -> new long[8]);
        }
    }

    private static Acc cur;
    // a frame's group render states to their groups' names (submit runs after extract)
    private static final Map<Object, String> STATES = new IdentityHashMap<>();
    // a run's census: particle class -> {added, by NTM's code, by the mod's, by the game's, by another mod's} (the
    // makers one in 8)
    private static final Map<String, long[]> CENSUS = new TreeMap<>();
    private static long adds;
    private static final StackWalker WALKER = StackWalker.getInstance();

    /** ParticleEngine.tick's start. */
    public static void tickBegin() {
        ticking = true;
    }

    public static void ticked(long ns, Map<?, ? extends ParticleGroup<?>> groups) {
        ticking = false;
        if (trial.equals(NEAR)) near(groups);
        Acc a = cur;
        if (a == null) return;
        a.tickNs += ns;
        a.ticks++;
        int all = 0;
        for (ParticleGroup<?> g : groups.values()) {
            int n = g.size();
            all += n;
            long[] s = a.group(name(g));
            s[6] += n;
            s[7]++;
        }
        a.aliveSum += all;
        a.aliveSamples++;
        a.aliveMax = Math.max(a.aliveMax, all);
    }

    public static void groupTicked(Object group, long ns) {
        Acc a = cur;
        if (a == null) return;
        long[] s = a.group(name(group));
        s[0] += ns;
        s[1]++;
    }

    public static void extracted(long ns) {
        Acc a = cur;
        if (a == null) return;
        a.extractNs += ns;
        a.extracts++;
    }

    public static void groupExtracted(Object group, Object state, long ns) {
        String n = name(group);
        STATES.put(state, n);
        Acc a = cur;
        if (a == null) return;
        long[] s = a.group(n);
        s[2] += ns;
        s[3]++;
    }

    public static void submitted(Object state, long ns) {
        String n = STATES.getOrDefault(state, name(state));
        Acc a = cur;
        if (a == null) return;
        a.submitNs += ns;
        long[] s = a.group(n);
        s[4] += ns;
        s[5]++;
    }

    public static void prepared(long ns) {
        Acc a = cur;
        if (a != null) a.prepareNs += ns;
    }

    public static void executed(long ns) {
        Acc a = cur;
        if (a != null) a.executeNs += ns;
    }

    public static void flywheelFrame(long ns) {
        Acc a = cur;
        if (a == null) return;
        a.fwFrameNs += ns;
        a.fwFrames++;
    }

    public static void flywheelRender(long ns) {
        Acc a = cur;
        if (a != null) a.fwRenderNs += ns;
    }

    // ---------------------------------------------------------------- the near trial

    /** near: NTM's flames full size from 2.5 blocks off the camera, none within 1 (their quad size, which NTM's visual reads). */
    private static final double NEAR_NONE = 1.0, NEAR_FULL = 2.5;
    private static final Map<Particle, Float> BASE_SIZE = new IdentityHashMap<>();
    private static Field quadSize;

    private static void near(Map<?, ? extends ParticleGroup<?>> groups) {
        Vec3 cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
        try {
            if (quadSize == null) {
                quadSize = net.minecraft.client.particle.SingleQuadParticle.class.getDeclaredField("quadSize");
                quadSize.setAccessible(true);
            }
            BASE_SIZE.keySet().removeIf(p -> !p.isAlive());
            for (ParticleGroup<?> g : groups.values()) for (Particle p : queue(g)) {
                if (!(p instanceof com.hbm.client.particle.ParticleFlamethrower f) || !p.isAlive()) continue;
                Float base = BASE_SIZE.get(p);
                if (base == null) BASE_SIZE.put(p, base = quadSize.getFloat(f));
                double d = cam.distanceTo(p.getBoundingBox().getCenter());
                double k = Math.clamp((d - NEAR_NONE) / (NEAR_FULL - NEAR_NONE), 0, 1);
                quadSize.setFloat(f, (float) (base * k));
            }
        } catch (Exception e) {
            ClientSmoke.check(false, "частицы: near " + e);
            trial = "";
        }
    }

    // ---------------------------------------------------------------- the GPU's time (OpenGL only), read back frames later

    /** Parts timed on the GPU: the quad particles' draw (executeGroup), Flywheel's (render, renderOit). */
    public static final int GPU_QUADS = 0, GPU_FLYWHEEL = 1;
    private static boolean gpu;
    private static final java.util.ArrayDeque<Integer> POOL = new java.util.ArrayDeque<>();
    private static final int[] OPEN = {-1, -1};
    private static final List<int[]> FRAME_QUERIES = new ArrayList<>();

    private record Pending(Acc acc, boolean hidden, List<int[]> queries) {
    }

    private static final java.util.ArrayDeque<Pending> PENDING = new java.util.ArrayDeque<>();

    public static void gpuBegin(int part) {
        if (!gpu || OPEN[part] >= 0) return;
        int id = query();
        org.lwjgl.opengl.GL33.glQueryCounter(id, org.lwjgl.opengl.GL33.GL_TIMESTAMP);
        OPEN[part] = id;
    }

    public static void gpuEnd(int part) {
        if (!gpu || OPEN[part] < 0) return;
        int id = query();
        org.lwjgl.opengl.GL33.glQueryCounter(id, org.lwjgl.opengl.GL33.GL_TIMESTAMP);
        FRAME_QUERIES.add(new int[] {part, OPEN[part], id});
        OPEN[part] = -1;
    }

    private static int query() {
        Integer id = POOL.poll();
        return id != null ? id : org.lwjgl.opengl.GL15.glGenQueries();
    }

    // the last frame's queries to wait, the frames whose results are in summed into their windows (8 frames behind at most)
    private static void gpuFrame(Acc a, boolean hid) {
        if (!gpu) return;
        PENDING.add(new Pending(a, hid, new ArrayList<>(FRAME_QUERIES)));
        FRAME_QUERIES.clear();
        while (!PENDING.isEmpty()) {
            Pending p = PENDING.peek();
            boolean ready = true;
            for (int[] q : p.queries)
                if (org.lwjgl.opengl.GL15.glGetQueryObjecti(q[2], org.lwjgl.opengl.GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                    ready = false;
                    break;
                }
            if (!ready && PENDING.size() < 8) break;
            PENDING.poll();
            long quads = 0, fw = 0;
            for (int[] q : p.queries) {
                long d = org.lwjgl.opengl.GL33.glGetQueryObjecti64(q[2], org.lwjgl.opengl.GL15.GL_QUERY_RESULT)
                        - org.lwjgl.opengl.GL33.glGetQueryObjecti64(q[1], org.lwjgl.opengl.GL15.GL_QUERY_RESULT);
                if (q[0] == GPU_QUADS) quads += d;
                else fw += d;
                POOL.add(q[1]);
                POOL.add(q[2]);
            }
            if (p.acc != null) p.acc.gpu(p.hidden, quads, fw);
        }
    }

    /** ParticleEngine.createParticle: true to refuse it (an X run; a notrail T run: the smoke a particle lets out as it ticks). */
    public static boolean refused(ParticleOptions options) {
        if (ticking && trial.equals(NOTRAIL) && options.getType() == net.minecraft.core.particles.ParticleTypes.SMOKE) {
            Acc a = cur;
            if (a != null) a.trimmed++;
            return true;
        }
        if (!refuse) return false;
        Acc a = cur;
        if (a != null) a.refused++;
        census("type " + BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType()), -1);
        return true;
    }

    /** ParticleEngine.add: true to refuse it (an X run: its Flywheel visual taken down); else counted. */
    public static boolean added(Particle p) {
        if (refuse) {
            Acc a = cur;
            if (a != null) a.refused++;
            census(p.getClass().getName(), -1);
            p.remove();
            if (p instanceof VisualParticle v) v.removeVisual();
            return true;
        }
        Acc a = cur;
        if (a != null) a.spawned++;
        census(p.getClass().getName(), adds++ % 8 == 0 ? maker() : -1);
        return false;
    }

    private static void census(String cls, int maker) {
        long[] c = CENSUS.computeIfAbsent(cls, k -> new long[5]);
        c[0]++;
        if (maker >= 0) {
            c[1 + maker]++;
            MAKERS.computeIfAbsent(cls, k -> new TreeMap<>()).merge(makerFrame, 1L, Long::sum);
        }
    }

    // particle class -> the frame that made it (class#method) -> how many (sampled)
    private static final Map<String, Map<String, Long>> MAKERS = new TreeMap<>();
    private static String makerFrame = "";

    private static final String[] INFRA = {"java.", "jdk.", "sun.", "net.minecraft.", "com.mojang.", "org.spongepowered.", "com.llamalad7.",
            "net.neoforged.", "io.netty.", "it.unimi.", "com.google.", "dev.engine_room.", "net.caffeinemc.", "net.irisshaders.", "net.raphimc.",
            "acup.gunfeel.ParticleStand"};

    // whose code made the particle: 0 NTM's, 1 the mod's, 2 the game's, 3 another mod's
    private static int maker() {
        StackWalker.StackFrame f = WALKER.walk(s -> s.filter(fr -> {
            String n = fr.getClassName();
            for (String p : INFRA) if (n.startsWith(p)) return false;
            return true;
        }).findFirst().orElse(null));
        String c = f == null ? "" : f.getClassName();
        makerFrame = f == null ? "(game)" : c + "#" + f.getMethodName();
        return c.isEmpty() ? 2 : c.startsWith("com.hbm.") ? 0 : c.startsWith("acup.gunfeel.") ? 1 : 3;
    }

    private static String origin(String cls) {
        return cls.startsWith("com.hbm.") ? "ntm" : cls.startsWith("acup.gunfeel.") ? "ours" : cls.startsWith("net.minecraft.") ? "vanilla"
                : cls.startsWith("type ") ? "refused" : "other";
    }

    private static String name(Object o) {
        String n = o.getClass().getName();
        int dot = n.lastIndexOf('.');
        return (n.startsWith("com.hbm.") ? "ntm:" : n.startsWith("net.minecraft.") ? "mc:" : n.startsWith("acup.gunfeel.") ? "ours:" : "")
                + n.substring(dot + 1);
    }

    // ---------------------------------------------------------------- the run

    private static final List<Run> RUNS = new ArrayList<>();
    private static int run, phase, frameNo;
    private static long startedAt, last;
    private static boolean wasHidden;
    private static int window, sampled;
    private static final List<String> ROWS = new ArrayList<>(), GROUPS = new ArrayList<>(), CENSUS_ROWS = new ArrayList<>(),
            LIVE = new ArrayList<>();
    private static String savedQuality;
    // the server's: the stream's throwers, ticks left to fire
    private static final List<LivingEntity> THROWERS = new ArrayList<>();
    private static volatile What streaming;
    private static volatile boolean FP;
    private static volatile int streamLeft;
    private static Fluid chlorine;
    private static String casing;

    private ParticleStand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.options.enableVsync().set(false);
        mc.options.framerateLimit().set(260);
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        savedQuality = Config.smokeQuality;
        Config.smokeQuality = "high";
        for (int r = 0; r < ROUNDS; r++) for (Scene s : SCENES) {
            List<Mode> modes = new ArrayList<>(s.trial.isEmpty() ? List.of(Mode.N, Mode.X) : List.of(Mode.N, Mode.T, Mode.X));
            if (r % 2 == 1) java.util.Collections.reverse(modes);
            for (Mode m : modes) RUNS.add(new Run(s, m, r));
        }
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            var source = server.createCommandSourceStack().withSuppressedOutput();
            server.getCommands().performPrefixedCommand(source, "time set noon");
            server.getCommands().performPrefixedCommand(source, "weather clear");
            var rules = level.getGameRules();
            rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
            rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER, false, server);
            rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server);
            field(level);
            server.getPlayerList().getPlayers().getFirst().setGameMode(GameType.SPECTATOR);
            chlorine = BuiltInRegistries.FLUID.getValue(Identifier.fromNamespaceAndPath("hbm", "chlorine"));
            casing = XFactory556mm.r556_fmj.casing.getName();
        }).join();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.LevelTickEvent.Pre.class, e -> {
            if (e.getLevel() instanceof ServerLevel level && level == level.getServer().overworld()) stream(level);
        });
        try {
            Files.createDirectories(Path.of(DIR));
        } catch (Exception e) {
            ClientSmoke.check(false, "частицы: папка " + DIR + ": " + e);
        }
        ROWS.add("run\twindow\tfps\tframe_ms\tdrawn_ms\tdrawn_p95\tdrawn_p99\tdrawn_n\thidden_ms\thidden_p95\thidden_n\talive_avg\talive_max"
                + "\tspawned_s\trefused_s\ttick_ms\ttick_ms_s\textract_ms\tsubmit_ms\tprepare_ms\texecute_ms\tfw_frame_ms\tfw_render_ms\tload"
                + "\tcpu_ms\tdrawn_cpu\thidden_cpu\tgpu_quads_ms\tgpu_fw_drawn_ms\tgpu_fw_hidden_ms\tgpu_n\ttrimmed_s");
        GROUPS.add("run\twindow\tgroup\talive_avg\ttick_us\textract_us\tsubmit_us");
        CENSUS_ROWS.add("run\tclass\torigin\tadded\tmade_by_ntm\tmade_by_ours\tmade_by_game\tmade_by_other\tmakers");
        LIVE.add("run\tt_s\tclass\talive\tflywheel\tasleep");
        run = 0;
        phase = 0;
        last = System.nanoTime();
        lastCpu = MX.getCurrentThreadCpuTime();
        warmUntil = last + 8_000_000_000L;
        // the GPU's time where the game draws with OpenGL 3.3 (the render thread's context)
        try {
            gpu = org.lwjgl.opengl.GL.getCapabilities().OpenGL33;
        } catch (Throwable t) {
            gpu = false;
        }
        GunFeel.LOGGER.info("[particlestand] {} runs, GPU timing {}", RUNS.size(), gpu);
    }

    private static final java.lang.management.ThreadMXBean MX = java.lang.management.ManagementFactory.getThreadMXBean();
    private static long lastCpu, warmUntil;

    // the floor: lime concrete a block under Y, air 20 up; what a blast broke laid again (the same block is no change)
    private static void field(ServerLevel level) {
        var floor = Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.LIME).defaultBlockState();
        var air = Blocks.AIR.defaultBlockState();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = -48; x <= 48; x++) for (int z = -24; z <= 64; z++) {
            for (int y = Y - 4; y < Y - 1; y++) if (level.getBlockState(p.set(x, y, z)) != floor) level.setBlock(p, floor, 2);
            if (level.getBlockState(p.set(x, Y - 1, z)) != floor) level.setBlock(p, floor, 2);
            for (int y = Y; y <= Y + 20; y++) if (!level.getBlockState(p.set(x, y, z)).isAir()) level.setBlock(p, air, 2);
        }
    }

    // the server's tick: each thrower of a stream fires (the flamethrower and the chemical thrower a blob a tick, a rifle
    // a round every 2 ticks with its casing)
    private static void stream(ServerLevel level) {
        What w = streaming;
        if (w == null || streamLeft <= 0 || THROWERS.isEmpty()) return;
        int tick = STREAM_TICKS - streamLeft;
        for (LivingEntity t : THROWERS) {
            switch (w) {
                // at the eye: out of the muzzle as NTM's flamethrower fires (its receiver's offset 0.75 on, 0.0625 down, 0.25 aside)
                case FLAMER -> level.addFreshEntity(FP ? new EntityBulletBaseMK4(t, XFactoryFlamer.flame_diesel, 10F, 0.05F, -0.25, -0.0625, 0.75)
                        : new EntityBulletBaseMK4(t, XFactoryFlamer.flame_diesel, 10F, 0.05F, 0, -0.25, 0.5));
                case CHEM -> {
                    EntityChemical chem = new EntityChemical(ModEntities.CHEMICAL.get(), level, t, 0, 0, 0);
                    chem.setFluid(chlorine);
                    level.addFreshEntity(chem);
                }
                case GUNFIRE -> {
                    if (tick % 2 != 0) continue;
                    level.addFreshEntity(new EntityBulletBaseMK4(t, XFactory556mm.r556_fmj, 8F, 0.02F, 0, -0.1, 0.5));
                    CasingCreator.composeEffect(level, t.getX() + 0.12, t.getEyeY() - 0.125, t.getZ() + 0.5, 0F, 0F, 0.0, 0.18, -0.12, 0.01,
                            (float) level.getRandom().nextGaussian() * 5F, 12.5F + level.getRandom().nextFloat() * 5F, casing, false, 0, 0, 0);
                }
                default -> {
                }
            }
        }
        streamLeft--;
    }

    private static void setUp(Run r) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            field(level);
            Scene s = r.scene;
            if (s.stream()) {
                for (int i = 0; i < s.count; i++) {
                    double x = s.count == 1 ? GX - 1 : GX - 3 + 2 * i;
                    var stand = EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
                    stand.snapTo(x, Y, GZ - 12, 0F, 0F);
                    stand.setYHeadRot(0F);
                    stand.setNoGravity(true);
                    // the camera in it
                    if (s.fp) stand.setInvisible(true);
                    level.addFreshEntity(stand);
                    THROWERS.add(stand);
                }
                if (s.what == What.GUNFIRE) {
                    var stone = Blocks.STONE.defaultBlockState();
                    for (int x = -12; x <= 12; x++) for (int y = Y; y < Y + 6; y++) level.setBlock(new BlockPos(x, y, (int) GZ + 8), stone, 2);
                }
            }
        }).join();
    }

    // a burst through NTM's code: the grenade's explode (shell, filling, extras), C4's blast as its primed charge has it,
    // the shell type's onImpact on the ground, the Fat Mine's explodeVariant
    private static void burst(Scene s) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            double gap = s.what == What.WP ? 8 : 4;
            for (int i = 0; i < s.count; i++) {
                double x = GX + (s.count == 1 ? 0 : -gap * (s.count - 1) / 2 + gap * i), z = GZ;
                BlockHitResult ground = new BlockHitResult(new Vec3(x, Y, z), Direction.UP, BlockPos.containing(x, Y - 1, z), false);
                try {
                    switch (s.what) {
                        case HE, WP -> {
                            EntityGrenadeUniversal g = new EntityGrenadeUniversal(level, ItemGrenadeUniversal.make(EnumGrenadeShell.FRAG,
                                    s.what == What.HE ? EnumGrenadeFilling.HE : EnumGrenadeFilling.WP, EnumGrenadeFuze.S3));
                            g.setPos(x, Y + 0.1, z);
                            g.setDeltaMovement(Vec3.ZERO);
                            g.explode();
                        }
                        // NTM's block of C4 (block_c4, as block_semtex: ExplosionVNT 20, makeStandard) set off as its primed charge
                        case C4_BLOCK -> {
                            EntityTntNtm tnt = new EntityTntNtm(ModEntities.TNT_NTM.get(), level, x, Y, z, null, ModBlocks.BLOCK_C4.get().defaultBlockState());
                            tnt.explode();
                            tnt.discard();
                        }
                        // NTM's C4 charge (charge_c4) as its detonator sets it off
                        case C4_CHARGE -> ModBlocks.CHARGE_C4.get().explode(level, BlockPos.containing(x, Y, z), null);
                        case ARTY -> {
                            EntityArtilleryShell shell = new EntityArtilleryShell(ModEntities.ARTILLERY_SHELL.get(), level);
                            shell.setPos(x, Y + 0.5, z);
                            shell.setDeltaMovement(new Vec3(0, -2, 0));
                            ItemAmmoArty.ArtilleryShellType.EXPLOSIVE.onImpact(shell, ground);
                            shell.discard();
                        }
                        case NUKE -> {
                            Method m = BlockLandmineFat.class.getDeclaredMethod("explodeVariant", net.minecraft.world.level.Level.class, BlockPos.class);
                            m.setAccessible(true);
                            m.invoke(ModBlocks.MINE_FAT.get(), level, BlockPos.containing(x, Y, z));
                        }
                        default -> {
                        }
                    }
                } catch (Exception e) {
                    ClientSmoke.check(false, "частицы: " + s.name + ": " + e);
                }
            }
        }).join();
    }

    private static void tearDown() {
        streamLeft = 0;
        streaming = null;
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            THROWERS.forEach(Entity::discard);
            THROWERS.clear();
            for (var type : List.of(ModEntities.FIRE_LINGERING.get(), ModEntities.CHEMICAL.get(), ModEntities.BULLET_MK4.get(), ModEntities.NUKE_TOREX.get(),
                    ModEntities.MIST.get())) for (Entity e : level.getEntities(type, e -> true)) e.discard();
            for (Entity e : level.getEntities(EntityTypes.ITEM, e -> true)) e.discard();
            SmokeClouds all = SmokeClouds.get(level);
            if (all != null) for (var c : all.clouds()) {
                all.remove(c.id);
                SmokeNet.ended(level, c);
            }
        }).join();
        purge();
    }

    // every particle gone, its Flywheel visual too (a particle cleared without being removed keeps its visual drawn)
    private static void purge() {
        var engine = Minecraft.getInstance().particleEngine;
        for (ParticleGroup<?> g : groups()) for (Particle p : queue(g)) {
            p.remove();
            if (p instanceof VisualParticle v) v.removeVisual();
        }
        engine.clearParticles();
    }

    private static Field groupsField, queueField;

    @SuppressWarnings("unchecked")
    private static Collection<ParticleGroup<?>> groups() {
        try {
            if (groupsField == null) {
                groupsField = net.minecraft.client.particle.ParticleEngine.class.getDeclaredField("particles");
                groupsField.setAccessible(true);
            }
            return new ArrayList<>(((Map<?, ParticleGroup<?>>) groupsField.get(Minecraft.getInstance().particleEngine)).values());
        } catch (Exception e) {
            ClientSmoke.check(false, "частицы: группы " + e);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<Particle> queue(ParticleGroup<?> g) {
        try {
            if (queueField == null) {
                queueField = ParticleGroup.class.getDeclaredField("particles");
                queueField.setAccessible(true);
            }
            return new ArrayList<>((Collection<Particle>) queueField.get(g));
        } catch (Exception e) {
            ClientSmoke.check(false, "частицы: очередь " + e);
            return List.of();
        }
    }

    // a stream behind its thrower (fp: at its eye, an armour stand's 1.7775); the rifles from behind them (their casings in sight)
    private static Vec3 eye(Scene s) {
        if (s.fp) return new Vec3(GX - 1, Y + 1.7775, GZ - 12);
        if (s.what == What.GUNFIRE) return new Vec3(GX + 1, Y + 2.2, GZ - 17);
        return s.stream() ? new Vec3(GX, Y + 1.62, GZ - 14.5) : new Vec3(GX, Y + s.up, GZ - s.dist);
    }

    private static float pitch(Scene s) {
        if (s.fp) return 0F;
        if (s.what == What.GUNFIRE) return 12F;
        return s.stream() ? 6F : (float) Math.toDegrees(Math.atan2(s.up - 1, s.dist));
    }

    private static void place(Scene s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Vec3 e = eye(s);
        float pitch = pitch(s);
        mc.player.setPos(e.x, e.y - mc.player.getEyeHeight(), e.z);
        mc.player.setYRot(0F);
        mc.player.setXRot(pitch);
        mc.player.yRotO = 0F;
        mc.player.xRotO = pitch;
        mc.player.setDeltaMovement(Vec3.ZERO);
    }

    static void frame() {
        long now = System.nanoTime();
        long dt = now - last;
        last = now;
        long cpuNow = MX.getCurrentThreadCpuTime();
        long dcpu = cpuNow - lastCpu;
        lastCpu = cpuNow;
        if (cur != null) {
            (wasHidden ? cur.hidden : cur.drawn).add(dt);
            (wasHidden ? cur.hiddenCpu : cur.drawnCpu).add(dcpu);
        }
        gpuFrame(cur, wasHidden);
        if (run >= RUNS.size()) return;
        Run r = RUNS.get(run);
        place(r.scene);
        double t = (now - startedAt) / 1e9 - PRE;
        switch (phase) {
            case 0 -> {
                var server = Minecraft.getInstance().getSingleplayerServer();
                Vec3 e = eye(r.scene);
                float pitch = pitch(r.scene);
                server.submit(() -> {
                    var sp = server.getPlayerList().getPlayers().getFirst();
                    sp.teleportTo(server.overworld(), e.x, e.y - sp.getEyeHeight(), e.z, Set.of(), 0F, pitch, false);
                }).join();
                // the last run's clouds gone from the client before the next begins
                SmokeClouds known = SmokeClient.current();
                if (known != null && !known.clouds().isEmpty() || now < warmUntil) return;
                setUp(r);
                purge();
                CENSUS.clear();
                adds = 0;
                refuse = r.refused();
                trial = r.trial();
                BASE_SIZE.clear();
                shot = 0;
                shotLeft = 0;
                holeStep = 0;
                frameNo = 0;
                window = 0;
                sampled = 0;
                startedAt = now;
                phase = 1;
            }
            case 1 -> {
                if (window == 0 && t >= -1.2) open("before", -1.2, 0);
                if (t < 0) break;
                close(r);
                if (r.scene.stream()) {
                    FP = r.scene.fp;
                    streamLeft = STREAM_TICKS;
                    streaming = r.scene.what;
                } else burst(r.scene);
                phase = 2;
            }
            case 2 -> {
                double[][] w = r.scene.stream() ? new double[][] {{2, 10}, {10, 15}} : new double[][] {{0, 3}, {3, 12}};
                String[] names = r.scene.stream() ? new String[] {"stream", "after"} : new String[] {"0-3s", "3-12s"};
                if (cur != null && t >= cur.to) close(r);
                if (cur == null && window - 1 < w.length && t >= w[window - 1][0]) open(names[window - 1], w[window - 1][0], w[window - 1][1]);
                double[] at = r.scene.stream() ? new double[] {6} : new double[] {1, 5};
                if (!r.refused() && sampled < at.length && t >= at[sampled]) live(r, at[sampled++]);
                double[] shots = r.scene.stream() ? new double[] {6} : new double[] {0.5, 2};
                if (r.round == 0 && !r.refused() && shotLeft == 0 && shot < shots.length && t >= shots[shot]) shotLeft = 3;
                if (r.scene.what == What.GUNFIRE && r.mode == Mode.N) hole(r, t);
                if (t >= r.scene.end()) {
                    if (cur != null) close(r);
                    censusRows(r);
                    write();
                    tearDown();
                    refuse = false;
                    trial = "";
                    hide = false;
                    run++;
                    phase = 0;
                    if (run >= RUNS.size()) done();
                }
            }
            default -> {
            }
        }
        // a shot: the drawing on for 3 frames, the second of them shot (the frame before this one, drawn)
        if (shotLeft > 0 && --shotLeft == 1) {
            double[] shots = r.scene.stream() ? new double[] {6} : new double[] {0.5, 2};
            shoot(r, shots[shot++]);
        }
        // N and T: the drawing off every other 32 frames
        wasHidden = hide;
        hide = phase != 0 && !r.refused() && shotLeft == 0 && (frameNo++ / 32) % 2 == 1;
    }

    private static int shot, shotLeft;

    // the rifles' casings lying 1.5 s after the last round (asleep, with MixinCasingSleep): the floor's top under them
    // taken away, 1.5 s on every one of them a block lower
    private static final Map<Particle, Double> LYING = new IdentityHashMap<>();
    private static int holeStep;

    private static void hole(Run r, double t) {
        if (holeStep == 0 && t >= 11.5) {
            holeStep = 1;
            LYING.clear();
            int asleep = 0;
            for (ParticleGroup<?> g : groups()) for (Particle p : queue(g)) {
                if (!(p instanceof com.hbm.particle.ParticleSpentCasing) || !p.isAlive()) continue;
                Vec3 c = p.getBoundingBox().getCenter();
                if (Math.abs(c.x - GX) > 6 || c.z < GZ - 13 || c.z > GZ - 6 || c.y > Y + 0.2) continue;
                LYING.put(p, p.getBoundingBox().minY);
                if (p instanceof SleepingCasing sc && sc.gunfeel$asleep()) asleep++;
            }
            GunFeel.LOGGER.info("[particlestand] {} гильз на полу у стрелков, спят {}", LYING.size(), asleep);
            var server = Minecraft.getInstance().getSingleplayerServer();
            server.submit(() -> {
                var air = Blocks.AIR.defaultBlockState();
                for (int x = (int) GX - 7; x <= (int) GX + 7; x++) for (int z = (int) GZ - 14; z <= (int) GZ - 5; z++)
                    server.overworld().setBlock(new BlockPos(x, Y - 1, z), air, 2);
            }).join();
        }
        if (holeStep == 1 && t >= 13) {
            holeStep = 2;
            int fell = 0, gone = 0;
            for (var e : LYING.entrySet()) {
                if (!e.getKey().isAlive()) gone++;
                else if (e.getKey().getBoundingBox().minY < e.getValue() - 0.9) fell++;
            }
            ClientSmoke.check(LYING.size() - gone > 0 && fell == LYING.size() - gone,
                    "частицы: " + r.label() + " гильз на полу " + LYING.size() + ", упали в яму " + fell + ", исчезли " + gone);
            LYING.clear();
        }
    }

    private static void shoot(Run r, double at) {
        Path file = Path.of(DIR, String.format(Locale.ROOT, "%s-%s-%.1fs.png", r.scene.name, r.mode, at));
        net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(file);
            } catch (Exception e) {
                GunFeel.LOGGER.error("[particlestand] {}", file, e);
            }
        });
    }

    private static void open(String name, double from, double to) {
        cur = new Acc(name, from, to);
        window++;
    }

    private static void close(Run r) {
        Acc a = cur;
        cur = null;
        if (a == null) return;
        List<Long> all = new ArrayList<>(a.drawn);
        all.addAll(a.hidden);
        double secs = a.to - a.from;
        int nd = Math.max(1, a.drawn.size());
        double load = 0;
        try {
            load = Double.parseDouble(Files.readString(Path.of("/proc/loadavg")).split(" ")[0]);
        } catch (Exception ignored) {
        }
        String row = String.format(Locale.ROOT, "%s\t%s\t%.0f\t%.3f\t%.3f\t%.3f\t%.3f\t%d\t%.3f\t%.3f\t%d\t%.0f\t%d\t%.0f\t%.0f\t%.4f\t%.3f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.2f\t%.3f\t%.3f\t%.3f\t%.4f\t%.4f\t%.4f\t%d\t%.0f",
                r.label(), a.name, 1000 / Math.max(1e-9, mean(all)), mean(all), mean(a.drawn), pct(a.drawn, 0.95), pct(a.drawn, 0.99), a.drawn.size(),
                mean(a.hidden), pct(a.hidden, 0.95), a.hidden.size(), a.aliveSamples == 0 ? 0 : a.aliveSum / (double) a.aliveSamples, a.aliveMax,
                a.spawned / secs, a.refused / secs, a.ticks == 0 ? 0 : a.tickNs / 1e6 / a.ticks, a.tickNs / 1e6 / secs, a.extractNs / 1e6 / nd,
                a.submitNs / 1e6 / nd, a.prepareNs / 1e6 / nd, a.executeNs / 1e6 / nd, a.fwFrameNs / 1e6 / Math.max(1, all.size()),
                a.fwRenderNs / 1e6 / Math.max(1, all.size()), load, (sum(a.drawnCpu) + sum(a.hiddenCpu)) / 1e6 / Math.max(1, all.size()),
                mean(a.drawnCpu), mean(a.hiddenCpu), a.gpuQuadsDrawn / 1e6 / Math.max(1, a.gpuDrawn), a.gpuFwDrawn / 1e6 / Math.max(1, a.gpuDrawn),
                a.gpuFwHidden / 1e6 / Math.max(1, a.gpuHidden), a.gpuDrawn + a.gpuHidden, a.trimmed / secs);
        ROWS.add(row);
        GunFeel.LOGGER.info("[particlestand] {}", row);
        for (var e : a.groups.entrySet()) {
            long[] s = e.getValue();
            GROUPS.add(String.format(Locale.ROOT, "%s\t%s\t%s\t%.0f\t%.2f\t%.2f\t%.2f", r.label(), a.name, e.getKey(), s[7] == 0 ? 0 : s[6] / (double) s[7],
                    s[1] == 0 ? 0 : s[0] / 1e3 / s[1], s[2] / 1e3 / nd, s[4] / 1e3 / nd));
        }
        STATES.clear();
    }

    private static long sum(List<Long> t) {
        long n = 0;
        for (long v : t) n += v;
        return n;
    }

    private static double mean(List<Long> t) {
        return t.isEmpty() ? 0 : t.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
    }

    private static double pct(List<Long> t, double q) {
        if (t.isEmpty()) return 0;
        List<Long> s = new ArrayList<>(t);
        s.sort(Long::compare);
        return s.get(Math.min(s.size() - 1, (int) (s.size() * q))) / 1e6;
    }

    // alive by class at a moment (and how many of them NTM's Flywheel draws), NTM's mushroom puffs too
    private static void live(Run r, double at) {
        Map<String, int[]> by = new TreeMap<>();
        for (ParticleGroup<?> g : groups()) for (Particle p : queue(g)) {
            int[] c = by.computeIfAbsent(p.getClass().getName(), k -> new int[3]);
            c[0]++;
            if (p instanceof VisualParticle v && v.ownsVisual()) c[1]++;
            if (p instanceof SleepingCasing sc && sc.gunfeel$asleep()) c[2]++;
        }
        var level = Minecraft.getInstance().level;
        int puffs = 0;
        if (level != null) for (Entity e : level.entitiesForRendering()) if (e instanceof EntityNukeTorex tx) puffs += tx.cloudlets.size();
        if (puffs > 0) by.put("(entity) com.hbm.entity.effect.EntityNukeTorex cloudlets", new int[] {puffs, 0, 0});
        for (var e : by.entrySet())
            LIVE.add(String.format(Locale.ROOT, "%s\t%.0f\t%s\t%d\t%d\t%d", r.label(), at, e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]));
    }

    private static void censusRows(Run r) {
        // NTM's standard blast puffs at BlastParticles.MAX of its blocks at most
        if (r.scene.what == What.C4_BLOCK && !r.refused()) {
            long[] puffs = CENSUS.get("net.minecraft.client.particle.ExplodeParticle");
            long n = puffs == null ? 0 : puffs[0];
            ClientSmoke.check(n > 0 && n <= BlastParticles.MAX, "частицы: " + r.label() + " клубов C4 " + n + " (не больше " + BlastParticles.MAX + ")");
        }
        for (var e : CENSUS.entrySet()) {
            long[] c = e.getValue();
            StringBuilder m = new StringBuilder();
            MAKERS.getOrDefault(e.getKey(), Map.of()).entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(3)
                    .forEach(x -> m.append(m.isEmpty() ? "" : ", ").append(x.getKey()).append(' ').append(x.getValue()));
            CENSUS_ROWS.add(String.format(Locale.ROOT, "%s\t%s\t%s\t%d\t%d\t%d\t%d\t%d\t%s", r.label(), e.getKey(), origin(e.getKey()), c[0], c[1], c[2], c[3], c[4], m));
        }
        MAKERS.clear();
    }

    // the tables so far, after every run (a run cut short keeps what it had)
    private static void write() {
        try {
            Files.write(Path.of(DIR, "runs.tsv"), ROWS);
            Files.write(Path.of(DIR, "groups.tsv"), GROUPS);
            Files.write(Path.of(DIR, "census.tsv"), CENSUS_ROWS);
            Files.write(Path.of(DIR, "live.tsv"), LIVE);
        } catch (Exception e) {
            ClientSmoke.check(false, "частицы: запись " + e);
        }
    }

    private static void done() {
        Config.smokeQuality = savedQuality;
        write();
        // the X runs had no particle at all, the N runs had them
        for (String row : ROWS) {
            String[] f = row.split("\t");
            if (f[0].equals("run") || !f[1].equals("0-3s") && !f[1].equals("stream")) continue;
            double alive = Double.parseDouble(f[11]);
            if (f[0].contains("-X-")) ClientSmoke.check(alive == 0, "частицы: " + f[0] + " живых " + alive + " при запрете");
            // the Fat Mine's cloud is NTM's Torex, an entity: no particle
            // NTM's chemical thrower: its blobs are entities, the cloud the mod's: no particle
            else if (!f[0].startsWith("nuke-") && !f[0].startsWith("chem-")) ClientSmoke.check(alive > 0, "частицы: " + f[0] + " ни одной частицы");
        }
        ClientSmoke.finish();
    }
}
