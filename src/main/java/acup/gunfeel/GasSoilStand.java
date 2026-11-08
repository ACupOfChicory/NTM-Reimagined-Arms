package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.gas.GasKind;
import acup.gunfeel.gas.GasPoison;
import acup.gunfeel.gas.client.GasStains;
import acup.gunfeel.gl54.Gl54Ammo;
import acup.gunfeel.gl54.Gl54Grenade;
import acup.gunfeel.smoke.SmokeClouds;
import acup.gunfeel.smoke.SmokeGrid;
import acup.gunfeel.smoke.client.SmokeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

// runCheckClient -PgasStand=<dir> -PgasSoil: the mustard's stains and poisoned ground and the gas stirred by bullets and a
// blast, in numbers (<dir>/soil.txt; the shots stain-*.png and field-*.png for gas-soil.py):
//   1. stains: one mustard grenade on a concrete pad; the pad seen from above before and after (the cloud gone), the
//      client's stains then;
//   2. poison: 16 mustard grenades a second apart on a grass field with flowers; the sum and the biome at the middle every
//      second, which biome cells turned (only those the gas lay on), the plants left on them, a sapling, grass and bone meal
//      there and out of it; the field from above before and after;
//   3. stir: two chlorine clouds side by side, one shot through ten times and then hit by a GL-54 HE grenade; the gas in
//      each against the other, and the client's copies against the server's.
final class GasSoilStand {
    static final boolean ON = Boolean.getBoolean("ntmgunfeel.gasSoil");
    private static final String DIR = GasStand.DIR;
    private static final int Y = 100;
    private static final Vec3 PAD = new Vec3(50.5, Y + 0.5, 0.5), FIELD = new Vec3(0.5, Y + 0.5, 0.5);
    private static final Vec3 STIRRED = new Vec3(60.5, Y + 0.5, 0.5), CONTROL = new Vec3(60.5, Y + 0.5, 36.5);
    private static final List<String> OUT = new ArrayList<>();
    private static int step, wait, frames, fired, turnedAt = -1, stirredId = -1, controlId = -1;
    private static float sumAtTurn;
    private static long t0;
    private static final Set<Long> WET = new HashSet<>();
    private static boolean done;

    private GasSoilStand() {
    }

    private static void line(String s) {
        OUT.add(s);
        GunFeel.LOGGER.info("[gassoil] {}", s);
    }

    private static MinecraftServer server() {
        return Minecraft.getInstance().getSingleplayerServer();
    }

