package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// runCheckClient -PholeFrames=<dir>: bullet holes' frames, the same in every run, for the render port
// (notes/ports-base/render-step1.md §5): a frame on the base and on the branch must be the same pixel for pixel.
// A row 12 blocks ahead of the camera: stone (the holes kept in batches), iron bars (two-sided), a glass block and a
// glass pane (late holes, and their copy under the glass seen from behind); behind the row's gap an NTM door (holes cut
// from a drawn model, on a part a renderer draws). The holes are made where a ray from the camera meets the blocks, as
// a bullet's (Tracers.standHole: its turn and variant from the shot's number), without particles or sound; the mod's
// clock held (GunClock.use), so a fading hole has the same alpha in every run. Frames, each two frames after its change
// (a buffer baked anew is drawn by then): 0 the scene without holes; A the holes, one fading; B a hole more in stone's open batch and another
// gone over to fading (a batch made anew); C a second later, both fainter. Poses: front, and back (behind the glass and
// the bars). <dir>/<pose>-<frame>.png, <dir>/frames.tsv (the holes, the mode, then 600 frames' ms and bytes allocated a
// frame on the render thread). -PsmokeFabulous / =chain: improved transparency with CrankShaft's OIT / the vanilla chain.
final class HoleFrameStand {
    static final String DIR = System.getProperty("ntmgunfeel.holeFrames", "");
    static final boolean ENABLED = !DIR.isBlank();
    private static final int Y = 100, ROW_Z = 6, DOOR_Z = 14;
    private static final long T0 = 1_000_000_000_000L, SECOND = 1_000_000_000L;
    private static final float LIFE = 30F;
    private static final String[] FRAMES = {"0", "A", "B", "C"};
    // clock of each frame from T0: 0 before any hole; the fading hole F (glass block: late, out of its batch when it
    // fades) is born at 0, G (iron bars: two-sided) at 1 s, the rest at 10 s, N (stone's open batch) just before B
    private static final double[] AT = {0, LIFE - 2.5, LIFE - 1.5, LIFE - 0.5};
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    private static final int MEASURE = 600;

    private record Pose(String name, double x, double y, double z, float yaw, float pitch) {
    }

    private static final Pose FRONT = new Pose("front", 0.5, Y + 2.1, -6.5, 0F, 4F);
    private static final Pose BACK = new Pose("back", 2.5, Y + 2.1, 10.5, 180F, 4F);
    private static final Pose[] POSES = {FRONT, BACK};

    private static volatile long clock = T0;
    private static int phase, wait, frame, pose, shots, expected, measured;
    private static boolean added;
    private static long measureFrom, allocFrom, last, readyAt;
    private static boolean rebuilt;
    private static final List<Long> TIMES = new ArrayList<>();
    private static final List<String> ROWS = new ArrayList<>();

