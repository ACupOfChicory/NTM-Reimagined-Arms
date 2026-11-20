package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

// runCheckClient -PcasingFrames=<dir>: NTM's spent casings' frames, the same in every run, for the render port step 2
// (notes/ports-base/render-step2.md): a frame on the base and on the branch must be the same pixel for pixel.
// The casings are NTM's, laid as FireStand.lay lays them (NTM's ParticleSpentCasing at a point, no speed, no spin, a
// turn of its own), on a floor before the camera; each scene laid anew, then 3 s for them to land and sleep:
//   0    none (where the scene itself differs run to run, as with the owner's mods: the mask of the comparison)
//   64   64 of one kind: its parts' draws one full block of 64 each
//   65   65 of it: a block more, of one
//   mix  the 65 and 47 of every kind by turns: the parts of many kinds, blocks of all sizes
// Poses: front (looking down at them) and turned (yaw 135, pitch 30). Casings drawn whatever their number
// (Config.casingMax -1). <dir>/<pose>-<scene>.png, <dir>/frames.tsv (the casings each scene, the mode, then 600
// frames of mix: ms and bytes allocated a frame on the render thread). -PsmokeFabulous / =chain as HoleFrameStand.
final class CasingFrameStand {
    static final String DIR = System.getProperty("ntmgunfeel.casingFrames", "");
    static final boolean ENABLED = !DIR.isBlank();
    private static final int Y = 100;
    private static final String[] SCENES = {"0", "64", "65", "mix"};
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    private static final int MEASURE = 600;
    private static final long SETTLE = 3_000_000_000L;

    private record Pose(String name, double x, double y, double z, float yaw, float pitch) {
    }

    private static final Pose FRONT = new Pose("front", 0.5, Y + 2.6, -2.0, 0F, 40F);
    private static final Pose TURNED = new Pose("turned", 3.4, Y + 2.4, 4.4, 135F, 30F);
    private static final Pose[] POSES = {FRONT, TURNED};

    private static int phase, wait, scene, pose, measured;
    private static long measureFrom, allocFrom, last, readyAt, settledAt;
    private static boolean rebuilt;
    private static final List<Long> TIMES = new ArrayList<>();
    private static final List<String> ROWS = new ArrayList<>();

