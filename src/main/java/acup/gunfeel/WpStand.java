package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import com.hbm.entity.ModEntities;
import com.hbm.entity.grenade.EntityGrenadeUniversal;
import com.hbm.entity.projectile.EntityArtilleryRocket;
import com.hbm.entity.projectile.EntityArtilleryShell;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.ItemAmmoArty;
import com.hbm.items.weapon.ItemAmmoHIMARS;
import com.hbm.items.weapon.grenade.ItemGrenadeFilling;
import com.hbm.items.weapon.grenade.ItemGrenadeFilling.EnumGrenadeFilling;
import com.hbm.items.weapon.grenade.ItemGrenadeFuze.EnumGrenadeFuze;
import com.hbm.items.weapon.grenade.ItemGrenadeShell.EnumGrenadeShell;
import com.hbm.items.weapon.grenade.ItemGrenadeUniversal;
import com.hbm.items.weapon.sedna.factory.XFactory40mm;
import com.hbm.items.weapon.sedna.factory.XFactoryRocket;

import acup.gunfeel.gas.WpSmoke;
import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeNet;
import acup.gunfeel.smoke.client.SmokeClient;
import acup.gunfeel.smoke.client.SmokeStats;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

// runCheckClient -PwpStand=<dir>: NTM's white phosphorus through NTM's own code, each scene twice in one run: A as NTM
// has it (WpSmoke.forced false: the grenade's hazes, the shells' mushroom, the 40 mm and the rocket no smoke), B with the
// mod's white cloud. Scenes on a flat field at clear noon, high smoke quality, the HUD hidden, vsync and the frame limit
// off: one hand grenade (ItemGrenadeFilling.explode) seen from 22 blocks, the camera inside it, three grenades 8 blocks
// apart seen from 22 blocks and from inside the middle one; six 40 mm rounds 0.2 s apart (a grenade launcher's burst),
// a rocket, an artillery shell, ten artillery shells about (a cluster's) and a HIMARS rocket, all of them seen from 22
// blocks, each hitting the ground through its own impact (XFactory40mm and XFactoryRocket's phosphorus impact, the
// shell types' onImpact). -PwpScenes=g40,arty10 only those, -PwpRounds=1 once (default twice over). A run: 1.5 s before
// the burst (the base frame), the burst, 26 s after. At 3, 8, 13, 18 and 23 s a row
// (<dir>/wp.tsv): clouds, NTM's hazes on the client, NTM's fires on the ground, the client's particles, the server's ms a
// tick, the smoke's own time (server, client), the packets since the burst (all, hazes, the smoke's); frame times from
// 2 to 22 s (<dir>/frames.tsv). Screenshots of the outside scenes in the first round: <scene>-<mode>-{base,5s,10s,20s}.png.
final class WpStand {
    static final String DIR = System.getProperty("ntmgunfeel.wpStand", "");
    static final boolean ENABLED = !DIR.isBlank();
    private static final int Y = 100;
    private static final double GX = 0.5, GZ = 24.5;
    private static final double[] SAMPLES = {3, 8, 13, 18, 23};
    private static final double[] SHOTS = {5, 10, 20};
    private static final double PRE = 1.5, LENGTH = 26, FRAMES_FROM = 2, FRAMES_TO = 22;
    private static final int ROUNDS = Integer.getInteger("ntmgunfeel.wpRounds", 2);
    private static final Set<String> ONLY = Set.copyOf(List.of(System.getProperty("ntmgunfeel.wpScenes", "").split(",")));

    private enum What { GRENADE, G40, ROCKET, ARTY, HIMARS }

    // count bursts, every delay seconds after the first, at the offsets (blocks, x and z) from the scene's spot
    private record Scene(String name, What what, int count, double delay, boolean inside, double[][] at) {
        Scene(String name, What what, int count, boolean inside) {
            this(name, what, count, 0, inside, row(count));
        }

        // a row 8 blocks apart
        static double[][] row(int n) {
            double[][] out = new double[n][];
            for (int i = 0; i < n; i++) out[i] = new double[] {n == 1 ? 0 : -8 + 8 * i, 0};
            return out;
        }
    }

