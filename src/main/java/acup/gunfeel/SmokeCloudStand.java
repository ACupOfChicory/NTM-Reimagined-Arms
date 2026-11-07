package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeKind;
import acup.gunfeel.smoke.SmokeNet;
import acup.gunfeel.smoke.SmokeSight;
import acup.gunfeel.smoke.client.SmokeClient;
import acup.gunfeel.smoke.client.SmokeFog;
import acup.gunfeel.smoke.client.SmokeStats;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

// runCheckClient -PsmokeStand=<dir>: smoke clouds (acup.gunfeel.smoke) measured in a real client, numbers only.
// A lime floor (smoke is grey, so a pixel of the floor still showing is counted by its colour) with 8 clouds in a row
// 30 blocks ahead; a red wall with one cloud behind it. Each camera pose is shot without clouds first (the reference),
// then with them at every smokeQuality: the frame time over 600 frames (vsync and the frame limit off) and a
// screenshot <dir>/<pose>-<quality>.png. Poses: outside looking at the 8, inside one of them (straight, yaw +135
// pitch +30, yaw -135 pitch -30), and at the wall. <dir>/frames.tsv has the times; the pixels are counted afterwards
// (notes/gl54/smoke-stand.py). The server's time on the clouds goes to the log ("Smoke server cost").
final class SmokeCloudStand {
    static final String DIR = System.getProperty("ntmgunfeel.smokeStand", "");
    static final boolean ENABLED = !DIR.isBlank();
    private static final int Y = 100;
    private static final String[] QUALITIES = {"off", "slices", "low", "medium", "high", "ultra"};

    // sky: 0 the ordinary poses; 1 and 2 the clouds in the air at the height of the sky's own clouds, seen against them
    // (1: the sky's clouds on, 2: off; same camera, so the pair tells where a sky cloud shows through the smoke)
    private record Pose(String name, double x, double y, double z, float yaw, float pitch, boolean wall, int sky) {
        Pose(String name, double x, double y, double z, float yaw, float pitch, boolean wall) {
            this(name, x, y, z, yaw, pitch, wall, 0);
        }
    }

    private static final int SKY_Y = 188;
    private static final List<Pose> POSES = poses(List.of(
            new Pose("outside", 0.5, Y + 3.2, 4.5, 0F, 8F, false),
            new Pose("inside", 6.5, Y + 1.6, 30.5, 0F, 0F, false),
            new Pose("inside-y135-p30", 6.5, Y + 1.6, 30.5, 135F, 30F, false),
            new Pose("inside-y-135-p-30", 6.5, Y + 1.6, 30.5, -135F, -30F, false),
            new Pose("wall", -59.5, Y + 1.6, 8.5, 0F, -5F, true),
            new Pose("sky", 0.5, SKY_Y - 6, 4.5, 0F, -16F, false, 1),
            new Pose("skyoff", 0.5, SKY_Y - 6, 4.5, 0F, -16F, false, 2),
            new Pose("skyoff2", 0.5, SKY_Y - 6, 4.5, 0F, -16F, false, 2)));

    // -PsmokePoses=a,b: only the poses whose names start with one of these
    private static List<Pose> poses(List<Pose> all) {
        String only = System.getProperty("ntmgunfeel.smokePoses", "");
        if (only.isBlank()) return all;
        List<Pose> out = new ArrayList<>();
        for (Pose p : all) for (String s : only.split(",")) if (p.name.startsWith(s.trim())) {
            out.add(p);
            break;
        }
        return out;
    }

    private static int pose, quality = -1, frames, phase;
    private static long last, spawnedAt;
    private static long[] cardAt = {0, 0};
    private static boolean reference = true;
    private static final List<Long> TIMES = new ArrayList<>();
    private static final List<String> ROWS = new ArrayList<>();
    private static String savedQuality;
    private static int savedMax;
    private static net.minecraft.client.CloudStatus savedClouds;