    private CasingFrameStand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.options.enableVsync().set(false);
        String fabulous = System.getProperty("ntmgunfeel.smokeFabulous", "false");
        if (!fabulous.equals("false")) mc.options.improvedTransparency().set(true);
        if (fabulous.equals("chain")) flywheelTerrainOff();
        mc.options.framerateLimit().set(260);
        mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
        mc.options.renderDistance().set(8);
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        Config.casingMax = -1;
        Config.tracers = false;
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            var quiet = server.createCommandSourceStack().withSuppressedOutput();
            server.getCommands().performPrefixedCommand(quiet, "time set noon");
            server.getCommands().performPrefixedCommand(quiet, "weather clear");
            var rules = level.getGameRules();
            rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
            rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER, false, server);
            rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server);
            var p = server.getPlayerList().getPlayers().getFirst();
            p.setGameMode(GameType.CREATIVE);
            for (int x = -40; x <= 40; x++) for (int z = -12; z <= 22; z++) {
                level.setBlock(new BlockPos(x, Y - 1, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = Y; y <= Y + 12; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            // walls past the edges of the view, as HoleFrameStand's: the land beyond never in the frame
            var backdrop = Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.LIGHT_GRAY).defaultBlockState();
            for (int x = -40; x <= 40; x++) for (int y = Y; y <= Y + 12; y++) {
                level.setBlock(new BlockPos(x, y, 22), backdrop, 2);
                level.setBlock(new BlockPos(x, y, -12), backdrop, 2);
            }
            for (int z = -12; z <= 22; z++) for (int y = Y; y <= Y + 12; y++) {
                level.setBlock(new BlockPos(-40, y, z), backdrop, 2);
                level.setBlock(new BlockPos(40, y, z), backdrop, 2);
            }
            p.setGameMode(GameType.SPECTATOR);
        }).join();
        try {
            Files.createDirectories(Path.of(DIR));
        } catch (Exception e) {
            ClientSmoke.check(false, "гильзы: папка " + DIR + ": " + e);
        }
        ROWS.add("scene\tpose\tcasings\tmode\tshaderTransparency");
        phase = 0;
        readyAt = System.nanoTime() + 10_000_000_000L;
        rebuilt = false;
    }

    private static void flywheelTerrainOff() {
        try {
            Class<?> cfg = Class.forName("dev.engine_room.flywheel.impl.NeoForgeFlwConfig");
            Object client = cfg.getField("client").get(cfg.getField("INSTANCE").get(null));
            Object value = client.getClass().getField("terrainMode").get(client);
            Class<?> mode = Class.forName("dev.engine_room.flywheel.backend.TerrainMode");
            value.getClass().getMethod("set", Object.class).invoke(value, mode.getField("OFF").get(null));
            GunFeel.LOGGER.info("[casingframes] Flywheel terrain mode OFF");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            ClientSmoke.check(false, "гильзы: Flywheel terrain OFF не поставлен: " + e);
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

    // the scene's casings, laid anew: n of the first kind (by name) in rows of 13, 0.25 apart, then `mixed` of every
    // kind by turns in rows further on
    private static int lay(int n, int mixed) {
        Minecraft mc = Minecraft.getInstance();
        mc.particleEngine.clearParticles();
        var kinds = new ArrayList<>(com.hbm.particle.SpentCasing.casingMap.values());
        kinds.sort(java.util.Comparator.comparing(com.hbm.particle.SpentCasing::getName));
        for (int i = 0; i < n + mixed; i++) {
            var config = i < n ? kinds.get(0) : kinds.get(((i - n) * 7) % kinds.size());
            int row = i / 13, col = i % 13;
            double x = -1.0 + col * 0.25 + (row % 2) * 0.12, z = 1.0 + row * 0.3;
            var c = new com.hbm.particle.ParticleSpentCasing(mc.level, x, Y + 0.05, z, 0, 0, 0, 0F, 0F, config, false, 0, 0, 0);
            c.rotationYaw = c.prevRotationYaw = i * 37F;
            c.rotationPitch = c.prevRotationPitch = i * 23F;
            mc.particleEngine.add(c);
        }
        return n + mixed;
    }

    private static int laid;

    private static void layScene(int s) {
        laid = switch (s) {
            case 0 -> lay(0, 0);
            case 1 -> lay(64, 0);
            case 2 -> lay(65, 0);
            default -> lay(65, 47);
        };
        settledAt = System.nanoTime() + SETTLE;
    }

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        Pose p = POSES[pose];
        place(p);
        if (wait > 0) {
            wait--;
            return;
        }
        switch (phase) {
            case 0 -> {
                if (!rebuilt && System.nanoTime() > readyAt - 4_000_000_000L) {
                    rebuilt = true;
                    mc.levelRenderer.invalidateCompiledGeometry(mc.level, mc.options, mc.gameRenderer.mainCamera(), mc.getBlockColors());
                }
                if (System.nanoTime() < readyAt) return;
                scene = 0;
                pose = 0;
                phase = 3;
            }
            case 3 -> {
                // a scene laid, its casings landed and asleep
                layScene(scene);
                phase = 4;
            }
            case 4 -> {
                if (pose == 0 && System.nanoTime() < settledAt) return;
                teleport(POSES[pose]);
                place(POSES[pose]);
                phase = 5;
                wait = 3;
            }
            case 5 -> {
                Path file = Path.of(DIR, POSES[pose].name + "-" + SCENES[scene] + ".png");
                net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
                    try (image) {
                        image.writeToFile(file);
                    } catch (Exception e) {
                        ClientSmoke.check(false, "гильзы: " + file + ": " + e);
                    }
                });
                ROWS.add(String.format(Locale.ROOT, "%s\t%s\t%d\t%s\t%b", SCENES[scene], POSES[pose].name, laid,
                        System.getProperty("ntmgunfeel.smokeFabulous", "false"), mc.gameRenderer.gameRenderState().useShaderTransparency()));
                ClientSmoke.check(laid == 0 || CasingCull.drawn > 0 || POSES[pose] != FRONT, "гильзы: " + SCENES[scene] + " — тел нарисовано " + CasingCull.drawn + " из " + CasingCull.alive);
                pose++;
                wait = 2;
                if (pose < POSES.length) {
                    phase = 4;
                    return;
                }
                pose = 0;
                scene++;
                phase = scene == SCENES.length ? 6 : 3;
            }
            case 6 -> {
                // the cost: 600 frames of mix from the front, laid anew
                layScene(3);
                teleport(FRONT);
                pose = 0;
                phase = 7;
            }
            case 7 -> {
                if (System.nanoTime() < settledAt) return;
                long now = System.nanoTime();
                long bytes = THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId());
                if (measured == 0) {
                    measureFrom = now;
                    allocFrom = bytes;
                } else TIMES.add(now - last);
                last = now;
                if (++measured <= MEASURE) return;
                TIMES.sort(null);
                double avg = (now - measureFrom) / 1e6 / MEASURE;
                double p95 = TIMES.get((int) (TIMES.size() * 0.95)) / 1e6;
                ROWS.add(String.format(Locale.ROOT, "# %d frames: %.3f ms a frame, p95 %.3f ms, %d bytes allocated a frame (render thread), %d casings drawn",
                        MEASURE, avg, p95, (bytes - allocFrom) / MEASURE, CasingCull.drawn));
                done();
            }
            default -> {
            }
        }
    }

    private static void done() {
        try {
            Files.write(Path.of(DIR, "frames.tsv"), ROWS);
        } catch (Exception e) {
            ClientSmoke.check(false, "гильзы: запись " + e);
        }
        for (String r : ROWS) GunFeel.LOGGER.info("[casingframes] {}", r);
        ClientSmoke.finish();
    }
}