    private static final List<Scene> SCENES = java.util.stream.Stream.of(new Scene("out1", What.GRENADE, 1, false),
            new Scene("in1", What.GRENADE, 1, true), new Scene("out3", What.GRENADE, 3, false), new Scene("in3", What.GRENADE, 3, true),
            // a burst of a grenade launcher at a spot: 2 to 3 blocks apart, as a magazine's rounds land
            new Scene("g40", What.G40, 6, 0.2, false, new double[][] {{0, 0}, {2, 1}, {-2, 2}, {1, -2}, {-1, -1}, {3, -1}}),
            new Scene("rocket", What.ROCKET, 1, false), new Scene("arty", What.ARTY, 1, false),
            // a cluster's ten shells strewn some 12 blocks about
            new Scene("arty10", What.ARTY, 10, 0.1, false, new double[][] {{0, 0}, {9, 3}, {-8, 5}, {4, -9}, {-5, -7}, {12, -4},
                    {-12, -2}, {2, 11}, {-3, 12}, {7, 8}}),
            new Scene("himars", What.HIMARS, 1, false))
            .filter(s -> ONLY.contains("") || ONLY.contains(s.name)).toList();

    private record Run(Scene scene, boolean ours, int round) {
        String label() {
            return scene.name + "-" + (ours ? "B" : "A") + "-" + round;
        }
    }

    private static final List<Run> RUNS = new ArrayList<>();
    private static int run, phase, sample, shot, fired;
    private static long startedAt, last;
    // the run's base frame taken (before the burst)
    private static boolean based;
    private static final List<Long> TIMES = new ArrayList<>();
    private static final List<String> ROWS = new ArrayList<>(), FRAMES = new ArrayList<>();
    private static final List<String> FAILS = new ArrayList<>();
    private static String savedQuality;
    private static volatile boolean counting;
    private static final Map<String, LongAdder> PACKETS = new ConcurrentHashMap<>();