    private SmokeCloudStand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.options.enableVsync().set(false);
        mc.options.framerateLimit().set(260);
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        savedQuality = Config.smokeQuality;
        savedMax = Config.smokeMaxClouds;
        savedClouds = mc.options.cloudStatus().get();
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon");
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "weather clear");
            var rules = level.getGameRules();
            rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
            rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER, false, server);
            rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server);
            for (int x = -80; x <= 60; x++) for (int z = -12; z <= 64; z++) {
                level.setBlock(new BlockPos(x, Y - 1, z), Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.LIME).defaultBlockState(), 2);
                for (int y = Y; y <= Y + 16; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            // the wall: x -70..-50 at z 20, 6 high; its cloud just behind it
            for (int x = -70; x <= -50; x++) for (int y = Y; y < Y + 6; y++) level.setBlock(new BlockPos(x, y, 20), Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.RED).defaultBlockState(), 2);
            var p = server.getPlayerList().getPlayers().getFirst();
            p.setGameMode(GameType.SPECTATOR);
        }).join();
        try {
            Files.createDirectories(Path.of(DIR));
        } catch (Exception e) {
            ClientSmoke.check(false, "дым: папка " + DIR + ": " + e);
        }
        ROWS.add("pose\tquality\tclouds_drawn\tframe_ms\tp95_ms\tfps\tcard_ms\tat_camera");
        phase = 0;
        last = System.nanoTime();
    }

    private static void spawn(Pose p) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            endAll(level);
            Vec3 dir = new Vec3(0, -0.3, 1).normalize();
            if (p.wall) SmokeClouds.spawn(level, new Vec3(-59.5, Y + 0.5, 24.5), dir, SmokeKind.SMOKE, 1F);
            else for (int i = 0; i < 8; i++) SmokeClouds.spawn(level, new Vec3(-41.5 + 12 * i, (p.sky > 0 ? SKY_Y : Y) + 0.5, 30.5), dir, SmokeKind.SMOKE, 1F);
        }).join();
        spawnedAt = System.nanoTime();
    }

    private static void clear() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> endAll(server.overworld())).join();
    }

    private static void endAll(ServerLevel level) {
        SmokeClouds all = SmokeClouds.get(level);
        if (all != null) for (var c : all.clouds()) {
            all.remove(c.id);
            SmokeNet.ended(level, c);
        }
    }

    private static void place(Pose p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.setPos(p.x, p.y - mc.player.getEyeHeight(), p.z);
        mc.player.setYRot(p.yaw);
        mc.player.setXRot(p.pitch);
        mc.player.yRotO = p.yaw;
        mc.player.xRotO = p.pitch;
        mc.player.setDeltaMovement(Vec3.ZERO);
    }

    private static void teleport(Pose p) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            var sp = server.getPlayerList().getPlayers().getFirst();
            sp.teleportTo(server.overworld(), p.x, p.y - sp.getEyeHeight(), p.z, Set.of(), p.yaw, p.pitch, false);
        }).join();
    }

    static void frame() {
        long now = System.nanoTime();
        long dt = now - last;
        last = now;
        Pose p = pose < POSES.size() ? POSES.get(pose) : null;
        if (p != null) place(p);
        switch (phase) {
            case 0 -> {
                // a pose begins: reference without clouds, then the clouds at each quality
                if (p == null) {
                    sightStart();
                    phase = 10;
                    return;
                }
                teleport(p);
                // the sky's own clouds: on for the pose that looks for them, off for its pair
                Minecraft.getInstance().options.cloudStatus().set(p.sky == 2 ? net.minecraft.client.CloudStatus.OFF : net.minecraft.client.CloudStatus.FANCY);
                Config.smokeMaxClouds = 16;
                clear();
                reference = true;
                quality = -1;
                frames = 0;
                phase = 1;
            }
            case 1 -> {
                // the reference waits until the fog of the last clouds is gone; then settle, then 120 frames
                SmokeClouds known = SmokeClient.current();
                if (reference && (SmokeFog.atCamera() > 0.002F || known != null && !known.clouds().isEmpty())) {
                    frames = 0;
                    return;
                }
                frames++;
                if (frames == 61) cardAt = new long[]{SmokeStats.cardNanos, SmokeStats.cardFrames};
                if (frames > 60) TIMES.add(dt);
                if (frames == 660) {
                    shoot(p, reference ? "none" : QUALITIES[quality]);
                    row(p, reference ? "none" : QUALITIES[quality]);
                    TIMES.clear();
                    frames = 0;
                    phase = 2;
                }
            }
            case 2 -> {
                // the shot is taken a few frames after it was asked for; then the next quality
                if (++frames < 10) return;
                if (reference) {
                    reference = false;
                    spawn(p);
                    phase = 3;
                    frames = 0;
                    return;
                }
                quality++;
                // the sky poses only at high
                if (p.sky > 0) quality = quality <= 4 ? 4 : QUALITIES.length;
                if (quality >= QUALITIES.length) {
                    pose++;
                    phase = 0;
                    return;
                }
                nextQuality(p);
            }
            case 3 -> {
                // the clouds grow for 3.5 s (dense, before they thin at ~22 s)
                if ((now - spawnedAt) / 1e9 < 3.5) return;
                SmokeClouds all = SmokeClient.current();
                int n = all == null ? 0 : all.clouds().size();
                if (n < (p.wall ? 1 : 8) && (now - spawnedAt) / 1e9 < 10) return;
                if (n < (p.wall ? 1 : 8)) ClientSmoke.check(false, "дым: клиент получил " + n + " облаков");
                quality = p.sky > 0 ? 4 : 0;
                nextQuality(p);
            }
            case 10 -> {
                // the key is read from the file at most once a second
                if ((now - sightAt) / 1e9 < 1.5) return;
                sightEnd();
                done();
            }
            default -> {
            }
        }
    }

    // smokeBlocksMobSight (SmokeSight): a zombie and a villager with a dense cloud between them, the key off and on
    private static long sightAt;
    private static boolean seenOff, seenNear;
    private static net.minecraft.world.entity.Mob zombie;
    private static net.minecraft.world.entity.LivingEntity villager;
    private static final Path SERVER_FILE = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(ReaimTimes.FILE);

    private static void sightStart() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            endAll(level);
            int id = SmokeClouds.spawn(level, new Vec3(6.5, Y + 0.5, 30.5), new Vec3(0, -0.3, 1).normalize(), SmokeKind.SMOKE, 1F);
            SmokeClouds.Cloud c = SmokeClouds.get(level).cloud(id);
            for (int i = 0; i < 100; i++) c.grid.step();
            zombie = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            villager = net.minecraft.world.entity.EntityTypes.VILLAGER.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            zombie.setNoAi(true);
            ((net.minecraft.world.entity.Mob) villager).setNoAi(true);
            zombie.setPos(6.5, Y, 18.5);
            villager.setPos(6.5, Y, 42.5);
            level.addFreshEntity(zombie);
            level.addFreshEntity(villager);
            seenOff = zombie.hasLineOfSight(villager);
            try {
                Files.writeString(SERVER_FILE, SmokeSight.KEY + " = true\n");
            } catch (Exception e) {
                ClientSmoke.check(false, "дым: файл сервера " + e);
            }
        }).join();
        sightAt = System.nanoTime();
    }

    private static void sightEnd() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        boolean[] seen = new boolean[2];
        server.submit(() -> {
            seen[0] = zombie.hasLineOfSight(villager);
            villager.setPos(6.5, Y, 20.5);
            seenNear = zombie.hasLineOfSight(villager);
            zombie.discard();
            villager.discard();
            try {
                Files.deleteIfExists(SERVER_FILE);
            } catch (Exception e) {
                ClientSmoke.check(false, "дым: файл сервера " + e);
            }
            endAll(server.overworld());
        }).join();
        ClientSmoke.check(seenOff, "дым: без ключа зомби видит жителя сквозь облако");
        ClientSmoke.check(!seen[0], "дым: с smokeBlocksMobSight зомби не видит жителя сквозь облако");
        ClientSmoke.check(seenNear, "дым: жителя в двух блоках зомби видит и в дыму");
        ROWS.add("# sight: key off " + seenOff + ", key on " + seen[0] + ", 2 blocks away " + seenNear);
    }

    private static void nextQuality(Pose p) {
        // a cloud thins after ~22 s: a fresh set when the old one is 14 s old
        if ((System.nanoTime() - spawnedAt) / 1e9 > 14) {
            spawn(p);
            phase = 3;
            frames = 0;
            return;
        }
        Config.smokeQuality = QUALITIES[quality];
        frames = 0;
        phase = 1;
    }

    private static void row(Pose p, String q) {
        List<Long> t = new ArrayList<>(TIMES);
        t.sort(Long::compare);
        double avg = t.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
        double p95 = t.isEmpty() ? 0 : t.get(Math.min(t.size() - 1, (int) (t.size() * 0.95))) / 1e6;
        int drawn = Math.max(acup.gunfeel.smoke.client.SmokeAtlas.drawn().size(), acup.gunfeel.smoke.client.SmokeSlices.drawnLast());
        long cardFrames = SmokeStats.cardFrames - cardAt[1];
        double card = cardFrames > 0 ? (SmokeStats.cardNanos - cardAt[0]) / 1e6 / cardFrames : 0;
        String r = String.format(Locale.ROOT, "%s\t%s\t%d\t%.3f\t%.3f\t%.0f\t%.3f\t%.2f", p.name, q, q.equals("none") || q.equals("off") ? 0 : drawn, avg, p95,
                avg > 0 ? 1000 / avg : 0, card, SmokeFog.atCamera());
        ROWS.add(r);
        GunFeel.LOGGER.info("[smokestand] {}", r);
    }

    private static void shoot(Pose p, String q) {
        Path file = Path.of(DIR, p.name + "-" + q + ".png");
        net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(file);
            } catch (Exception e) {
                GunFeel.LOGGER.error("[smokestand] {}", file, e);
            }
        });
    }

    private static void done() {
        Config.smokeQuality = savedQuality;
        Config.smokeMaxClouds = savedMax;
        Minecraft.getInstance().options.cloudStatus().set(savedClouds);
        clear();
        ROWS.add("# client: " + SmokeStats.last);
        ROWS.add("# server: " + SmokeClouds.serverCost());
        ROWS.add("# checks: matched " + SmokeClient.matched + ", apart " + SmokeClient.apart + ", grids " + SmokeClient.grids);
        try {
            Files.write(Path.of(DIR, "frames.tsv"), ROWS);
        } catch (Exception e) {
            ClientSmoke.check(false, "дым: запись " + e);
        }
        ClientSmoke.check(SmokeClient.matched > 0, "дым: хеши клиента сошлись с сервером " + SmokeClient.matched + " раз, разошлись " + SmokeClient.apart);
        ClientSmoke.finish();
    }
}