    private HoleFrameStand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.options.enableVsync().set(false);
        String fabulous = System.getProperty("ntmgunfeel.smokeFabulous", "false");
        if (!fabulous.equals("false")) mc.options.improvedTransparency().set(true);
        if (fabulous.equals("chain")) flywheelTerrainOff();
        mc.options.framerateLimit().set(260);
        // the sky's clouds move with the ticks: off, so a frame differs only where the holes do
        mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
        // the scene's far corners (some 50 blocks off) well inside the chunks drawn: at the edge Sodium drew them lit
        // in one run and dark in the next
        mc.options.renderDistance().set(8);
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        Config.bulletHoles = true;
        Config.holeCache = true;
        Config.holeLife = LIFE;
        Config.tracers = false;
        GunClock.use(() -> clock);
        Rng.seed(71294);
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
            com.hbm.blocks.multiblock.BlockMultiblockCore.withoutTeardown(() -> {
                for (int x = -40; x <= 40; x++) for (int z = -12; z <= 22; z++) {
                    level.setBlock(new BlockPos(x, Y - 1, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                    for (int y = Y; y <= Y + 12; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            });
            // walls behind the scene from both poses, past the edges of the view: the land beyond (loaded as it comes)
            // never in the frame
            var backdrop = Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.LIGHT_GRAY).defaultBlockState();
            for (int x = -40; x <= 40; x++) for (int y = Y; y <= Y + 12; y++) {
                level.setBlock(new BlockPos(x, y, 22), backdrop, 2);
                level.setBlock(new BlockPos(x, y, -12), backdrop, 2);
            }
            for (int y = Y; y <= Y + 4; y++) {
                for (int x = -9; x <= -6; x++) level.setBlock(new BlockPos(x, y, ROW_Z), Blocks.STONE.defaultBlockState(), 3);
                for (int x = -5; x <= -3; x++) level.setBlock(new BlockPos(x, y, ROW_Z), Blocks.IRON_BARS.defaultBlockState(), 3);
                for (int x = 3; x <= 4; x++) level.setBlock(new BlockPos(x, y, ROW_Z), Blocks.GLASS.defaultBlockState(), 3);
                for (int x = 5; x <= 7; x++) level.setBlock(new BlockPos(x, y, ROW_Z), Blocks.GLASS_PANE.defaultBlockState(), 3);
            }
            // the NTM door, placed as a player does (its multiblock), as HoleBenchmark does
            var placement = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("hbm:secure_access_door")));
            p.setItemInHand(InteractionHand.MAIN_HAND, placement);
            BlockPos target = new BlockPos(0, Y - 1, DOOR_Z);
            placement.useOn(new UseOnContext(p, InteractionHand.MAIN_HAND, new BlockHitResult(new Vec3(0.5, Y, DOOR_Z + 0.5), Direction.UP, target, false)));
            p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            ClientSmoke.check(level.getBlockEntity(target.above()) != null, "дыры: дверь NTM поставлена");
            p.setGameMode(GameType.SPECTATOR);
        }).join();
        try {
            Files.createDirectories(Path.of(DIR));
        } catch (Exception e) {
            ClientSmoke.check(false, "дыры: папка " + DIR + ": " + e);
        }
        ROWS.add("frame\tpose\tholes\tmode\tshaderTransparency");
        phase = 0;
        // the light of the blocks just set reaches the client, and the chunks are drawn anew, before the first frame
        readyAt = System.nanoTime() + 10_000_000_000L;
        rebuilt = false;
    }

    // -PsmokeFabulous=chain: Flywheel's terrain mode OFF for this run only (as SmokeCloudStand does it)
    private static void flywheelTerrainOff() {
        try {
            Class<?> cfg = Class.forName("dev.engine_room.flywheel.impl.NeoForgeFlwConfig");
            Object client = cfg.getField("client").get(cfg.getField("INSTANCE").get(null));
            Object value = client.getClass().getField("terrainMode").get(client);
            Class<?> mode = Class.forName("dev.engine_room.flywheel.backend.TerrainMode");
            value.getClass().getMethod("set", Object.class).invoke(value, mode.getField("OFF").get(null));
            GunFeel.LOGGER.info("[holeframes] Flywheel terrain mode OFF");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            ClientSmoke.check(false, "дыры: Flywheel terrain OFF не поставлен: " + e);
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

    // a hole where the ray from the front camera through (x, y, z) meets a block, its shot's number the next
    private static void shoot(double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 from = new Vec3(FRONT.x, FRONT.y, FRONT.z), to = new Vec3(x, y, z);
        Vec3 dir = to.subtract(from).normalize();
        BlockHitResult hit = mc.level.clip(new ClipContext(from, from.add(dir.scale(40)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        expected++;
        if (hit.getType() != HitResult.Type.BLOCK) {
            ClientSmoke.check(false, "дыры: луч в " + to + " ни во что не попал");
            return;
        }
        Tracers.standHole(mc.level, hit, com.hbm.items.weapon.sedna.factory.XFactory10ga.g10, dir, 900_000 + shots++);
    }

    private static void at(double seconds) {
        clock = T0 + Math.round(seconds * SECOND);
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
                // frame 0: the scene without holes
                // the sections drawn anew once the light is in (under load Sodium kept a section built before it, dark)
                if (!rebuilt && System.nanoTime() > readyAt - 4_000_000_000L) {
                    rebuilt = true;
                    mc.levelRenderer.invalidateCompiledGeometry(mc.level, mc.options, mc.gameRenderer.mainCamera(), mc.getBlockColors());
                }
                if (System.nanoTime() < readyAt) return;
                frame = 0;
                pose = 0;
                phase = 4;
            }
            case 10 -> {
                // F at 0 on the glass block, G at 1 s on the bars
                at(0);
                shoot(4.1, Y + 1.25, ROW_Z);
                phase = 11;
                wait = 3;
            }
            case 11 -> {
                at(1);
                shoot(-4.9, Y + 1.5, ROW_Z);
                phase = 12;
                wait = 3;
            }
            case 12 -> {
                at(10);
                for (double y = Y + 0.6; y < Y + 4.5; y += 1.3) {
                    shoot(-6.6, y, ROW_Z);
                    shoot(-4.3, y + 0.4, ROW_Z);
                    shoot(-3.6, y, ROW_Z);
                    shoot(3.5, y + 0.2, ROW_Z);
                    shoot(4.6, y + 0.5, ROW_Z);
                    shoot(5.5, y, ROW_Z);
                    shoot(6.8, y + 0.3, ROW_Z);
                }
                // the door behind the row's gap
                for (double x = -1.2; x <= 1.3; x += 1.2) for (double y = Y + 0.8; y < Y + 3.5; y += 1.1) shoot(x, y, DOOR_Z);
                phase = 13;
                wait = 10;
            }
            case 13 -> {
                int holes = Tracers.holeCount();
                ClientSmoke.check(holes >= expected && Tracers.pendingHoles() == 0, "дыры: сделано " + holes + " на " + expected + " выстрелов (на двери по дыре на часть)");
                pose = 0;
                phase = 4;
            }
            case 4 -> {
                // a frame: its time (B: and a hole more in stone's open batch first), then each pose
                if (pose == 0) {
                    at(AT[frame]);
                    if (frame == 2 && !added) {
                        added = true;
                        shoot(-7.7, Y + 0.8, ROW_Z);
                        wait = 4;
                        return;
                    }
                }
                teleport(POSES[pose]);
                place(POSES[pose]);
                phase = 5;
                wait = 3;
            }
            case 5 -> {
                Path file = Path.of(DIR, POSES[pose].name + "-" + FRAMES[frame] + ".png");
                net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
                    try (image) {
                        image.writeToFile(file);
                    } catch (Exception e) {
                        ClientSmoke.check(false, "дыры: " + file + ": " + e);
                    }
                });
                ROWS.add(String.format(Locale.ROOT, "%s\t%s\t%d\t%s\t%b", FRAMES[frame], POSES[pose].name, Tracers.holeCount(),
                        System.getProperty("ntmgunfeel.smokeFabulous", "false"), mc.gameRenderer.gameRenderState().useShaderTransparency()));
                pose++;
                if (pose == POSES.length) {
                    pose = 0;
                    frame++;
                }
                phase = frame == FRAMES.length ? 6 : frame == 1 && pose == 0 && shots == 0 ? 10 : 4;
                wait = 2;
            }
            case 6 -> {
                // the cost: 600 frames of scene A from the front, the clock held
                at(AT[1]);
                teleport(FRONT);
                pose = 0;
                phase = 7;
                wait = 20;
            }
            case 7 -> {
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
                ROWS.add(String.format(Locale.ROOT, "# %d frames: %.3f ms a frame, p95 %.3f ms, %d bytes allocated a frame (render thread)", MEASURE, avg, p95,
                        (bytes - allocFrom) / MEASURE));
                done();
            }
            default -> {
            }
        }
    }

    private static void done() {
        GunClock.use(null);
        try {
            Files.write(Path.of(DIR, "frames.tsv"), ROWS);
        } catch (Exception e) {
            ClientSmoke.check(false, "дыры: запись " + e);
        }
        for (String r : ROWS) GunFeel.LOGGER.info("[holeframes] {}", r);
        ClientSmoke.finish();
    }
}