    private WpStand() {
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
            RUNS.add(new Run(s, false, r));
            RUNS.add(new Run(s, true, r));
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
            for (int x = -40; x <= 40; x++) for (int z = -10; z <= 56; z++) {
                level.setBlock(new BlockPos(x, Y - 1, z), Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.LIME).defaultBlockState(), 2);
                for (int y = Y; y <= Y + 16; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            server.getPlayerList().getPlayers().getFirst().setGameMode(GameType.SPECTATOR);
        }).join();
        // the packets to the client by kind, while a run counts them
        var pipe = mc.getConnection().getConnection().channel().pipeline();
        pipe.addBefore(pipe.context(Connection.class).name(), "wp_stand_count", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (counting) count(msg);
                ctx.fireChannelRead(msg);
            }
        });
        try {
            Files.createDirectories(Path.of(DIR));
        } catch (Exception e) {
            ClientSmoke.check(false, "фосфор: папка " + DIR + ": " + e);
        }
        ROWS.add("run\tt_s\tclouds\thazes\tfires\tparticles\tmspt\tsmoke_server_ms\tsmoke_client_tick_ms\tpackets\thaze_packets\tsmoke_packets");
        FRAMES.add("run\tframe_ms\tp95_ms\tp99_ms\tfps\tframes");
        run = 0;
        phase = 0;
        last = System.nanoTime();
    }

    private static void count(Object msg) {
        if (msg instanceof net.minecraft.network.protocol.game.ClientboundBundlePacket b) {
            for (var p : b.subPackets()) count(p);
            return;
        }
        String name = msg instanceof ClientboundCustomPayloadPacket c ? c.payload().type().id().toString() : msg.getClass().getSimpleName();
        PACKETS.computeIfAbsent(name, k -> new LongAdder()).increment();
    }

    private static long packets(java.util.function.Predicate<String> which) {
        long n = 0;
        for (var e : PACKETS.entrySet()) if (which.test(e.getKey())) n += e.getValue().sum();
        return n;
    }

    // bursts from to to (one server task: the grenades of a scene with no delay go off in one tick, as they always did)
    // go off through NTM's code: the grenade's explode (the burst, the fire on the ground, the blocks set alight, the
    // hazes), the rounds' phosphorus impact on the ground, the shells' onImpact
    private static void burst(Run r, int from, int to) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            WpSmoke.forced = r.ours;
            for (int i = from; i < to; i++) {
                double x = GX + r.scene.at[i][0], z = GZ + r.scene.at[i][1];
                BlockHitResult ground = new BlockHitResult(new Vec3(x, Y, z), Direction.UP, BlockPos.containing(x, Y - 1, z), false);
                switch (r.scene.what) {
                    case GRENADE -> {
                        EntityGrenadeUniversal g = new EntityGrenadeUniversal(level,
                                ItemGrenadeUniversal.make(EnumGrenadeShell.FRAG, EnumGrenadeFilling.WP, EnumGrenadeFuze.S3));
                        g.setPos(x, Y + 0.1, z);
                        g.setDeltaMovement(Vec3.ZERO);
                        ItemGrenadeFilling.explode(g);
                        g.discard();
                    }
                    case G40, ROCKET -> {
                        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(ModEntities.BULLET_MK4.get(), level);
                        boolean g40 = r.scene.what == What.G40;
                        b.config = g40 ? XFactory40mm.g40_phosphorus : XFactoryRocket.rocket_rpzb[4];
                        b.setPos(x, Y + 0.5, z);
                        b.setDeltaMovement(new Vec3(0, -1, 0));
                        (g40 ? XFactory40mm.LAMBDA_STANDARD_EXPLODE_PHOSPHORUS : XFactoryRocket.LAMBDA_STANDARD_EXPLODE_PHOSPHORUS).accept(b, ground);
                        b.discard();
                    }
                    case ARTY -> {
                        EntityArtilleryShell shell = new EntityArtilleryShell(ModEntities.ARTILLERY_SHELL.get(), level);
                        shell.setPos(x, Y + 0.5, z);
                        shell.setDeltaMovement(new Vec3(0, -2, 0));
                        ItemAmmoArty.ArtilleryShellType.PHOSPHORUS.onImpact(shell, ground);
                        shell.discard();
                    }
                    case HIMARS -> {
                        EntityArtilleryRocket rocket = new EntityArtilleryRocket(ModEntities.HIMARS.get(), level);
                        rocket.setPos(x, Y + 0.5, z);
                        rocket.setDeltaMovement(new Vec3(0, -2, 0));
                        ItemAmmoHIMARS.HIMARSRocketType.SMALL_WP.onImpact(rocket, ground);
                        rocket.discard();
                    }
                }
            }
        }).join();
    }

    private static void tearDown() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            for (Entity e : level.getEntities(ModEntities.FIRE_LINGERING.get(), e -> true)) e.discard();
            SmokeClouds all = SmokeClouds.get(level);
            if (all != null) for (var c : all.clouds()) {
                all.remove(c.id);
                SmokeNet.ended(level, c);
            }
            WpSmoke.forced = null;
        }).join();
        // NTM's hazes outlive the run (35 s): gone with the rest of the particles before the next
        Minecraft.getInstance().particleEngine.clearParticles();
    }

    private static Vec3 eye(Scene s) {
        return s.inside ? new Vec3(GX, Y + 1.6, GZ + 1) : new Vec3(GX, Y + 3.5, GZ - 22);
    }

    private static void place(Scene s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Vec3 e = eye(s);
        float pitch = s.inside ? 0F : 8F;
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
        if (run >= RUNS.size()) return;
        Run r = RUNS.get(run);
        place(r.scene);
        double t = (now - startedAt) / 1e9 - PRE;
        switch (phase) {
            case 0 -> {
                var server = Minecraft.getInstance().getSingleplayerServer();
                Vec3 e = eye(r.scene);
                server.submit(() -> {
                    var sp = server.getPlayerList().getPlayers().getFirst();
                    sp.teleportTo(server.overworld(), e.x, e.y - sp.getEyeHeight(), e.z, Set.of(), 0F, r.scene.inside ? 0F : 8F, false);
                }).join();
                // the last run's clouds gone from the client before the next begins
                SmokeClouds known = SmokeClient.current();
                if (known != null && !known.clouds().isEmpty()) return;
                startedAt = now;
                based = false;
                TIMES.clear();
                sample = 0;
                shot = 0;
                phase = 1;
            }
            case 1 -> {
                if (!based && t >= -0.2 && shots(r)) {
                    shoot(r, "base");
                    based = true;
                }
                if (t < 0) return;
                PACKETS.clear();
                counting = true;
                fired = r.scene.delay == 0 ? r.scene.count : 1;
                burst(r, 0, fired);
                phase = 2;
            }
            case 2 -> {
                while (fired < r.scene.count && t >= fired * r.scene.delay) burst(r, fired, ++fired);
                if (t >= FRAMES_FROM && t < FRAMES_TO) TIMES.add(dt);
                if (t >= FRAMES_TO && !TIMES.isEmpty()) frames(r);
                if (shots(r) && shot < SHOTS.length && t >= SHOTS[shot]) {
                    shoot(r, (int) SHOTS[shot] + "s");
                    shot++;
                }
                if (sample < SAMPLES.length && t >= SAMPLES[sample]) {
                    sample(r, SAMPLES[sample]);
                    sample++;
                }
                if (t >= LENGTH) {
                    counting = false;
                    tearDown();
                    run++;
                    phase = 0;
                    if (run >= RUNS.size()) done();
                }
            }
            default -> {
            }
        }
    }

    private static boolean shots(Run r) {
        return r.round == 0 && !r.scene.inside;
    }

    private static void shoot(Run r, String what) {
        Path file = Path.of(DIR, r.scene.name + "-" + (r.ours ? "B" : "A") + "-" + what + ".png");
        net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(file);
            } catch (Exception e) {
                GunFeel.LOGGER.error("[wpstand] {}", file, e);
            }
        });
    }

    private static void frames(Run r) {
        List<Long> t = new ArrayList<>(TIMES);
        TIMES.clear();
        t.sort(Long::compare);
        double avg = t.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        double p95 = t.get(Math.min(t.size() - 1, (int) (t.size() * 0.95))) / 1e6;
        double p99 = t.get(Math.min(t.size() - 1, (int) (t.size() * 0.99))) / 1e6;
        String row = String.format(Locale.ROOT, "%s\t%.3f\t%.3f\t%.3f\t%.0f\t%d", r.label(), avg, p95, p99, avg > 0 ? 1000 / avg : 0, t.size());
        FRAMES.add(row);
        GunFeel.LOGGER.info("[wpstand] {}", row);
    }

    private static void sample(Run r, double at) {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        int hazes = hazes();
        String particles = mc.particleEngine.countParticles();
        String client = SmokeStats.last;
        int tick = client.indexOf("tick ");
        String clientTick = tick >= 0 ? client.substring(tick + 5, client.indexOf(" ms", tick)) : "-";
        String[] row = new String[1];
        server.submit(() -> {
            ServerLevel level = server.overworld();
            SmokeClouds all = SmokeClouds.get(level);
            int clouds = all == null ? 0 : all.clouds().size();
            int fires = level.getEntities(ModEntities.FIRE_LINGERING.get(), e -> true).size();
            String cost = SmokeClouds.serverCost();
            int cut = cost.indexOf(" ms");
            row[0] = String.format(Locale.ROOT, "%s\t%.0f\t%d\t%d\t%d\t%s\t%.3f\t%s\t%s\t%d\t%d\t%d", r.label(), at, clouds, hazes, fires, particles,
                    server.getAverageTickTimeNanos() / 1e6, r.ours && cut > 0 ? cost.substring(0, cut) : "-", r.ours ? clientTick : "-",
                    packets(k -> true), packets(k -> k.equals("hbm:haze")), packets(k -> k.startsWith("ntmgunfeel:smoke")));
            // what each mode is: A NTM's (the grenade's hazes) and no cloud; B a cloud a grenade, at least one for a burst
            // or a cluster (the next ones may feed it), and no haze; NTM's fire on the ground in both (the shells have none)
            if (at == SAMPLES[0]) {
                int n = r.scene.count;
                boolean grenade = r.scene.what == What.GRENADE;
                int wantFires = grenade || r.scene.what == What.G40 || r.scene.what == What.ROCKET ? n : 0;
                if (fires != wantFires) FAILS.add(r.label() + ": огней NTM " + fires + " вместо " + wantFires);
                boolean ok = r.ours ? (grenade ? clouds == n : clouds >= 1 && clouds <= n) && hazes == 0 : clouds == 0 && hazes == (grenade ? 3 * n : 0);
                if (!ok) FAILS.add(r.label() + ": облаков " + clouds + ", дымок NTM " + hazes);
            }
        }).join();
        ROWS.add(row[0]);
        GunFeel.LOGGER.info("[wpstand] {}", row[0]);
    }

    // NTM's hazes alive on the client (its HazeParticleGroup's queue)
    private static int hazes() {
        try {
            var engine = Minecraft.getInstance().particleEngine;
            for (var f : engine.getClass().getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                for (Object g : ((Map<?, ?>) f.get(engine)).values()) {
                    if (!g.getClass().getName().equals("com.hbm.particle.HazeParticleGroup")) continue;
                    var q = net.minecraft.client.particle.ParticleGroup.class.getDeclaredField("particles");
                    q.setAccessible(true);
                    return ((java.util.Collection<?>) q.get(g)).size();
                }
            }
        } catch (Exception e) {
            return -1;
        }
        return 0;
    }

    private static void done() {
        Config.smokeQuality = savedQuality;
        ROWS.add("# checks: matched " + SmokeClient.matched + ", apart " + SmokeClient.apart + ", grids " + SmokeClient.grids);
        try {
            Files.write(Path.of(DIR, "wp.tsv"), ROWS);
            Files.write(Path.of(DIR, "frames.tsv"), FRAMES);
        } catch (Exception e) {
            ClientSmoke.check(false, "фосфор: запись " + e);
        }
        for (String f : FAILS) ClientSmoke.check(false, "фосфор: " + f);
        ClientSmoke.check(SmokeClient.apart == 0, "фосфор: хеши клиента разошлись с сервером " + SmokeClient.apart + " раз");
        ClientSmoke.finish();
    }
}
