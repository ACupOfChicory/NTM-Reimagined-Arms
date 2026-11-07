package acup.gunfeel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

// runCheckClient -PglassBack[=fabulous]: glass and the like seen through, and the holes on its far side. Each
// glass (blocks, then panes) in a row of three, two high, in front of the camera, a wall behind it and the glass's
// item in an invisible item frame on the wall beside it. With the wall white and then black: how much of the glass
// and of its item shows the wall through it. Then bullets from behind the glass, holes on its far face: how many
// pixels they change seen through it, with the holes kept on the graphics card (holeCache) and sent each frame; then
// holes on the near face, the same. Then particles held still (NTM's explosion cloud, vanilla campfire smoke at alpha
// 0.5, vanilla explosion) in front of each glass of UNDER, then behind it: how much of the glass still shows with them.
// Under the cloud and the smoke at least 0.35 of its change on the bare wall must, behind them at least half of
// it. Lines "[glassback] ..." in the log. =fabulous: with improved transparency.
final class GlassBackCheck {
    private static final String MODE = System.getProperty("ntmgunfeel.glassBack", "");
    static final boolean ENABLED = !MODE.isBlank();
    private static final boolean FABULOUS = MODE.equals("fabulous");
    private static final BlockPos O = new BlockPos(8, 100, 8);
    private static final String[] TYPES = {"minecraft:glass", "minecraft:white_stained_glass", "minecraft:tinted_glass", "hbm:reinforced_glass",
            "hbm:reinforced_laminate", "hbm:glass_boron", "minecraft:glass_pane", "minecraft:white_stained_glass_pane", "hbm:reinforced_glass_pane",
            "hbm:reinforced_laminate_pane"};
    // the wall behind, its south face at z = O.z - 2
    private static final int WALL_Z = O.getZ() - 3;
    // the camera's eye a block above O, 4.5 blocks south of the glass's middle, looking north
    private static final Vec3 EYE = new Vec3(O.getX() + 0.5, O.getY() + 1 - 1.62, O.getZ() + 4.5);
    private static final float YAW = 180F, PITCH = 0F;
    // the item frame: on the wall, right of the glass
    private static final BlockPos FRAME = new BlockPos(O.getX() + 5, O.getY() + 1, WALL_Z + 1);

    private static int type = -1, step, wait, shotId = -2_000_000;
    private static volatile boolean placed;
    private static volatile String failure;
    private static volatile int[] shot;
    private static volatile int shotWidth;
    private static boolean shooting;
    private static int[] emptyWhite, emptyBlack, baseWhite, baseBlack, farCache, farEach, nearCache, nearEach;
    private static String faces = "";
    private static final Map<String, double[]> RESULT = new LinkedHashMap<>();
    private static final String[] PARTICLES = {"ntm explosion", "vanilla smoke", "vanilla explosion"};
    // the particle between the camera and the glass, then between the glass and the wall
    private static final String[] SIDES = {"before", "behind"};
    private static final Vec3[] PARTICLE_AT = {new Vec3(O.getX() + 0.5, O.getY() + 1, O.getZ() + 1.6),
            new Vec3(O.getX() + 0.5, O.getY() + 1, O.getZ() - 1.0)};
    // the glass under the particles: vanilla's cutout and translucent, NTM's cutout block and translucent pane, the laminate
    private static final String[] UNDER = {"minecraft:glass", "minecraft:white_stained_glass", "minecraft:glass_pane",
            "minecraft:white_stained_glass_pane", "hbm:reinforced_glass", "hbm:reinforced_glass_pane", "hbm:reinforced_laminate",
            "hbm:reinforced_laminate_pane"};
    // glass | particle | side: its pixels the particle covers, how many of them still show the glass, and the glass's
    // change there over its change on the bare wall
    private static final Map<String, double[]> UNDER_RESULT = new LinkedHashMap<>();
    private static int particle, under, side, missing;
    private static net.minecraft.client.particle.Particle held;
    private static int[] underBoth, underOnly, paneOnly, none;

