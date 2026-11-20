package acup.gunfeel;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;

import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeKind;
import acup.gunfeel.smoke.client.SmokeClient;
import acup.gunfeel.smoke.client.SmokeRender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

// runCheckClient -PsmokeFrames=<dir>: the smoke's frames, the same in every run, base against branch (the render port's
// step 3, notes/ports-base/render-step3.md, and the fix of SmokeInfo's matrices). The clouds' every input held: the
// game's ticks frozen before they are let out, its game time set (the wind a cloud takes is the level's at its birth),
// the level's random seeded before each (the cloud's own seed), the client's clouds stepped by the client to a time it
// is set to, the atlas made again from them; the noise's time held (SmokeRender.standSeconds). Three clouds: two whose boxes meet (one group) and one apart. Shots at high and low
// quality: outside before them, inside one (the march a step coarser), turned (yaw 135, pitch 30), and the scene with no
// clouds ("0", the mask of a run with mods). <dir>/<quality>-<pose>.png, <dir>/frames.tsv (the clouds, the groups
// marched, then 600 frames at high from the front: ms and bytes allocated a frame on the render thread).
// -PsmokeFabulous / =chain as HoleFrameStand.
final class SmokeFrameStand {
    static final String DIR = System.getProperty("ntmgunfeel.smokeFrames", "");
    static final boolean ENABLED = !DIR.isBlank();
    private static final int Y = 100;
    private static final int MIN_X = -40, MAX_X = 40, MIN_Z = -12, MAX_Z = 50;
    private static final int SETTLED_FRAMES = 20;
    private static final long SETTLE_TIMEOUT = 120_000_000_000L;
    private static final long GAME_TIME = 1_000_000L, SEED = 20261120L;
    private static final int STEPS = 60;
    private static final String[] QUALITIES = {"high", "low"};
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
    private static final int MEASURE = 600;

    private record Pose(String name, double x, double y, double z, float yaw, float pitch) {
    }

    private static final Pose FRONT = new Pose("front", 0.5, Y + 3.0, -4.0, 0F, 12F);
    private static final Pose[] POSES = {FRONT, new Pose("inside", 0.5, Y + 2.0, 14.5, 20F, 5F),
            new Pose("yaw+135-pitch+30", 18.5, Y + 9.0, 30.5, 135F, 30F),
            new Pose("yaw-135-pitch-30", -18.5, Y + 2.0, 30.5, -135F, -30F)};
    private static final String[] SCENES = {"one", "meeting", "apart", "three", "glass"};
    private static int scene, lightReady;
    private static int expectedClouds() {
        return scene == 0 || scene == 4 ? 1 : scene == 3 ? 3 : 2;
    }

    private static int phase, wait, quality, pose, measured, frames;
    private static long measureFrom, allocFrom, last, readyAt;
    private static boolean rebuilt, clouds;
    private static long settleFrom;
    private static Field lightUpdates, dispatcherPool, poolBuffers;
    private static Map<SectionRenderDispatcher.RenderSection, SectionMesh> settledMeshes = new IdentityHashMap<>();
    private static final List<Long> TIMES = new ArrayList<>();
    private static final List<String> ROWS = new ArrayList<>();