    private static void cmd(String c) {
        server().getCommands().performPrefixedCommand(server().createCommandSourceStack().withSuppressedOutput(), c);
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        if (!mc.gui.hud.isHidden()) mc.gui.hud.toggle();
        try {
            Files.createDirectories(Path.of(DIR));
        } catch (Exception e) {
            ClientSmoke.check(false, "почва: папка " + e);
        }
        server().submit(() -> {
            ServerLevel level = server().overworld();
            cmd("time set noon");
            cmd("weather clear");
            var rules = level.getGameRules();
            rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME, false, server());
            rules.set(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER, false, server());
            rules.set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, server());
            rules.set(net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED, 0, server());
            BlockState concrete = Blocks.CONCRETE.pick(DyeColor.LIGHT_GRAY).defaultBlockState();
            for (int x = -40; x <= 90; x++) for (int z = -40; z <= 60; z++) {
                boolean field = x <= 30;
                for (int y = Y - 4; y < Y; y++)
                    level.setBlock(new BlockPos(x, y, z), field ? (y == Y - 1 ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.DIRT.defaultBlockState()) : concrete, 2);
                for (int y = Y; y <= Y + 14; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                if (field && Math.floorMod(x * 7 + z * 3, 3) == 0) level.setBlock(new BlockPos(x, Y, z), (x + z) % 2 == 0 ? Blocks.POPPY.defaultBlockState() : Blocks.SHORT_GRASS.defaultBlockState(), 2);
            }
            ServerPlayer p = server().getPlayerList().getPlayers().getFirst();
            p.setGameMode(GameType.SPECTATOR);
        }).join();
        t0 = level().getGameTime();
    }

    private static ServerLevel level() {
        return server().overworld();
    }

    // the camera above a point, looking straight down
    private static void above(Vec3 at, double height) {
        Minecraft mc = Minecraft.getInstance();
        server().submit(() -> {
            var sp = server().getPlayerList().getPlayers().getFirst();
            sp.teleportTo(level(), at.x, at.y + height, at.z, Set.of(), 0F, 90F, false);
        }).join();
        if (mc.player != null) {
            mc.player.setPos(at.x, at.y + height, at.z);
            mc.player.setYRot(0F);
            mc.player.setXRot(90F);
            mc.player.yRotO = 0F;
            mc.player.xRotO = 90F;
        }
    }

    private static void shot(String name) {
        Path file = Path.of(DIR, name + ".png");
        net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(file);
            } catch (Exception e) {
                GunFeel.LOGGER.error("[gassoil] {}", file, e);
            }
        });
    }

    private static int spawn(Vec3 at, GasKind g) {
        return server().submit(() -> SmokeClouds.spawn(level(), at, new Vec3(0, -1, 0), g.smoke(), 1F)).join();
    }

    private static boolean noClouds() {
        return server().submit(() -> {
            SmokeClouds all = SmokeClouds.get(level());
            return all == null || all.isEmpty();
        }).join();
    }

    static void frame() {
        if (done) return;
        frames++;
        if (wait > 0) {
            wait--;
            return;
        }
        try {
            switch (step) {
                case 0 -> { above(PAD, 6); wait = 60; step++; }
                case 1 -> { shot("stain-before"); wait = 10; step++; }
                case 2 -> { line("stain: mustard cloud " + spawn(PAD, GasKind.MUSTARD)); step++; }
                case 3 -> {
                    if (!noClouds()) return;
                    wait = 40;
                    step++;
                }
                case 4 -> { stains("stain", PAD); shot("stain-after"); wait = 10; step++; }
                case 5 -> { above(FIELD, 8); wait = 60; step++; }
                case 6 -> { shot("field-before"); plants("before"); step++; }
                case 7 -> poisonStep();
                case 8 -> {
                    poisonLog();
                    if (!noClouds()) return;
                    server().submit(GasSoilStand::poisonResult).join();
                    wait = 80;
                    step++;
                }
                case 9 -> { shot("field-after"); stains("field", FIELD); step++; }
                case 10 -> {
                    stirredId = spawn(STIRRED, GasKind.CHLORINE);
                    controlId = spawn(CONTROL, GasKind.CHLORINE);
                    wait = 0;
                    step++;
                }
                case 11 -> stirStep();
                default -> finish();
            }
        } catch (RuntimeException e) {
            GunFeel.LOGGER.error("[gassoil]", e);
            ClientSmoke.check(false, "почва: " + e);
            finish();
        }
    }

    // the client's stains near a point: how many and how strong
    private static void stains(String what, Vec3 at) {
        int n = 0, near = 0, far = 0;
        float most = 0F;
        for (float[] s : GasStains.snapshot()) {
            if (s[3] <= 0F) continue;
            n++;
            double d = Math.hypot(s[0] + 0.5 - at.x, s[2] + 0.5 - at.z);
            if (d < 6) near++;
            if (d > 14) far++;
            most = Math.max(most, s[3]);
        }
        line(String.format(Locale.ROOT, "%s: stains shown %d, within 6 blocks %d, past 14 %d, strongest %.2f", what, n, near, far, most));
        if (what.equals("stain")) {
            ClientSmoke.check(near >= 9, "почва: пятна иприта под облаком " + near);
            ClientSmoke.check(far == 0, "почва: пятен вдали от облака нет " + far);
        }
    }

    // ---- the poisoned field

    private static int lastFire = -1000, lastLog = -1;

    private static void poisonStep() {
        long now = level().getGameTime();
        int t = (int) (now - t0);
        if (fired < 16 && t - lastFire >= 20) {
            lastFire = t;
            fired++;
            spawn(FIELD, GasKind.MUSTARD);
        }
        poisonLog();
        if (fired >= 16) step++;
    }

    // every frame: the biome cells that had gas on their floor; every second the sum and the biome at the middle
    private static void poisonLog() {
        int t = (int) (level().getGameTime() - t0);
        server().submit(() -> {
            SmokeClouds all = SmokeClouds.get(level());
            if (all != null) for (SmokeClouds.Cloud c : all.clouds()) for (int i : c.grid.floorCells(GasPoison.LEAST))
                WET.add(key(c.baseX() + i % SmokeGrid.SX, c.baseZ() + i / SmokeGrid.SX % SmokeGrid.SZ));
        }).join();
        if (t % 20 == 0 && t != lastLog) server().submit(() -> {
            lastLog = t;
            ServerLevel level = level();
            float sum = GasPoison.sumAt(level, 0, 0);
            boolean poisoned = GasPoison.poisoned(level, new BlockPos(0, Y, 0));
            if (poisoned && turnedAt < 0) {
                turnedAt = fired;
                sumAtTurn = sum;
            }
            line(String.format(Locale.ROOT, "poison: t %d s, grenades %d, sum at the middle %.0f, poisoned %s", t / 20, fired, sum, poisoned));
        }).join();
    }

    private static long key(int x, int z) {
        return (long) QuartPos.fromBlock(x) << 32 | QuartPos.fromBlock(z) & 0xFFFFFFFFL;
    }

    private static void plants(String when) {
        int[] n = server().submit(() -> {
            int in = 0, out = 0;
            for (int x = -30; x <= 30; x++) for (int z = -30; z <= 30; z++) {
                BlockState s = level().getBlockState(new BlockPos(x, Y, z));
                if (!GasPoison.plant(s)) continue;
                if (GasPoison.poisoned(level(), new BlockPos(x, Y, z))) in++;
                else out++;
            }
            return new int[] {in, out};
        }).join();
        line("plants " + when + ": on poisoned ground " + n[0] + ", elsewhere " + n[1]);
        if (when.equals("after")) ClientSmoke.check(n[0] == 0, "почва: на отравленной земле растений нет " + n[0]);
    }

    // on the server
    private static void poisonResult() {
        ServerLevel level = level();
        int cells = 0, dry = 0;
        for (int qx = QuartPos.fromBlock(-30); qx <= QuartPos.fromBlock(30); qx++) for (int qz = QuartPos.fromBlock(-30); qz <= QuartPos.fromBlock(30); qz++) {
            BlockPos at = new BlockPos(QuartPos.toBlock(qx), Y, QuartPos.toBlock(qz));
            if (!GasPoison.poisoned(level, at)) continue;
            cells++;
            if (!WET.contains((long) qx << 32 | qz & 0xFFFFFFFFL)) dry++;
        }
        boolean middle = GasPoison.poisoned(level, new BlockPos(0, Y, 0)), off = GasPoison.poisoned(level, new BlockPos(24, Y, 24));
        line(String.format(Locale.ROOT, "poison: turned after grenade %d at sum %.0f; biome cells poisoned %d (16 blocks each), of them never under the gas %d; middle %s, 24 blocks off %s",
                turnedAt, sumAtTurn, cells, dry, middle, off));
        ClientSmoke.check(turnedAt > 8, "почва: магазина (8) мало, повернуло на гранате " + turnedAt);
        ClientSmoke.check(middle && !off, "почва: биом в середине, не в 24 блоках");
        ClientSmoke.check(dry == 0, "почва: биом только под газом, вне его " + dry);
        // growth: a sapling, grass on dirt and bone meal there and out of it
        var random = level.getRandom();
        String[] where = {"poisoned", "clean"};
        BlockPos[] at = {new BlockPos(0, Y, 0), new BlockPos(24, Y, 24)};
        for (int k = 0; k < 2; k++) {
            BlockPos p = at[k];
            level.setBlock(p.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            level.setBlock(p, Blocks.OAK_SAPLING.defaultBlockState(), 3);
            for (int i = 0; i < 400 && level.getBlockState(p).is(Blocks.OAK_SAPLING); i++) level.getBlockState(p).randomTick(level, p, random);
            String sapling = level.getBlockState(p).getBlock().toString();
            BlockPos dirt = p.east().below();
            level.setBlock(dirt.above(), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(dirt, Blocks.DIRT.defaultBlockState(), 3);
            BlockPos grass = p.west().below();
            level.setBlock(grass.above(), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(grass, Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            for (int i = 0; i < 2000 && level.getBlockState(dirt).is(Blocks.DIRT); i++) {
                level.getBlockState(grass).randomTick(level, grass, random);
                for (BlockPos q : BlockPos.betweenClosed(dirt.offset(-1, -1, -1), dirt.offset(1, 1, 1))) if (level.getBlockState(q).is(Blocks.GRASS_BLOCK)) level.getBlockState(q).randomTick(level, q, random);
            }
            String spread = level.getBlockState(dirt).getBlock().toString();
            BlockPos meal = p.north();
            level.setBlock(meal.below(), Blocks.GRASS_BLOCK.defaultBlockState(), 3);
            level.setBlock(meal, Blocks.OAK_SAPLING.defaultBlockState(), 3);
            ServerPlayer sp = server().getPlayerList().getPlayers().getFirst();
            boolean took = net.minecraft.world.item.BoneMealItem.applyBonemeal(new ItemStack(Items.BONE_MEAL, 64), level, meal, sp);
            line(String.format(Locale.ROOT, "growth %s: sapling after 400 random ticks %s; dirt beside grass after 2000 %s; bone meal took %s",
                    where[k], sapling, spread, took));
            if (k == 0) {
                ClientSmoke.check(!level.getBlockState(p).is(Blocks.OAK_SAPLING) && !level.getBlockState(p).is(Blocks.OAK_LOG), "почва: саженец погиб, не вырос");
                ClientSmoke.check(level.getBlockState(dirt).is(Blocks.DIRT), "почва: трава не расползлась");
                ClientSmoke.check(!took, "почва: костная мука не берёт");
            } else {
                ClientSmoke.check(level.getBlockState(p).is(Blocks.OAK_LOG), "почва: вне яда саженец вырос");
                ClientSmoke.check(level.getBlockState(dirt).is(Blocks.GRASS_BLOCK), "почва: вне яда трава расползлась");
            }
        }
        // the plants (counted on the client's thread through the server again)
        int in = 0, out = 0;
        for (int x = -30; x <= 30; x++) for (int z = -30; z <= 30; z++) {
            BlockPos q = new BlockPos(x, Y, z);
            if (Math.abs(x) <= 1 && Math.abs(z) <= 1 || Math.abs(x - 24) <= 1 && Math.abs(z - 24) <= 1) continue;
            if (!GasPoison.plant(level.getBlockState(q))) continue;
            if (GasPoison.poisoned(level, q)) in++;
            else out++;
        }
        line("plants after: on poisoned ground " + in + ", elsewhere " + out);
        ClientSmoke.check(in == 0, "почва: на отравленной земле растений нет " + in);
        ClientSmoke.check(out > 100, "почва: вне яда растения на месте " + out);
    }

    // ---- the stir

    private static int sStep, sTick, bullets;
    private static float base;

    private static float[] totals() {
        return server().submit(() -> {
            SmokeClouds all = SmokeClouds.get(level());
            SmokeClouds.Cloud a = all == null ? null : all.cloud(stirredId), b = all == null ? null : all.cloud(controlId);
            return new float[] {a == null ? 0F : a.grid.total(), b == null ? 0F : b.grid.total(), a == null ? -1 : a.grid.age()};
        }).join();
    }

    private static float[] clientTotals() {
        SmokeClouds all = SmokeClient.current();
        SmokeClouds.Cloud a = all == null ? null : all.cloud(stirredId);
        return new float[] {a == null ? 0F : a.grid.total(), a == null ? -1 : a.grid.age()};
    }

    private static BulletConfig rifle() {
        for (BulletConfig c : BulletConfig.configs) if (c != null && c.velocity >= 5F && c.onImpact == null && c.spread <= 0.01F) return c;
        for (BulletConfig c : BulletConfig.configs) if (c != null && c.velocity >= 5F && c.onImpact == null) return c;
        return null;
    }

    private static void stirStep() {
        sTick++;
        switch (sStep) {
            case 0 -> {
                if (sTick < 60) return;
                float[] t = totals();
                base = t[0] / Math.max(1e-6F, t[1]);
                line(String.format(Locale.ROOT, "stir: at age %.0f the shot cloud has %.1f, the other %.1f (ratio %.3f)", t[2], t[0], t[1], base));
                SmokeClient.matched = SmokeClient.apart = SmokeClient.grids = 0;
                sStep++;
                sTick = 0;
            }
            case 1 -> {
                if (sTick % 2 != 0) return;
                BulletConfig c = rifle();
                server().submit(() -> {
                    ServerPlayer sp = server().getPlayerList().getPlayers().getFirst();
                    var b = new EntityBulletBaseMK4(level(), sp, c, 1F, 0F, STIRRED.x - 14, Y + 1.0, STIRRED.z, 1, 0, 0);
                    level().addFreshEntity(b);
                }).join();
                if (++bullets >= 10) {
                    sStep++;
                    sTick = 0;
                }
            }
            case 2 -> {
                if (sTick < 20) return;
                float[] t = totals();
                float r = t[0] / Math.max(1e-6F, t[1]) / base;
                line(String.format(Locale.ROOT, "stir: after 10 shots (%s) the shot cloud keeps %.3f of what the other does", rifle() == null ? "none" : rifle().id, r));
                ClientSmoke.check(r < 0.97F && r > 0.5F, "почва: очередь сквозь газ взяла немного " + r);
                base = t[0] / Math.max(1e-6F, t[1]);
                server().submit(() -> {
                    ServerPlayer sp = server().getPlayerList().getPlayers().getFirst();
                    Vec3 from = new Vec3(STIRRED.x, Y + 4.0, STIRRED.z - 4.0);
                    Vec3 dir = new Vec3(0, -1, 1).normalize();
                    level().addFreshEntity(new EntityBulletBaseMK4(level(), sp, Gl54Ammo.he(), Gl54Grenade.BASE_DAMAGE, 0F, from.x, from.y, from.z, dir.x, dir.y, dir.z));
                }).join();
                sStep++;
                sTick = 0;
            }
            case 3 -> {
                if (sTick < 30) return;
                float[] t = totals();
                float r = t[0] / Math.max(1e-6F, t[1]) / base;
                line(String.format(Locale.ROOT, "stir: after a GL-54 HE grenade in it the shot cloud keeps %.3f of what the other does", r));
                ClientSmoke.check(r < 0.6F, "почва: фугас выдул больше 40% " + r);
                sStep++;
                sTick = 0;
            }
            case 4 -> {
                if (sTick < 120) return;
                float[] s = totals(), c = clientTotals();
                line(String.format(Locale.ROOT, "stir: server %.2f at age %.0f, client %.2f at age %.0f; hash checks matched %d, apart %d, grids sent %d",
                        s[0], s[2], c[0], c[1], SmokeClient.matched, SmokeClient.apart, SmokeClient.grids));
                float rel = Math.abs(s[0] - c[0]) / Math.max(1e-3F, s[0]);
                ClientSmoke.check(rel < 0.1F, "почва: клиент и сервер сошлись после разгона " + rel);
                sStep++;
            }
            default -> step++;
        }
    }

    private static void finish() {
        if (done) return;
        done = true;
        try {
            Files.write(Path.of(DIR, "soil.txt"), OUT);
        } catch (Exception e) {
            ClientSmoke.check(false, "почва: запись " + e);
        }
        ClientSmoke.finish();
    }
}
