package acup.gunfeel;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.hbm.blocks.ModBlocks;
import com.hbm.blocks.bomb.BlockLandmineFat;
import com.hbm.entity.effect.EntityNukeTorex;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.explosion.ExplosionNukeSmall;
import com.hbm.handler.threading.TargetPoint;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.XFactoryCatapult;
import com.hbm.packet.PacketWire;
import com.hbm.packet.toclient.MukePayload;

import acup.gunfeel.nuke.MiniNukeCloud;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

// runCheckClient -PminiNukeStand=<dir>: NTM's small nuclear blasts through NTM's own code (the Fat Mine, the Fat Man's
// standard shell, a Tiny Tot volley of 8, 8 Fat Mines in a row, the artillery mini nuke, the mod's nuclear .50 call, a
// Fat Mine past the simulation distance that keeps NTM's muke), each with NTM's muke particles (A, MiniNukeCloud off)
// and with the bombs' cloud (B), twice; -PminiNukeScenes=mine,arty only those. The camera 40 blocks off, 6 up, the HUD
// hidden, vsync and the frame limit off. Per run: the frame time before the blast (2 s) and over 8 s after it,
// and for B the clouds seen, their scale and type, the most puffs, the mushroom's top above the ground and the shock
// ring's radius in blocks. <dir>/runs.tsv; screenshots <scene>-<mode>-<rep>-{base,1s,3s}.png (pixels counted
// afterwards, not looked at).
final class MiniNukeStand {
    static final String DIR = System.getProperty("ntmgunfeel.miniNukeStand", "");
    static final boolean ENABLED = !DIR.isBlank();
    private static final int Y = 100, REPS = 2;
    private static final String[] SCENES = System.getProperty("ntmgunfeel.miniNukeScenes", "").isBlank()
            ? new String[] {"mine", "fatman", "volley", "mines8", "arty", "bmg50", "far"} : System.getProperty("ntmgunfeel.miniNukeScenes").split(",");
    // the cloud's scale each scene should get (radius / 100): the mine 10, the shell 10, the volley's one cloud 10,
    // the artillery's PARAMS_MEDIUM 20, the .50's 8
    private static double want(String scene) {
        return switch (scene) {
            case "arty" -> 0.2;
            case "bmg50" -> 0.08;
            default -> 0.1;
        };
    }

    private static int run, phase, frames;
    private static long last, phaseAt, triggerAt;
    private static final List<Long> TIMES = new ArrayList<>();
    private static double baseMs;
    private static int clouds, puffs, wrongType, minesFired;
    private static double scale, top, ring;
    private static boolean shot1, shot3;
    // the cloud's heat and its puffs' mean colour (NTM's own, before light and texture) at 1 s and 3 s: hot, then grey
    private static final double[] COLOUR = new double[8];
    // the shock ring at 0.5, 1, 2 and 3 s: its radius (blocks) and its puffs then
    private static final double[] RING_AT = {0.5, 1, 2, 3};
    private static final double[] RING_R = new double[4];
    private static final int[] RING_N = new int[4];
    // the share of the ring's puffs within 2 blocks of its median distance (a ring, not a disc), and that median
    private static final double[] RING_BAND = new double[4], RING_MID = new double[4];
    private static final List<String> ROWS = new ArrayList<>();

    private MiniNukeStand() {
    }

    private static String scene() {
        return SCENES[run / (2 * REPS)];
    }

    private static boolean modeB() {
        return run % 2 == 1;
    }

    private static int rep() {
        return run / 2 % REPS;
    }