    private SmokeFrameStand() {
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
        Config.smokeMaxClouds = 16;
        Config.smokeQuality = "high";
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
            endAll(level);
            // no mob in the frame: one may have spawned with the level before the rule (a slime wandered into a run)
            var others = new java.util.ArrayList<net.minecraft.world.entity.Entity>();
            for (var en : level.getAllEntities()) if (!(en instanceof net.minecraft.world.entity.player.Player)) others.add(en);
            others.forEach(net.minecraft.world.entity.Entity::discard);
            var p = server.getPlayerList().getPlayers().getFirst();
            p.setGameMode(GameType.CREATIVE);
            for (int x = MIN_X; x <= MAX_X; x++) for (int z = MIN_Z; z <= MAX_Z; z++) {
                level.setBlock(new BlockPos(x, Y - 1, z), Blocks.SMOOTH_STONE.defaultBlockState(), 2);
                for (int y = Y; y <= Y + 24; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
            // A closed perimeter up to the world's inclusive maxY hides all distant terrain from every pose inside
            // it, regardless of chunk arrival. No roof: every camera and cloud keeps its direct vertical skylight.
            // LevelHeightAccessor.getMaxY() (26.2) is minY + height - 1.
            var backdrop = Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.LIGHT_GRAY).defaultBlockState();
            for (int x = MIN_X; x <= MAX_X; x++) for (int y = Y; y <= level.getMaxY(); y++) {
                level.setBlock(new BlockPos(x, y, MAX_Z), backdrop, 2);
                level.setBlock(new BlockPos(x, y, MIN_Z), backdrop, 2);
            }
            for (int z = MIN_Z; z <= MAX_Z; z++) for (int y = Y; y <= level.getMaxY(); y++) {
                level.setBlock(new BlockPos(MIN_X, y, z), backdrop, 2);
                level.setBlock(new BlockPos(MAX_X, y, z), backdrop, 2);
            }
            p.setGameMode(GameType.SPECTATOR);
        }).join();
        holdNoon();
        try {
            Files.createDirectories(Path.of(DIR));
        } catch (Exception e) {
            ClientSmoke.check(false, "дым: папка " + DIR + ": " + e);
        }
        ROWS.add("quality\tpose\tclouds\tgroups\tmode\tshaderTransparency\tglass");
        try {
            lightUpdates = access(ClientLevel.class, "lightUpdateQueue");
            dispatcherPool = access(SectionRenderDispatcher.class, "bufferPool");
            poolBuffers = access(SectionBufferBuilderPool.class, "freeBuffers");
        } catch (ReflectiveOperationException | RuntimeException e) {
            ClientSmoke.check(false, "дым: доступ к очередям готовности 26.2: " + e);
            done();
            return;
        }
        phase = 0;
        rebuilt = false;
        teleport(FRONT);
        place(FRONT);
    }

    private static void flywheelTerrainOff() {
        try {
            Class<?> cfg = Class.forName("dev.engine_room.flywheel.impl.NeoForgeFlwConfig");
            Object client = cfg.getField("client").get(cfg.getField("INSTANCE").get(null));
            Object value = client.getClass().getField("terrainMode").get(client);
            Class<?> mode = Class.forName("dev.engine_room.flywheel.backend.TerrainMode");
            value.getClass().getMethod("set", Object.class).invoke(value, mode.getField("OFF").get(null));
            GunFeel.LOGGER.info("[smokeframes] Flywheel terrain mode OFF");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            ClientSmoke.check(false, "дым: Flywheel terrain OFF не поставлен: " + e);
        }
    }

    private static void endAll(ServerLevel level) {
        SmokeClouds all = SmokeClouds.get(level);
        if (all != null) for (var c : all.clouds()) {
            all.remove(c.id);
            acup.gunfeel.smoke.SmokeNet.ended(level, c);
        }
    }

    private static void command(String c) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), c)).join();
    }

    // the clouds, every input held: the ticks frozen, the game time and the random's seed set before each. The server
    // does not step them: the client steps its own to the time it is set to (frozen, it keeps that time), first the
    // time of their birth, so that none is stepped before all three are there
    private static void letOut() {
        command("tick freeze");
        Minecraft.getInstance().level.setTimeFromServer(GAME_TIME);
        holdNoon();
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            endAll(level);
            ((net.minecraft.world.level.storage.ServerLevelData) level.getLevelData()).setGameTime(GAME_TIME);
            Vec3 dir = new Vec3(0, -0.3, 1).normalize();
            Vec3[] at = {new Vec3(0.5, Y + 0.5, 14.5), new Vec3(7.5, Y + 0.5, 17.5), new Vec3(-20.5, Y + 0.5, 30.5)};
            for (int i = 0; i < at.length; i++) {
                if (i >= expectedClouds()) break;
                if (scene == 2 && i == 1) at[i] = at[2];
                level.getRandom().setSeed(SEED + i);
                SmokeClouds.spawn(level, at[i], dir, SmokeKind.SMOKE, 1F);
            }
        }).join();
        synced = false;
    }

    private static boolean synced;

    // the client has the three clouds at their birth, then stepped to STEPS ticks
    private static boolean caughtUp() {
        Minecraft mc = Minecraft.getInstance();
        SmokeClouds all = SmokeClient.current();
        if (mc.level == null || all == null || all.clouds().size() != expectedClouds()) return false;
        if (!synced) {
            for (var c : all.clouds()) if (c.born != GAME_TIME || c.grid.age() != 0) {
                ClientSmoke.check(false, "дым: облако " + c.id + " родилось в " + c.born + ", возраст " + c.grid.age());
                done();
                return false;
            }
            mc.level.setTimeFromServer(GAME_TIME + STEPS);
            holdNoon();
            synced = true;
            return false;
        }
        // a time packet of the server's that came after it set the time back: set again
        if (mc.level.getGameTime() != GAME_TIME + STEPS) mc.level.setTimeFromServer(GAME_TIME + STEPS);
        holdNoon();
        for (var c : all.clouds()) if (c.catchingUp(mc.level.getGameTime()) || c.grid.age() != STEPS) return false;
        return true;
    }

    private static String state() {
        Minecraft mc = Minecraft.getInstance();
        SmokeClouds all = SmokeClient.current();
        var server = mc.getSingleplayerServer();
        StringBuilder b = new StringBuilder("время клиента ").append(mc.level == null ? -1 : mc.level.getGameTime()).append(", сервера ")
                .append(server.submit(() -> server.overworld().getGameTime()).join()).append(", шагает ").append(server.tickRateManager().isSteppingForward())
                .append(", заморожен ").append(server.tickRateManager().isFrozen()).append(", облака:");
        if (all != null) for (var c : all.clouds()) b.append(' ').append(c.grid.age()).append(c.catchingUp(mc.level.getGameTime()) ? "(догоняет)" : "");
        return b.toString();
    }

    private static String digest(SmokeClouds all) {
        StringBuilder b = new StringBuilder();
        if (all != null) for (var c : all.clouds())
            b.append(String.format(Locale.ROOT, "[%d at %d,%d,%d born %d seed %d wind %.6f,%.6f age %d shift %d,%d box %s hash %x content %x]", c.id, c.originX, c.originY,
                    c.originZ, c.born, c.grid.seed(), c.windX, c.windZ, c.grid.age(), c.grid.shiftX(), c.grid.shiftZ(), java.util.Arrays.toString(c.grid.box()), c.grid.hash(),
                    c.grid.contentHash()));
        return b.toString();
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
        resetReadiness();
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            var sp = server.getPlayerList().getPlayers().getFirst();
            sp.teleportTo(server.overworld(), p.x, p.y - sp.getEyeHeight(), p.z, Set.of(), p.yaw, p.pitch, false);
        }).join();
    }

    private static void shot(String name) {
        Minecraft mc = Minecraft.getInstance();
        acup.gunfeel.smoke.client.SmokeFog.frame(mc.gameRenderer.mainCamera(), 1F);
        Path file = Path.of(DIR, name + ".png");
        net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(file);
            } catch (Exception e) {
                ClientSmoke.check(false, "дым: " + file + ": " + e);
            }
        });
    }

    private static void row(String q, String p) {
        Minecraft mc = Minecraft.getInstance();
        int[] drawn = SmokeRender.drawnLast();
        ROWS.add(String.format(Locale.ROOT, "%s\t%s\t%d\t%d\t%s\t%b\t%s", q, SCENES[scene] + "/" + p, drawn[0], drawn[1], System.getProperty("ntmgunfeel.smokeFabulous", "false"),
                mc.gameRenderer.gameRenderState().useShaderTransparency(), SmokeRender.glassMode()));
    }

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        holdNoon();
        Pose p = POSES[pose];
        place(p);
        acup.gunfeel.smoke.client.SmokeFog.frame(mc.gameRenderer.mainCamera(), 1F);
        if (wait > 0) {
            wait--;
            return;
        }
        switch (phase) {
            case 0 -> {
                if (!rebuilt) {
                    if (!backdropReady()) return;
                    rebuilt = true;
                    // Invalidate only after the entire backdrop arrived, never against a partially received wall.
                    mc.levelRenderer.invalidateCompiledGeometry(mc.level, mc.options, mc.gameRenderer.mainCamera(), mc.getBlockColors());
                    resetReadiness();
                    return;
                }
                if (!lightReady()) return;
                SmokeRender.standSeconds = 1234.5F;
                quality = 0;
                pose = 0;
                clouds = false;
                prepareScene();
                phase = 5;
            }
            case 1 -> {
                // first the scene with no clouds at each pose, then the clouds let out
                Config.smokeQuality = QUALITIES[quality];
                teleport(POSES[pose]);
                place(POSES[pose]);
                lightReady = 0;
                phase = 7;
            }
            case 7 -> {
                // the camera set, the light settled: the atlas made again and filled in one phase (the clouds frozen at 60, it is filled once)
                if (!lightReady()) return;
                acup.gunfeel.smoke.client.SmokeAtlas.close();
                acup.gunfeel.smoke.client.SmokeAtlas.update();
                frames = 0;
                phase = 2;
            }
            case 2 -> {
                // the world settles in the first frames at a pose
                if (!lightReady()) {
                    frames = 0;
                    if (phase != 9) phase = 7;
                    return;
                }
                if (++frames < 60) return;
                String name = SCENES[scene] + "-" + QUALITIES[quality] + "-" + POSES[pose].name + (clouds ? "" : "-0");
                if (clouds || quality == 0) {
                    shot(name);
                    row(QUALITIES[quality], POSES[pose].name + (clouds ? "" : "-0"));
                }
                wait = 2;
                if (++pose < POSES.length) {
                    phase = 1;
                    return;
                }
                pose = 0;
                if (!clouds) {
                    teleport(FRONT);
                    phase = 6;
                    return;
                }
                if (++quality < QUALITIES.length) {
                    phase = 1;
                    return;
                }
                Config.smokeQuality = "high";
                if (++scene < SCENES.length) {
                    quality = 0;
                    clouds = false;
                    prepareScene();
                    phase = 5;
                    return;
                }
                scene = SCENES.length - 1;
                teleport(FRONT);
                phase = 8;
            }
            case 3 -> {
                if (!lightReady()) return;
                if (!caughtUp()) {
                    if (++frames > 3000 && System.nanoTime() - readyAt > 30_000_000_000L) {
                        ClientSmoke.check(false, "дым: облака не догнали время сервера: " + state());
                        done();
                    }
                    return;
                }
                clouds = true;
                quality = 0;
                phase = 1;
                // the atlas made again in phase 7, once the camera is set and the light settled
                // what the clouds are, client and server: the same in every run, or the frames cannot be
                var server = mc.getSingleplayerServer();
                String served = server.submit(() -> digest(SmokeClouds.get(server.overworld()))).join();
                ROWS.add("# client " + digest(SmokeClient.current()) + " time " + mc.level.getGameTime());
                ROWS.add("# server " + served);
            }
            case 5 -> {
                if (lightReady()) phase = 1;
            }
            case 6 -> {
                if (!lightReady()) return;
                letOut();
                readyAt = System.nanoTime();
                frames = 0;
                phase = 3;
            }
            case 8 -> {
                if (lightReady()) phase = 4;
            }
            case 4 -> {
                // the cost: 600 frames at high from the front
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
                int[] drawn = SmokeRender.drawnLast();
                ROWS.add(String.format(Locale.ROOT, "# %d frames: %.3f ms a frame, p95 %.3f ms, %d bytes allocated a frame (render thread), %d clouds in %d groups",
                        MEASURE, avg, p95, (bytes - allocFrom) / MEASURE, drawn[0], drawn[1]));
                ClientSmoke.check(drawn[0] == expectedClouds(), "дым: нарисовано облаков " + drawn[0] + " из " + expectedClouds());
                done();
            }
            default -> {
            }
        }
    }

    private static void prepareScene() {
        resent = false;
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            endAll(level);
            var state = scene == 4 ? Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.BLUE).defaultBlockState()
                    : Blocks.AIR.defaultBlockState();
            for (int x = -6; x <= 6; x++) for (int y = Y; y <= Y + 8; y++)
                level.setBlock(new BlockPos(x, y, 8), state, 2);
        }).join();
        holdNoon();
        teleport(FRONT);
    }

    // setTimeFromServer sets only gameTime; ClientClockManager.tick moves the day's clock by the gameTime's delta, so
    // setting the clouds' birth time back could take the day to night in some runs. A whole clock packet sets the
    // delta's base, noon (6000) and no speed, and its handler clears the environment attributes' cache.
    private static void holdNoon() {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.getConnection() == null) return;
        var clock = mc.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK)
                .getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD);
        mc.getConnection().handleSetTime(new net.minecraft.network.protocol.game.ClientboundSetTimePacket(mc.level.getGameTime(),
                java.util.Map.of(clock, new net.minecraft.world.clock.ClockNetworkState(6000L, 0F, 0F))));
    }

    private static Field access(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void resetReadiness() {
        lightReady = 0;
        settleFrom = System.nanoTime();
        settledMeshes.clear();
    }

    private static boolean pending(String reason) {
        lightReady = 0;
        if (settleFrom == 0) settleFrom = System.nanoTime();
        if (System.nanoTime() - settleFrom > SETTLE_TIMEOUT) {
            ClientSmoke.check(false, "дым: мир не готов за 120 с, " + SCENES[scene] + "/" + POSES[pose].name + ": " + reason);
            done();
        }
        return false;
    }

    private static boolean localChunksReady() {
        var level = Minecraft.getInstance().level;
        if (level == null) return pending("нет клиентского мира");
        // All four poses fit inside this room: its own chunks only (a ring around it was not always sent: the server
        // tracks a circle, not a square). Farther terrain, and the walls' outer faces, are behind the walls.
        // ClientChunkCache.getChunk(..., false) returns null, not its fallback empty chunk, when absent.
        for (int x = MIN_X >> 4; x <= MAX_X >> 4; x++)
            for (int z = MIN_Z >> 4; z <= MAX_Z >> 4; z++)
                if (level.getChunkSource().getChunk(x, z, false) == null) return pending("не пришёл чанк " + x + "," + z);
        return true;
    }

    private static boolean backdropReady() {
        if (!localChunksReady()) return false;
        var level = Minecraft.getInstance().level;
        var backdrop = Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.LIGHT_GRAY);
        var at = new BlockPos.MutableBlockPos();
        for (int y = Y; y <= level.getMaxY(); y++) {
            for (int x = MIN_X; x <= MAX_X; x++)
                if (!level.getBlockState(at.set(x, y, MIN_Z)).is(backdrop)
                        || !level.getBlockState(at.set(x, y, MAX_Z)).is(backdrop)) return pending("не пришёл задник, y=" + y);
            for (int z = MIN_Z; z <= MAX_Z; z++)
                if (!level.getBlockState(at.set(MIN_X, y, z)).is(backdrop)
                        || !level.getBlockState(at.set(MAX_X, y, z)).is(backdrop)) return pending("не пришли боковые стены, y=" + y);
        }
        for (int x = MIN_X; x <= MAX_X; x++) for (int z = MIN_Z; z <= MAX_Z; z++)
            if (!level.getBlockState(at.set(x, Y - 1, z)).is(Blocks.SMOOTH_STONE)) return pending("не пришёл пол");
        return true;
    }

    private static boolean geometryReady() throws IllegalAccessException {
        var renderer = Minecraft.getInstance().levelRenderer;
        var dispatcher = renderer.sectionRenderDispatcher();
        // visibleSections may be empty in the stand's phases (no frame drawn that collected them): not required
        if (dispatcher == null) return pending("нет диспетчера секций");
        if (!renderer.hasRenderedAllSections()) return pending("очередь сборки секций");
        // isQueueEmpty alone misses workers that already took a task (SectionRenderDispatcher.runTask, 26.2).
        var buffers = (ArrayBlockingQueue<?>) poolBuffers.get(dispatcherPool.get(dispatcher));
        if (buffers.remainingCapacity() != 0) return pending("секции ещё собираются рабочими потоками");
        Map<SectionRenderDispatcher.RenderSection, SectionMesh> current = new IdentityHashMap<>();
        for (var section : renderer.visibleSections()) {
            SectionMesh mesh = section.getSectionMesh();
            // a section of the room's chunks must be compiled; one beyond them may stay uncompiled for good (its
            // neighbour chunk the server's circle never sends) and draws nothing in any run, behind the walls.
            // One compiled must be faded in whole (its fade-in goes by real time).
            var origin = section.getRenderOrigin();
            boolean room = origin.getX() >> 4 >= MIN_X >> 4 && origin.getX() >> 4 <= MAX_X >> 4
                    && origin.getZ() >> 4 >= MIN_Z >> 4 && origin.getZ() >> 4 <= MAX_Z >> 4;
            if (mesh == CompiledSectionMesh.UNCOMPILED) {
                if (room) {
                    // a section is compiled only with its neighbour chunks on the client; the server's circle sends the
                    // row beyond the room in some runs only, so the chunks around it are sent from the server
                    if (++dirtyFrames % 40 == 1) resend(origin.getX() >> 4, origin.getZ() >> 4);
                    return pending("секция помещения не собрана " + origin);
                }
            } else if (section.getVisibility(net.minecraft.util.Util.getMillis()) < 1F) return pending("секция ещё проявляется");
            current.put(section, mesh);
        }
        // Mesh publication follows GPU upload callbacks. Require the visible set and mesh identities to stay fixed
        // as well as empty queues, so a completed CPU task with a pending upload cannot count as a settled frame.
        boolean unchanged = current.equals(settledMeshes);
        settledMeshes = current;
        return unchanged || pending("изменился набор видимых секций или их меши");
    }

    private static String lastSky;
    private static int resendFrames;
    private static boolean resent;
    private static int dirtyFrames;

    private static void resendRoom() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            var level = server.overworld();
            var sp = server.getPlayerList().getPlayers().getFirst();
            for (int x = MIN_X >> 4; x <= MAX_X >> 4; x++) for (int z = MIN_Z >> 4; z <= MAX_Z >> 4; z++)
                sp.connection.send(new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(level.getChunk(x, z),
                        level.getLightEngine(), null, null));
            GunFeel.LOGGER.info("[smokeframes] the room's chunks sent again with the server's light");
        }).join();
    }

    // the chunks around one (the room's and the row beyond it), with the server's light, sent to the player (as ChunkMap sends them)
    private static void resend(int cx, int cz) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            var level = server.overworld();
            var sp = server.getPlayerList().getPlayers().getFirst();
            for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++) {
                sp.connection.send(new net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket(level.getChunk(x, z),
                        level.getLightEngine(), null, null));
            }
            GunFeel.LOGGER.info("[smokeframes] chunks around {},{} sent again with the server's light", cx, cz);
        }).join();
    }

    private static boolean lightReady() {
        Minecraft mc = Minecraft.getInstance();
        if (!localChunksReady()) return false;
        var level = mc.level;
        try {
            // ClientPacketListener.handleLightUpdatePacket (26.2:2334) queues work in ClientLevel, not directly in
            // the engine. ClientLevel.pollLightUpdates feeds it later, before runLightUpdates (26.2:550-553).
            if (!((Deque<?>) lightUpdates.get(level)).isEmpty()) return pending("очередь пакетов света ClientLevel");
            if (level.getChunkSource().getLightEngine().hasLightWork()) return pending("очередь клиентского light engine");
            if (!geometryReady()) return false;
        } catch (IllegalAccessException | RuntimeException e) {
            ClientSmoke.check(false, "дым: проверка очередей готовности 26.2: " + e);
            done();
            return false;
        }
        var expected = scene == 4 ? Blocks.STAINED_GLASS.pick(net.minecraft.world.item.DyeColor.BLUE) : Blocks.AIR;
        for (int x = -6; x <= 6; x++) for (int y = Y; y <= Y + 8; y++)
            if (!level.getBlockState(new BlockPos(x, y, 8)).is(expected)) return pending("не пришли блоки сцены");
        var server = mc.getSingleplayerServer();
        int[] serverSky = server.submit(() -> {
            ServerLevel served = server.overworld();
            int[] values = new int[POSES.length + 1];
            for (int i = 0; i < POSES.length; i++) {
                Pose p = POSES[i];
                values[i] = served.getBrightness(LightLayer.SKY, BlockPos.containing(p.x, p.y + 10, p.z));
            }
            values[POSES.length] = served.getChunkSource().getLightEngine().hasLightWork() ? 1 : 0;
            return values;
        }).join();
        if (serverSky[POSES.length] != 0) return pending("очередь серверного light engine");
        // once a scene is built and the server's light settled: every chunk of the room sent again with the server's
        // light, so the client's light is the server's everywhere in every run (its own working out of the stand's
        // block changes left a section at 0 in some runs; a resend of a few chunks only mixed the two)
        if (!resent) {
            resent = true;
            resendRoom();
            return pending("чанки помещения отправлены заново со светом сервера");
        }
        // the client's skylight above each pose as the server's: a client section can stay at 0 for good while both
        // light queues are empty (the smoke's atlas reads the client's light, so a cloud there was lit otherwise in such
        // a run). Then the pose's chunk is sent again with the server's light, as when it was first sent, and waited for.
        StringBuilder sky = new StringBuilder("# skylight client/server");
        boolean off = false;
        for (int i = 0; i < POSES.length; i++) {
            Pose p = POSES[i];
            int c = level.getBrightness(LightLayer.SKY, BlockPos.containing(p.x, p.y + 10, p.z));
            sky.append(' ').append(p.name).append(' ').append(c).append('/').append(serverSky[i]);
            if (c != serverSky[i]) {
                off = true;
                if (++resendFrames % 40 == 1) resend(SectionPos.blockToSectionCoord(p.x), SectionPos.blockToSectionCoord(p.z));
            }
        }
        if (!sky.toString().equals(lastSky)) ROWS.add(lastSky = sky.toString());
        if (off) return pending("skylight клиента не равен серверному: " + sky);
        // All local chunks, scene blocks, both light stages, uploaded visible geometry and open sky: 20 frames running.
        if (lightReady < SETTLED_FRAMES) lightReady++;
        if (lightReady < SETTLED_FRAMES) return false;
        settleFrom = 0;
        return true;
    }

    private static void done() {
        SmokeRender.standSeconds = Float.NaN;
        command("tick unfreeze");
        try {
            Files.write(Path.of(DIR, "frames.tsv"), ROWS);
        } catch (Exception e) {
            ClientSmoke.check(false, "дым: запись " + e);
        }
        for (String r : ROWS) GunFeel.LOGGER.info("[smokeframes] {}", r);
        phase = 9;
        ClientSmoke.finish();
    }
}