    // NTM's explosion cloud (a Flywheel visual on DE) at a sixth of its life, kept there
    private static final class Frozen extends com.hbm.particle.ParticleExplosionSmall {
        Frozen(net.minecraft.client.multiplayer.ClientLevel level, double x, double y, double z) {
            super(level, x, y, z, 1.5F, 0F);
            this.lifetime = 30;
            this.age = 5;
        }

        @Override
        public void tick() {
        }
    }

    private static java.lang.reflect.Field field(Class<?> c, String name) {
        for (; c != null; c = c.getSuperclass()) try {
            var f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            // up the classes
        }
        throw new IllegalStateException("no field " + name);
    }

    // a vanilla particle held where it is, at its age: set back each frame (its tick moves it on by a tick at most)
    private static void hold(net.minecraft.client.particle.Particle p) {
        try {
            for (String n : new String[] {"x", "xo"}) field(p.getClass(), n).setDouble(p, PARTICLE_AT[side].x);
            for (String n : new String[] {"y", "yo"}) field(p.getClass(), n).setDouble(p, PARTICLE_AT[side].y);
            for (String n : new String[] {"z", "zo"}) field(p.getClass(), n).setDouble(p, PARTICLE_AT[side].z);
            for (String n : new String[] {"xd", "yd", "zd"}) field(p.getClass(), n).setDouble(p, 0);
            field(p.getClass(), "age").setInt(p, 2);
            // (the vanilla explosion lives 6-9 ticks: in some runs it was gone before the shots, 0 pixels)
            field(p.getClass(), "lifetime").setInt(p, 1000);
            if (particle == 1) field(p.getClass(), "alpha").setFloat(p, 0.5F);
        } catch (ReflectiveOperationException e) {
            failure = "частица: " + e;
        }
    }

    private static void spawn() {
        Minecraft mc = Minecraft.getInstance();
        if (particle == 0) {
            Vec3 at = PARTICLE_AT[side];
            held = new Frozen(mc.level, at.x, at.y, at.z);
            mc.particleEngine.add(held);
        } else {
            held = mc.particleEngine.createParticle(particle == 1 ? net.minecraft.core.particles.ParticleTypes.CAMPFIRE_COSY_SMOKE
                    : net.minecraft.core.particles.ParticleTypes.EXPLOSION, PARTICLE_AT[side].x, PARTICLE_AT[side].y, PARTICLE_AT[side].z, 0, 0, 0);
            if (held == null) failure = "частица " + PARTICLES[particle] + " не создана";
            else hold(held);
        }
    }

    // where the particle shows over the bare wall and the glass shows without it: how much of the glass is left with it
    private static void particleResult() {
        int w = shotWidth, cut = split(w, none.length / w), region = 0, foot = 0, seen = 0;
        double left = 0, bare = 0;
        for (int i = 0; i < none.length; i++) {
            if (i % w >= cut || diff(underOnly[i], none[i]) <= 12) continue;
            foot++;
            int b = Math.abs(light(paneOnly[i]) - light(none[i]));
            if (b <= 8 * 3) continue;
            int u = Math.abs(light(underBoth[i]) - light(underOnly[i]));
            boolean alike = Math.abs(light(underOnly[i]) - light(paneOnly[i])) <= 8 * 3;
            // the particle alone as bright as the glass alone: whichever shows, the frame is the same; not judged
            if (u <= 8 * 3 && alike) continue;
            region++;
            // the glass shows where the frame differs from the particle alone, or is the glass alone (the particle does
            // not show there: behind the glass, it fails the depth test of a glass that writes depth)
            if (u > 8 * 3 || Math.abs(light(underBoth[i]) - light(paneOnly[i])) <= 8 * 3) seen++;
            left += u;
            bare += b;
        }
        UNDER_RESULT.put(UNDER[under] + " | " + PARTICLES[particle] + " | " + SIDES[side], new double[] {region, seen, bare == 0 ? 0 : left / bare});
        GunFeel.LOGGER.info(String.format(Locale.ROOT, "[glassback] particle %s %s %s: its pixels %d, of them on the glass %d; the glass "
                + "still seen with it in %d (%.0f%%), its change there %.2f of the bare glass's", PARTICLES[particle], SIDES[side], UNDER[under], foot,
                region, seen, 100.0 * seen / Math.max(1, region), bare == 0 ? 0 : left / bare));
    }