    private static String label() {
        return scene() + "-" + (modeB() ? "B" : "A") + "-" + rep();
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.options.enableVsync().set(false);
        mc.options.framerateLimit().set(260);
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            var src = server.createCommandSourceStack().withSuppressedOutput();
            server.getCommands().performPrefixedCommand(src, "time set noon");
            server.getCommands().performPrefixedCommand(src, "weather clear");
            var rules = level.getGameRules();
            rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server);
            rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER, false, server);
            rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server);
            server.getPlayerList().getPlayers().getFirst().setGameMode(GameType.SPECTATOR);
        }).join();
        try {
            Files.createDirectories(Path.of(DIR));
        } catch (Exception e) {
            ClientSmoke.check(false, "мини-нюк: папка " + DIR + ": " + e);
        }
        ROWS.add("scene\tmode\trep\tbase_ms\tframe_ms\tp95_ms\tfps\tclouds\tscale\tpuffs_max\ttop_blocks\tring_blocks\theat_1s\tr_1s\tg_1s\tb_1s\theat_3s\tr_3s\tg_3s\tb_3s\tring_05s\tring_1s\tring_2s\tring_3s\tpuffs_05s\tpuffs_1s\tpuffs_2s\tpuffs_3s\tmid_05s\tmid_1s\tmid_2s\tmid_3s\tband_05s\tband_1s\tband_2s\tband_3s\twaste_r20\tgrass_r20");
        run = 0;
        phase = 0;
        last = System.nanoTime();
    }

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        long dt = now - last;
        last = now;
        place(mc);
        double since = (now - phaseAt) / 1e9;
        switch (phase) {
            case 0 -> {
                if (run >= SCENES.length * 2 * REPS) {
                    done();
                    return;
                }
                MiniNukeCloud.forced = modeB();
                flatten();
                phaseAt = now;
                phase = 1;
            }
            case 1 -> {
                // the floor redrawn and the last run's cloud and particles gone
                if (since < 3 || torexes(mc) > 0) return;
                TIMES.clear();
                phaseAt = now;
                phase = 2;
            }
            case 2 -> {
                TIMES.add(dt);
                if (since < 2) return;
                baseMs = mean(TIMES);
                shoot(label() + "-base");
                TIMES.clear();
                clouds = puffs = wrongType = minesFired = 0;
                scale = top = ring = 0;
                shot1 = shot3 = false;
                java.util.Arrays.fill(COLOUR, Double.NaN);
                java.util.Arrays.fill(RING_R, Double.NaN);
                java.util.Arrays.fill(RING_N, -1);
                trigger(0);
                triggerAt = phaseAt = now;
                phase = 3;
            }
            case 3 -> {
                TIMES.add(dt);
                if (scene().equals("mines8")) while (minesFired < 8 && since >= minesFired * 0.25) trigger(minesFired);
                measure(mc);
                for (int i = 0; i < RING_AT.length; i++) if (RING_N[i] < 0 && since >= RING_AT[i]) ring(mc, i);
                if (!shot1 && since >= 1) {
                    colour(mc, 0);
                    shot1 = true;
                    shoot(label() + "-1s");
                }
                if (!shot3 && since >= 3) {
                    colour(mc, 4);
                    shot3 = true;
                    shoot(label() + "-3s");
                }
                if (since < 8) return;
                record();
                run++;
                phase = 0;
            }
            default -> {
            }
        }
    }

    private static void place(Minecraft mc) {
        if (mc.player == null) return;
        mc.player.setPos(0.5, Y + 6 - mc.player.getEyeHeight(), -39.5);
        mc.player.setYRot(0F);
        mc.player.setXRot(-8F);
        mc.player.yRotO = 0F;
        mc.player.xRotO = -8F;
        mc.player.setDeltaMovement(Vec3.ZERO);
    }

    private static void flatten() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            var sp = server.getPlayerList().getPlayers().getFirst();
            sp.teleportTo(level, 0.5, Y + 6 - sp.getEyeHeight(), -39.5, Set.of(), 0F, -8F, false);
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int x = -64; x <= 64; x++) for (int z = -40; z <= 48; z++) {
                for (int y = Y - 10; y < Y; y++) level.setBlock(p.set(x, y, z), Blocks.STONE.defaultBlockState(), 2);
                level.setBlock(p.set(x, Y - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
                for (int y = Y; y <= Y + 24; y++) level.setBlock(p.set(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }).join();
    }

    // the blast of this run's scene through NTM's code (mines8: mine i of the row)
    private static void trigger(int i) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        String scene = scene();
        boolean b = modeB();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            double x = 0.5, y = Y, z = 0.5;
            try {
                switch (scene) {
                    case "mine" -> fatMine(level, BlockPos.containing(x, y, z));
                    case "mines8" -> fatMine(level, BlockPos.containing(-41.5 + 12 * i, y, z));
                    case "fatman" -> catapult(level, XFactoryCatapult.nuke_standard, XFactoryCatapult.LAMBDA_NUKE_STANDARD, x, y, z);
                    case "volley" -> {
                        var rand = level.getRandom();
                        for (int k = 0; k < 8; k++) catapult(level, XFactoryCatapult.nuke_tots, XFactoryCatapult.LAMBDA_NUKE_TINYTOT,
                                x + rand.nextGaussian() * 4, y, z + rand.nextGaussian() * 4);
                    }
                    case "arty" -> ExplosionNukeSmall.explode(level, x, y, z, ExplosionNukeSmall.PARAMS_MEDIUM);
                    // past the simulation distance (5 chunks from the camera's): NTM's muke in both modes
                    case "far" -> fatMine(level, BlockPos.containing(x, y, 72.5));
                    case "bmg50" -> {
                        // the nuclear .50's call: the cloud (MiniNukeCloud.spawn, size 8); A: NTM's muke as the Fat Mine
                        if (b) MiniNukeCloud.spawn(level, x, y + 0.5, z, 8F, false);
                        else PacketWire.sendToAllAround(new MukePayload(x, y + 0.5, z, false, false), new TargetPoint(level, x, y, z, 250));
                    }
                    default -> {
                    }
                }
            } catch (Exception e) {
                ClientSmoke.check(false, "мини-нюк: " + scene + ": " + e);
            }
        }).join();
        if (scene.equals("mines8")) minesFired = i + 1;
    }

    private static void fatMine(ServerLevel level, BlockPos pos) throws Exception {
        BlockLandmineFat mine = ModBlocks.MINE_FAT.get();
        Method m = BlockLandmineFat.class.getDeclaredMethod("explodeVariant", net.minecraft.world.level.Level.class, BlockPos.class);
        m.setAccessible(true);
        m.invoke(mine, level, pos);
    }

    private static void catapult(ServerLevel level, BulletConfig config, java.util.function.BiConsumer<EntityBulletBaseMK4, net.minecraft.world.phys.HitResult> impact,
            double x, double y, double z) {
        EntityBulletBaseMK4 bullet = new EntityBulletBaseMK4(level, config, 100F, 0F, 0F, 90F);
        bullet.setPos(x, y + 0.2, z);
        impact.accept(bullet, new BlockHitResult(new Vec3(x, y, z), Direction.UP, BlockPos.containing(x, y - 1, z), false));
    }

    private static int torexes(Minecraft mc) {
        int n = 0;
        if (mc.level != null) for (Entity e : mc.level.entitiesForRendering()) if (e instanceof EntityNukeTorex) n++;
        return n;
    }

    private static void measure(Minecraft mc) {
        if (mc.level == null) return;
        int seen = 0, all = 0;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof EntityNukeTorex t)) continue;
            seen++;
            if (!MiniNukeCloud.mini(t)) wrongType++;
            scale = Math.max(scale, t.getScaleVal());
            all += t.cloudlets.size();
            for (EntityNukeTorex.Cloudlet c : t.cloudlets) {
                if (c.type == EntityNukeTorex.TorexType.SHOCK) ring = Math.max(ring, Math.hypot(c.posX - t.getX(), c.posZ - t.getZ()));
                else top = Math.max(top, c.posY + c.getScale() - Y);
            }
        }
        clouds = Math.max(clouds, seen);
        puffs = Math.max(puffs, all);
    }

    // the ground within 20 blocks of the blast as the run ends: blocks of NTM's waste earth and of grass left (the
    // ground is grass a block under Y; the crater's air neither)
    private static int[] ground() {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return new int[] {-1, -1};
        return server.submit(() -> {
            ServerLevel level = server.overworld();
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            int waste = 0, grass = 0;
            for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) {
                if (x * x + z * z > 400) continue;
                for (int y = Y - 6; y < Y; y++) {
                    var b = level.getBlockState(p.set(x, y, z));
                    if (b.is(Blocks.GRASS_BLOCK)) grass++;
                    else if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b.getBlock()).getPath().equals("waste_earth")) waste++;
                }
            }
            return new int[] {waste, grass};
        }).join();
    }

    private static void ring(Minecraft mc, int at) {
        double r = 0;
        int n = 0;
        if (mc.level != null) for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof EntityNukeTorex t)) continue;
            for (EntityNukeTorex.Cloudlet c : t.cloudlets) {
                if (c.type != EntityNukeTorex.TorexType.SHOCK) continue;
                r = Math.max(r, Math.hypot(c.posX - t.getX(), c.posZ - t.getZ()));
                n++;
            }
        }
        RING_R[at] = r;
        RING_N[at] = n;
        java.util.List<Double> ds = new java.util.ArrayList<>();
        if (mc.level != null) for (Entity e : mc.level.entitiesForRendering())
            if (e instanceof EntityNukeTorex t) for (EntityNukeTorex.Cloudlet c : t.cloudlets)
                if (c.type == EntityNukeTorex.TorexType.SHOCK) ds.add(Math.hypot(c.posX - t.getX(), c.posZ - t.getZ()));
        java.util.Collections.sort(ds);
        double mid = ds.isEmpty() ? Double.NaN : ds.get(ds.size() / 2);
        long in = ds.stream().filter(d -> Math.abs(d - mid) <= 2).count();
        RING_MID[at] = mid;
        RING_BAND[at] = ds.isEmpty() ? Double.NaN : (double) in / ds.size();
    }

    private static void colour(Minecraft mc, int at) {
        if (mc.level == null) return;
        double heat = Double.NaN, r = 0, g = 0, b = 0;
        int n = 0;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof EntityNukeTorex t)) continue;
            heat = t.heat;
            for (EntityNukeTorex.Cloudlet c : t.cloudlets) {
                if (c.type == EntityNukeTorex.TorexType.SHOCK) continue;
                r += c.colorR;
                g += c.colorG;
                b += c.colorB;
                n++;
            }
        }
        COLOUR[at] = heat;
        COLOUR[at + 1] = n == 0 ? Double.NaN : r / n;
        COLOUR[at + 2] = n == 0 ? Double.NaN : g / n;
        COLOUR[at + 3] = n == 0 ? Double.NaN : b / n;
    }

    private static void record() {
        long[] t = TIMES.stream().mapToLong(Long::longValue).sorted().toArray();
        double ms = mean(TIMES), p95 = t.length == 0 ? 0 : t[Math.min(t.length - 1, (int) (t.length * 0.95))] / 1e6;

        String mode = modeB() ? "B" : "A";
        int[] g = ground();
        ROWS.add(String.format(Locale.ROOT, "%s\t%s\t%d\t%.2f\t%.2f\t%.2f\t%.0f\t%d\t%.3f\t%d\t%.1f\t%.1f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.3f\t%.1f\t%.1f\t%.1f\t%.1f\t%d\t%d\t%d\t%d\t%.1f\t%.1f\t%.1f\t%.1f\t%.2f\t%.2f\t%.2f\t%.2f\t%d\t%d", scene(), mode, rep(), baseMs, ms, p95,
                ms > 0 ? 1000 / ms : 0, clouds, scale, puffs, top, ring, COLOUR[0], COLOUR[1], COLOUR[2], COLOUR[3], COLOUR[4], COLOUR[5], COLOUR[6], COLOUR[7], RING_R[0], RING_R[1], RING_R[2], RING_R[3], RING_N[0], RING_N[1], RING_N[2], RING_N[3], RING_MID[0], RING_MID[1], RING_MID[2], RING_MID[3], RING_BAND[0], RING_BAND[1], RING_BAND[2], RING_BAND[3], g[0], g[1]));
        GunFeel.LOGGER.info("[mininuke] {}", ROWS.getLast());
        if (modeB()) {
            if (scene().equals("far")) {
                ClientSmoke.check(clouds == 0, "мини-нюк: " + label() + " за дистанцией симуляции облака нет, остаются частицы NTM (" + clouds + ")");
                return;
            }
            int want = scene().equals("mines8") ? 8 : 1;
            ClientSmoke.check(clouds >= 1 && clouds <= want, "мини-нюк: " + label() + " облаков " + clouds + " (ждали 1.." + want + ")");
            ClientSmoke.check(wrongType == 0, "мини-нюк: " + label() + " облако не мини-типа");
            ClientSmoke.check(Math.abs(scale - want(scene())) < 1e-3, "мини-нюк: " + label() + " масштаб " + scale + " (ждали " + want(scene()) + ")");
        } else {
            ClientSmoke.check(clouds == 0, "мини-нюк: " + label() + " без ключа облако NTM-бомбы (" + clouds + ")");
        }
    }

    private static double mean(List<Long> times) {
        return times.isEmpty() ? 0 : times.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6;
    }

    private static void shoot(String name) {
        Path file = Path.of(DIR, name + ".png");
        net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(file);
            } catch (Exception e) {
                GunFeel.LOGGER.error("[mininuke] {}", file, e);
            }
        });
    }

    private static void done() {
        MiniNukeCloud.forced = null;
        try {
            Files.write(Path.of(DIR, "runs.tsv"), ROWS);
        } catch (Exception e) {
            ClientSmoke.check(false, "мини-нюк: запись " + e);
        }
        GunFeel.LOGGER.info("[mininuke] done {}", Arrays.toString(SCENES));
        ClientSmoke.finish();
    }
}