    private GlassBackCheck() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.options.fov().set(70);
        mc.options.fovEffectScale().set(0.0);
        if (FABULOUS) mc.options.improvedTransparency().set(true);
        mc.getSingleplayerServer().execute(() -> {
            var server = mc.getSingleplayerServer();
            var player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.CREATIVE);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            player.teleportTo(server.overworld(), EYE.x, EYE.y, EYE.z, Set.of(), YAW, PITCH, false);
            server.overworld().getGameRules().set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon");
        });
        Config.tracers = false;
        Config.bulletFx = false;
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
        step = 0;
    }

    private static Block block(String id) {
        return BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
    }

    private static Block wall(boolean white) {
        return block(white ? "minecraft:white_concrete" : "minecraft:black_concrete");
    }

    private static String name() {
        return TYPES[type].replace("minecraft:", "");
    }

    private static boolean pane() {
        return TYPES[type].endsWith("_pane");
    }

    private static List<BlockPos> glass() {
        List<BlockPos> out = new ArrayList<>();
        for (int x = -1; x <= 1; x++) for (int y = 0; y <= 1; y++) out.add(O.offset(x, y, 0));
        return out;
    }

    private static void command(net.minecraft.server.MinecraftServer server, String text) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), text);
    }

    // the area emptied, the wall built of white or black concrete; with the glass (type >= 0) and its item
    private static void build(boolean white, boolean withGlass) {
        placed = false;
        Minecraft.getInstance().getSingleplayerServer().execute(() -> {
            try {
                var server = Minecraft.getInstance().getSingleplayerServer();
                var level = server.overworld();
                for (int x = O.getX() - 9; x <= O.getX() + 9; x++) for (int y = O.getY() - 7; y <= O.getY() + 8; y++)
                    for (int z = WALL_Z; z <= O.getZ() + 3; z++) {
                        BlockState want = z == WALL_Z ? wall(white).defaultBlockState() : Blocks.AIR.defaultBlockState();
                        if (level.getBlockState(new BlockPos(x, y, z)) != want) level.setBlock(new BlockPos(x, y, z), want, 3);
                    }
                command(server, "kill @e[type=minecraft:item_frame]");
                if (withGlass) {
                    for (BlockPos p : glass()) level.setBlock(p, block(TYPES[type]).defaultBlockState(), 3);
                    for (int pass = 0; pass < 2; pass++) for (BlockPos p : glass())
                        level.setBlock(p, Block.updateFromNeighbourShapes(level.getBlockState(p), level, p), 3);
                    command(server, String.format(Locale.ROOT, "summon minecraft:item_frame %d %d %d {Facing:3b,Invisible:1b,Fixed:1b,Item:{id:\"%s\",count:1}}",
                            FRAME.getX(), FRAME.getY(), FRAME.getZ(), TYPES[type]));
                }
                placed = true;
            } catch (Throwable t) {
                failure = t.toString();
            }
        });
    }

    // the client has what the server built
    private static boolean synced(boolean white, boolean withGlass) {
        Minecraft mc = Minecraft.getInstance();
        if (!placed) return false;
        var server = mc.getSingleplayerServer().overworld();
        BlockPos wall = new BlockPos(O.getX(), O.getY(), WALL_Z);
        if (mc.level.getBlockState(wall).getBlock() != wall(white)) return false;
        for (BlockPos p : glass()) if (mc.level.getBlockState(p) != server.getBlockState(p)) return false;
        int frames = 0;
        for (var e : mc.level.entitiesForRendering()) if (e instanceof net.minecraft.world.entity.decoration.ItemFrame) frames++;
        return frames == (withGlass ? 1 : 0);
    }

    private static void fire(boolean fromBehind) {
        BulletConfig round = BulletCheck.pick();
        int index = BulletConfig.configs.indexOf(round);
        Vec3 u = new Vec3(0, 0, fromBehind ? 1 : -1);
        for (int i = 0; i < 5; i++) for (int j = 0; j < 4; j++) {
            Vec3 at = new Vec3(O.getX() - 1 + 0.2 + i * 0.65, O.getY() + 0.2 + j * 0.53, O.getZ() + 0.5);
            Vec3 from = at.subtract(u.scale(1.5));
            Vec3 v = u.scale(round.velocity);
            // a weak bullet: it stops in the glass, a hole on the face it meets and none on the other
            Tracers.handle(new ShotPayload(index, -1, from.x, from.y, from.z, (float) v.x, (float) v.y, (float) v.z, 0F, 3, 0.5F,
                    Double.NaN, Double.NaN, Double.NaN, shotId--), null);
        }
    }

    private static boolean screenshot() {
        Minecraft mc = Minecraft.getInstance();
        if (shooting) {
            if (shot == null) return false;
            shooting = false;
            return true;
        }
        shot = null;
        shooting = true;
        net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                shotWidth = image.getWidth();
                shot = image.getPixels();
            } catch (Exception e) {
                failure = "снимок: " + e;
            }
        });
        return false;
    }

    private static int diff(int a, int b) {
        int d = 0;
        for (int k = 0; k < 24; k += 8) d = Math.max(d, Math.abs((a >> k & 255) - (b >> k & 255)));
        return d;
    }

    private static int light(int a) {
        return (a & 255) + (a >> 8 & 255) + (a >> 16 & 255);
    }

    // the screen's column between the glass and the item frame: the glass's east edge is 1.5 blocks right of the eye
    // at 3.5 blocks, the frame's west edge 4.5 right at 6.4
    private static int split(int width, int height) {
        double f = height / 2.0 / Math.tan(Math.toRadians(35));
        return (int) (width / 2.0 + f * 0.55);
    }

    // [glass pixels, those showing the wall through them, how much of the wall's change shows through them]
    private static double[] seen(boolean item) {
        int w = shotWidth, h = baseWhite.length / w, cut = split(w, h), region = 0, through = 0;
        double passed = 0;
        for (int i = 0; i < baseWhite.length; i++) {
            if (i % w < cut == item) continue;
            if (diff(baseWhite[i], emptyWhite[i]) <= 12 && diff(baseBlack[i], emptyBlack[i]) <= 12) continue;
            region++;
            if (diff(baseWhite[i], baseBlack[i]) > 12) through++;
            int wall = light(emptyWhite[i]) - light(emptyBlack[i]);
            if (wall > 60) passed += Math.max(0, light(baseWhite[i]) - light(baseBlack[i])) / (double) wall;
        }
        return new double[] {region, through, region == 0 ? 0 : passed / region};
    }

    // pixels of the glass's side of the screen that differ by more than 10
    private static int changed(int[] a, int[] b) {
        int w = shotWidth, cut = split(w, a.length / w), n = 0;
        for (int i = 0; i < a.length; i++) if (i % w < cut && diff(a[i], b[i]) > 10) n++;
        return n;
    }

    // how much darker a pixel the holes changed is, on average (0..255): the copy under the glass laid under the late
    // hole seen from in front darkened its half-clear edges twice
    private static double darkness(int[] a, int[] b) {
        int w = shotWidth, cut = split(w, a.length / w), n = 0;
        double sum = 0;
        for (int i = 0; i < a.length; i++) if (i % w < cut && diff(a[i], b[i]) > 10) { n++; sum += light(b[i]) - light(a[i]); }
        return n == 0 ? 0 : sum / n;
    }

    private static String faces() {
        StringBuilder sb = new StringBuilder();
        for (var e : Tracers.holeFaces().entrySet()) {
            int[] c = e.getValue();
            sb.append(String.format(Locale.ROOT, " %s: %d (late %d, under %d, batched %d, batched under %d)", e.getKey(), c[0], c[1], c[2], c[3], c[4]));
        }
        return sb.length() == 0 ? " none" : sb.toString();
    }

    private static boolean shaderTransparency() {
        return Minecraft.getInstance().gameRenderer.gameRenderState().useShaderTransparency();
    }

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if (failure != null) {
            ClientSmoke.check(false, "стекло сзади: " + failure);
            ClientSmoke.finish();
            return;
        }
        if (mc.player != null && mc.player.position().distanceTo(EYE) < 1) {
            mc.player.setYRot(YAW);
            mc.player.setXRot(PITCH);
            mc.player.yRotO = YAW;
            mc.player.xRotO = PITCH;
        }
        if (held != null && particle > 0) hold(held);
        if (wait > 0) {
            wait--;
            return;
        }
        switch (step) {
            case 0 -> { // the empty area, white wall
                if (mc.player == null || mc.player.position().distanceTo(EYE) > 1) return;
                GunFeel.LOGGER.info("[glassback] mode {}: improved transparency {}, holeCache {}, ntmRenderFixes {}", MODE, shaderTransparency(), Config.holeCache,
                        Config.ntmRenderFixes);
                if (FABULOUS) ClientSmoke.check(shaderTransparency(), "стекло сзади: улучшенная прозрачность включена");
                Tracers.clearHoles();
                build(true, false);
                step = 1;
            }
            case 1 -> {
                if (!synced(true, false)) return;
                // the first shot of the run: the light settled first (on DE, at 30 frames the wall was darker than in
                // the later shots, and the glass seemed to pass more than all of it)
                wait = 200;
                step = 2;
            }
            case 2 -> {
                if (!screenshot()) return;
                emptyWhite = shot;
                build(false, false);
                step = 3;
            }
            case 3 -> {
                if (!synced(false, false)) return;
                wait = 30;
                step = 4;
            }
            case 4 -> {
                if (!screenshot()) return;
                emptyBlack = shot;
                step = 10;
            }
            case 10 -> { // the next glass, on the black wall first
                Tracers.clearHoles();
                if (++type >= TYPES.length) {
                    particle = 0;
                    step = 30;
                    return;
                }
                if (block(TYPES[type]) == Blocks.AIR) {
                    GunFeel.LOGGER.info("[glassback] {}: no such block", name());
                    return;
                }
                build(false, true);
                step = 11;
            }
            case 11 -> {
                if (!synced(false, true)) return;
                wait = 30;
                step = 12;
            }
            case 12 -> {
                if (!screenshot()) return;
                baseBlack = shot;
                build(true, true);
                step = 13;
            }
            case 13 -> {
                if (!synced(true, true)) return;
                wait = 30;
                step = 14;
            }
            case 14 -> {
                if (!screenshot()) return;
                baseWhite = shot;
                BlockState st = mc.level.getBlockState(O);
                GunFeel.LOGGER.info("[glassback] {}: {} translucent layer {}", name(), st, Tracers.translucentLayer(st));
                fire(true);
                wait = 20;
                step = 15;
            }
            case 15 -> { // the holes on the far face
                if (Tracers.pendingHoles() > 0) return;
                faces = faces();
                wait = 5;
                step = 16;
            }
            case 16 -> {
                if (!screenshot()) return;
                farCache = shot;
                Config.holeCache = false;
                wait = 5;
                step = 17;
            }
            case 17 -> {
                if (!screenshot()) return;
                farEach = shot;
                Config.holeCache = true;
                GunFeel.LOGGER.info("[glassback] {}: holes from behind:{}", name(), faces);
                Tracers.clearHoles();
                fire(false);
                wait = 20;
                step = 18;
            }
            case 18 -> {
                if (Tracers.pendingHoles() > 0) return;
                faces = faces();
                wait = 5;
                step = 19;
            }
            case 19 -> {
                if (!screenshot()) return;
                nearCache = shot;
                Config.holeCache = false;
                wait = 5;
                step = 20;
            }
            case 20 -> {
                if (!screenshot()) return;
                nearEach = shot;
                Config.holeCache = true;
                GunFeel.LOGGER.info("[glassback] {}: holes from the front:{}", name(), faces);
                double[] g = seen(false), it = seen(true);
                double[] r = {g[0], g[1], g[2], it[0], it[1], it[2], changed(farCache, baseWhite), changed(farEach, baseWhite), changed(farCache, farEach),
                        changed(nearCache, baseWhite), changed(nearEach, baseWhite), changed(nearCache, nearEach),
                        darkness(nearCache, baseWhite), darkness(nearEach, baseWhite), darkness(farCache, baseWhite), darkness(farEach, baseWhite)};
                RESULT.put(name(), r);
                GunFeel.LOGGER.info(String.format(Locale.ROOT, "[glassback] %s: glass pixels %.0f, the wall seen through %.0f (%.0f%%), passing %.2f; item pixels %.0f, "
                        + "through %.0f (%.0f%%), passing %.2f; far holes change %.0f pixels kept / %.0f sent (they differ in %.0f); near holes %.0f / %.0f (differ %.0f); "
                        + "a changed pixel darker by %.1f / %.1f near, %.1f / %.1f far",
                        name(), r[0], r[1], 100 * r[1] / Math.max(1, r[0]), r[2], r[3], r[4], 100 * r[4] / Math.max(1, r[3]), r[5], r[6], r[7], r[8], r[9], r[10], r[11],
                        r[12], r[13], r[14], r[15]));
                step = 10;
            }
            case 30 -> { // a particle before a glass
                if (particle >= PARTICLES.length) {
                    particle = 0;
                    under++;
                }
                if (under >= UNDER.length) {
                    under = 0;
                    side++;
                }
                if (side >= SIDES.length) {
                    step = 99;
                    return;
                }
                if (block(UNDER[under]) == Blocks.AIR) {
                    if (particle == 0) {
                        GunFeel.LOGGER.info("[glassback] particles {} {}: no such block", SIDES[side], UNDER[under]);
                        missing += PARTICLES.length;
                    }
                    particle = PARTICLES.length;
                    return;
                }
                type = java.util.Arrays.asList(TYPES).indexOf(UNDER[under]);
                build(true, true);
                step = 31;
            }
            case 31 -> {
                if (!synced(true, true)) return;
                wait = 30;
                step = 32;
            }
            case 32 -> {
                spawn();
                wait = 15;
                step = 33;
            }
            case 33 -> {
                if (!screenshot()) return;
                underBoth = shot;
                GunFeel.LOGGER.info("[glassback] particle {}: {}, alive {}", PARTICLES[particle], held == null ? "none" : held.getClass().getName(),
                        held != null && held.isAlive());
                build(true, false);
                step = 34;
            }
            case 34 -> {
                if (!synced(true, false)) return;
                wait = 30;
                step = 35;
            }
            case 35 -> {
                if (!screenshot()) return;
                underOnly = shot;
                held.remove();
                held = null;
                wait = 15;
                step = 36;
            }
            case 36 -> {
                if (!screenshot()) return;
                none = shot;
                build(true, true);
                step = 37;
            }
            case 37 -> {
                if (!synced(true, true)) return;
                wait = 30;
                step = 38;
            }
            case 38 -> {
                if (!screenshot()) return;
                paneOnly = shot;
                particleResult();
                particle++;
                step = 30;
            }
            case 99 -> {
                for (var e : RESULT.entrySet()) {
                    String n = e.getKey();
                    double[] r = e.getValue();
                    boolean laminate = n.contains("laminate");
                    // the holes on the far face seen through the glass: as many pixels kept on the card as sent each frame
                    ClientSmoke.check(r[7] < 30 || r[6] >= 0.6 * r[7], "стекло сзади " + n + ": след с той стороны, пикселей " + (int) r[6] + " в партиях, "
                            + (int) r[7] + " каждый кадр");
                    ClientSmoke.check(r[10] < 30 || r[9] >= 0.6 * r[10], "стекло сзади " + n + ": след с этой стороны, пикселей " + (int) r[9] + " в партиях, "
                            + (int) r[10] + " каждый кадр");
                    // from in front the holes kept on the card darken the glass as the ones sent each frame do, which
                    // draw no copy under the glass there
                    ClientSmoke.check(r[10] < 30 || Math.abs(r[12] - r[13]) <= 0.1 * r[13] + 2, "стекло сзади " + n + ": след спереди темнее на "
                            + String.format(Locale.ROOT, "%.1f в партиях, %.1f каждый кадр", r[12], r[13]));
                    if (laminate) {
                        ClientSmoke.check(r[6] > 100, "стекло сзади " + n + ": след с той стороны виден сквозь ламинат, пикселей " + (int) r[6]);
                        ClientSmoke.check(r[0] > 1000 && r[1] > 0.5 * r[0], "стекло сзади " + n + ": стена видна сквозь блок, " + (int) r[1] + " из " + (int) r[0]);
                        ClientSmoke.check(r[3] > 200 && r[4] > 0.5 * r[3], "стекло сзади " + n + ": стена видна сквозь предмет, " + (int) r[4] + " из " + (int) r[3]);
                    }
                }
                ClientSmoke.check(RESULT.containsKey("hbm:reinforced_laminate") && RESULT.containsKey("hbm:reinforced_laminate_pane"), "стекло сзади: ламинат проверен");
                // the glass behind the explosion cloud and the smoke (both half clear): its change under them at least 0.35
                // of its change on the bare wall (the smoke at alpha 0.5 leaves 0.5 of it, a glass they hide 0). The glass
                // before them shows on at least half of what they cover. Vanilla's explosion is only logged: it is opaque
                // and hides the glass behind it, as it should, and held at its age it is now and then not drawn at all or
                // drawn over the glass before it, run to run alike with ntmRenderFixes on or off. A glass a particle covers
                // under 50 pixels of is not judged (vanilla's clear glass is mostly empty, the smoke's sprite is small and
                // changes run to run), but NTM's cloud must cover 50 of every glass.
                int cases = UNDER.length * PARTICLES.length * SIDES.length - missing;
                ClientSmoke.check(UNDER_RESULT.size() == cases, "стекло и частицы: проверено " + UNDER_RESULT.size() + " из " + cases);
                for (var e : UNDER_RESULT.entrySet()) {
                    double[] r = e.getValue();
                    if (e.getKey().contains(" | " + PARTICLES[2] + " | ")) continue;
                    if (e.getKey().contains(" | " + PARTICLES[0] + " | "))
                        ClientSmoke.check(r[0] >= 50, "стекло и частица " + e.getKey() + ": под частицей пикселей стекла " + (int) r[0]);
                    if (r[0] < 50) continue;
                    if (e.getKey().endsWith(SIDES[1]))
                        ClientSmoke.check(r[1] * 2 >= r[0], "стекло и частица " + e.getKey() + ": видно в " + (int) r[1] + " из " + (int) r[0]);
                    else
                        ClientSmoke.check(r[2] >= 0.35, String.format(Locale.ROOT, "стекло и частица %s: изменение под ней %.2f от голого стекла",
                                e.getKey(), r[2]));
                }
                Tracers.clearHoles();
                ClientSmoke.finish();
            }
            default -> {
            }
        }
    }
}
