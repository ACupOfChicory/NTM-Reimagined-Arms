package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.entity.projectile.EntityBulletBeamBase;
import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.XFactory9mm;
import com.hbm.items.weapon.sedna.factory.XFactoryEnergy;
import com.hbm.items.weapon.sedna.factory.XFactoryNPC;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

// runCheckClient -PsuppressionStand: suppression under fire (Suppression). A flat world, the player in creative mode
// looking along +Z, a mannequin the shooter of everything not the player's own. Each case starts from an empty scale
// and is watched 40 ticks: the events counted, the scale's level each tick, its top.
//  - a burst of 8 rounds of 9 mm past the player 2, 8 and 16 blocks to the side: only at 2 it counts;
//  - one round into a wall 4.5 blocks to the side (it counts once), then 4;
//  - NTM's hand grenade (size 7.5) 4, 10, 14 blocks ahead, vanilla's TNT blast 10 ahead, the player's own grenade at 4;
//  - a laser beam and a mob's laser bolt 2 blocks past, the player's own rounds 1 block past: nothing;
//  - the sounds: the game's own volume of a world sound and a gun's at an empty and a full scale (MixinSuppressionMuffle);
//  - the screen at an empty and a full scale, the darkening by pixels (never more than 40 %);
//  - the flinch off (the view does not move) and on (suppressionFlinch = true: it moves 0.11 to 0.66° an event).
// Lines "[suppression-stand] ..." in the log, PASS/FAIL in the report.
final class SuppressionStand {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.suppressionStand");
    private static final BlockPos O = new BlockPos(8, 100, 8);
    private static final double EX = 8.5, EZ = 8.5;
    private static final String[] KEYS = {"suppression", "suppressionPass", "suppressionImpact", "suppressionBlast", "suppressionPassRange",
            "suppressionImpactRange", "suppressionBlastRange", "suppressionRecovery", "suppressionDarken", "suppressionMuffle",
            "suppressionFlinch", "suppressionFlinchDeg"};

    private record Case(String name, Runnable fire, int minEvents, int maxEvents, float minTop, float maxTop) {
    }

    private static final List<Case> CASES = new ArrayList<>();
    private static int step, until, index, watchTick, events0;
    private static float top;
    private static final StringBuilder SERIES = new StringBuilder();
    private static Mannequin shooter;
    private static String fileBefore;
    private static boolean fileExisted, prepared;
    private static float yaw0, pitch0;
    private static int flinch0;
    private static com.mojang.blaze3d.platform.NativeImage base;
    private static volatile com.mojang.blaze3d.platform.NativeImage shot;

    private SuppressionStand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        try {
            Path f = file();
            fileExisted = Files.exists(f);
            fileBefore = fileExisted ? Files.readString(f) : null;
            prepared = true;
            String[] kv = new String[KEYS.length * 2];
            for (int i = 0; i < KEYS.length; i++) kv[2 * i] = KEYS[i];
            keys(kv);
        } catch (Exception e) {
            fail("подготовка: " + e);
            return;
        }
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            for (int x = -24; x <= 24; x++) for (int z = -30; z <= 30; z++) {
                level.setBlock(O.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 0; y <= 8; y++) level.setBlock(O.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
            // the wall: a column of stone 4 blocks to the -X side, its face 4.5 blocks from the eyes
            for (int y = 0; y <= 3; y++) for (int z = -2; z <= 2; z++) level.setBlock(new BlockPos(4, O.getY() + y, 8 + z), Blocks.STONE.defaultBlockState(), 3);
            ServerPlayer p = player(server);
            p.setGameMode(GameType.CREATIVE);
            p.getAbilities().flying = false;
            p.teleportTo(level, EX, O.getY(), EZ, Set.of(), 0F, 0F, false);
            shooter = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
            shooter.snapTo(EX + 20, O.getY(), EZ - 20, 0F, 0F);
            shooter.setNoGravity(true);
            level.addFreshEntity(shooter);
        }).join();
        plan();
        step = 0;
        until = now() + 60;
    }

    private static void plan() {
        CASES.clear();
        for (double d : new double[] {2, 8, 16}) {
            boolean near = d <= 3.5;
            CASES.add(new Case("очередь мимо, " + (int) d + " бл", () -> burst(d, false, XFactory9mm.p9_fmj), near ? 8 : 0, near ? 8 : 0, near ? 60F : 0F, near ? 100F : 0F));
        }
        // one hit of 26 (24 × (1 − 4.5/8) × 2.5): the level shown tops lower, as the target melts while the level climbs
        CASES.add(new Case("удар в стену, 1 пуля", () -> wall(1), 1, 1, 14F, 26.5F));
        CASES.add(new Case("удар в стену, 4 пули", () -> wall(4), 4, 4, 60F, 100F));
        for (double d : new double[] {4, 10, 14}) {
            boolean in = d < 14;
            CASES.add(new Case("граната NTM, " + (int) d + " бл", () -> grenade(d, false), in ? 1 : 0, in ? 1 : 0, in ? 1F : 0F, in ? 60F : 0F));
        }
        CASES.add(new Case("взрыв TNT, 10 бл", SuppressionStand::tnt, 1, 1, 1F, 40F));
        CASES.add(new Case("своя граната, 4 бл", () -> grenade(4, true), 0, 0, 0F, 0F));
        CASES.add(new Case("луч лазера мимо, 2 бл", SuppressionStand::beam, 0, 0, 0F, 0F));
        CASES.add(new Case("лазерный заряд моба мимо, 2 бл", () -> burst(2, false, XFactoryNPC.maskman_bolt), 0, 0, 0F, 0F));
        CASES.add(new Case("свои пули мимо, 1 бл", () -> burst(1, true, XFactory9mm.p9_fmj), 0, 0, 0F, 0F));
    }

    // ---- what is fired

    private static void burst(double side, boolean own, BulletConfig round) {
        for (int i = 0; i < 8; i++) {
            int k = i;
            later(2 * i, () -> onServer(p -> {
                LivingEntity by = own ? p : shooter;
                ServerLevel level = (ServerLevel) p.level();
                double y = O.getY() + 1.62;
                var b = new EntityBulletBaseMK4(level, by, round, 1F, -round.spread, EX + side, y, EZ - 20 + 0.01 * k, 0, 0, 1);
                level.addFreshEntity(b);
                return null;
            }));
        }
    }

    private static void wall(int n) {
        for (int i = 0; i < n; i++) {
            int k = i;
            later(2 * i, () -> onServer(p -> {
                ServerLevel level = (ServerLevel) p.level();
                var round = XFactory9mm.p9_fmj;
                var b = new EntityBulletBaseMK4(level, shooter, round, 1F, -round.spread, EX - 20, O.getY() + 1.62, EZ + 0.4 * k - 0.6, 1, 0, 0);
                level.addFreshEntity(b);
                return null;
            }));
        }
    }

    private static void grenade(double ahead, boolean own) {
        onServer(p -> {
            new ExplosionVNT(p.level(), EX, O.getY() + 0.5, EZ + ahead, 7.5F, own ? p : null).explode();
            return null;
        });
    }

    private static void tnt() {
        onServer(p -> {
            p.level().explode(null, EX, O.getY() + 0.5, EZ + 10, 4F, Level.ExplosionInteraction.NONE);
            return null;
        });
    }

    private static void beam() {
        onServer(p -> {
            ServerLevel level = (ServerLevel) p.level();
            shooter.snapTo(EX + 2, O.getY(), EZ - 20, 0F, 0F);
            shooter.setYHeadRot(0F);
            var b = new EntityBulletBeamBase(shooter, XFactoryEnergy.energy_las, 1F);
            level.addFreshEntity(b);
            shooter.snapTo(EX + 20, O.getY(), EZ - 20, 0F, 0F);
            return null;
        });
    }

    // ---- the run

    private static final List<int[]> LATER_AT = new ArrayList<>();
    private static final List<Runnable> LATER = new ArrayList<>();

    private static void later(int ticks, Runnable r) {
        LATER_AT.add(new int[] {now() + ticks});
        LATER.add(r);
    }

    private static void runLater() {
        for (int i = 0; i < LATER.size(); i++) if (now() >= LATER_AT.get(i)[0]) {
            Runnable r = LATER.remove(i);
            LATER_AT.remove(i);
            i--;
            r.run();
        }
    }

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        try {
            runLater();
            if (step == 10 && now() != watchTick) watch();
            if (step == 43 && Suppression.flinches != lastFlinches) {
                lastFlinches = Suppression.flinches;
                minFlinch = Math.min(minFlinch, Suppression.lastFlinch);
                maxFlinch = Math.max(maxFlinch, Suppression.lastFlinch);
            }
            if (now() < until) return;
            switch (step) {
                case 0 -> next();
                case 10 -> {
                    Case c = CASES.get(index);
                    int n = Suppression.events - events0;
                    log("{}: событий {}, вершина {}, по тактам:{}", c.name, n, f(top), SERIES);
                    check(n >= c.minEvents && n <= c.maxEvents, c.name + ": событий " + n + " (ждали " + c.minEvents + "–" + c.maxEvents + ")");
                    check(top >= c.minTop - 1e-3F && top <= c.maxTop + 1e-3F, c.name + ": вершина шкалы " + f(top) + " (ждали " + f(c.minTop) + "–" + f(c.maxTop) + ")");
                    index++;
                    next();
                }
                case 20 -> sounds();
                case 30 -> {
                    // the screen with an empty scale
                    Suppression.clear();
                    Suppression.frozen = true;
                    // the stone floor, still: no clouds or sky moving between the two frames
                    mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
                    mc.player.setYRot(0F);
                    mc.player.setXRot(60F);
                    wait(10, 31);
                }
                case 31 -> grab(32);
                case 32 -> {
                    if (shot == null) return;
                    base = shot;
                    shot = null;
                    Suppression.set(100F);
                    wait(5, 33);
                }
                case 33 -> grab(34);
                case 34 -> {
                    if (shot == null) return;
                    try {
                        base.writeToFile(Path.of("suppression-empty.png"));
                        shot.writeToFile(Path.of("suppression-full.png"));
                    } catch (java.io.IOException e) {
                        GunFeel.LOGGER.warn("[suppression-stand] кадры не сохранены", e);
                    }
                    darkness(base, shot);
                    base.close();
                    shot.close();
                    shot = null;
                    Suppression.frozen = false;
                    Suppression.clear();
                    step = 40;
                }
                case 40 -> { // the flinch, off by default
                    yaw0 = mc.player.getYRot();
                    pitch0 = mc.player.getXRot();
                    flinch0 = Suppression.flinches;
                    burst(2, false, XFactory9mm.p9_fmj);
                    wait(30, 41);
                }
                case 41 -> {
                    float moved = Math.abs(mc.player.getYRot() - yaw0) + Math.abs(mc.player.getXRot() - pitch0);
                    log("рывок выключен: взгляд сдвинулся на {}°, рывков {}", f(moved), Suppression.flinches - flinch0);
                    check(moved < 1e-4F && Suppression.flinches == flinch0, "рывок по умолчанию выключен: взгляд сдвинулся на " + f(moved) + "°");
                    keys("suppressionFlinch", "true");
                    Suppression.clear();
                    wait(50, 42);
                }
                case 42 -> {
                    yaw0 = mc.player.getYRot();
                    pitch0 = mc.player.getXRot();
                    flinch0 = Suppression.flinches;
                    events0 = Suppression.events;
                    minFlinch = Float.MAX_VALUE;
                    maxFlinch = 0F;
                    lastFlinches = Suppression.flinches;
                    step = 43;
                    until = now() + 30;
                    burst(2, false, XFactory9mm.p9_fmj);
                }
                case 43 -> {
                    int fl = Suppression.flinches - flinch0, ev = Suppression.events - events0;
                    float dy = mc.player.getYRot() - yaw0, dp = mc.player.getXRot() - pitch0;
                    log("рывок включён: рывков {} на {} событий, от {}° до {}°, взгляд сдвинулся на {}° / {}°", fl, ev, f(minFlinch), f(maxFlinch), f(dy), f(dp));
                    check(fl == ev && fl > 0, "рывок включён: по рывку на событие (" + fl + " / " + ev + ")");
                    check(minFlinch >= 0.11F - 1e-4F && maxFlinch <= 0.66F + 1e-4F, "рывок 0,11–0,66°: от " + f(minFlinch) + " до " + f(maxFlinch));
                    check(Math.abs(dy) + Math.abs(dp) > 0.05F, "рывок сдвинул взгляд: " + f(dy) + "° / " + f(dp) + "°");
                    keys("suppressionFlinch", null);
                    restore();
                    ClientSmoke.finish();
                    step = 999;
                }
                default -> {
                }
            }
        } catch (Exception e) {
            GunFeel.LOGGER.error("[suppression-stand]", e);
            fail("исключение: " + e);
        }
    }

    private static float minFlinch, maxFlinch;
    private static int lastFlinches;

    private static void next() {
        if (index >= CASES.size()) {
            step = 20;
            return;
        }
        Suppression.clear();
        Minecraft.getInstance().player.setYRot(0F);
        Minecraft.getInstance().player.setXRot(0F);
        events0 = Suppression.events;
        top = 0F;
        SERIES.setLength(0);
        watchTick = now();
        CASES.get(index).fire.run();
        step = 10;
        until = now() + 40;
    }

    private static void watch() {
        watchTick = now();
        float l = Suppression.level();
        top = Math.max(top, l);
        SERIES.append(' ').append(Math.round(l));
    }

    // the game's own volume of a world sound and a gun's (SoundEngine.calculateVolume, where the mixin is), empty and full
    private static void sounds() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        var managerField = net.minecraft.client.sounds.SoundManager.class.getDeclaredField("soundEngine");
        managerField.setAccessible(true);
        Object engine = managerField.get(mc.getSoundManager());
        var calc = net.minecraft.client.sounds.SoundEngine.class.getDeclaredMethod("calculateVolume", SoundInstance.class);
        calc.setAccessible(true);
        SoundInstance world = sound("minecraft:block.note_block.harp", SoundSource.BLOCKS);
        SoundInstance gun = sound("hbm:weapon.fire.pistol", SoundSource.PLAYERS);
        SoundInstance music = sound("minecraft:music.game", SoundSource.MUSIC);
        Suppression.frozen = true;
        Suppression.clear();
        float w0 = (float) calc.invoke(engine, world), g0 = (float) calc.invoke(engine, gun), m0 = (float) calc.invoke(engine, music);
        Suppression.set(100F);
        float w1 = (float) calc.invoke(engine, world), g1 = (float) calc.invoke(engine, gun), m1 = (float) calc.invoke(engine, music);
        Suppression.set(50F);
        float w5 = (float) calc.invoke(engine, world);
        Suppression.frozen = false;
        Suppression.clear();
        log("громкость: мир {} → {} (половина шкалы {}), выстрел {} → {}, музыка {} → {}", f(w0), f(w1), f(w5), f(g0), f(g1), f(m0), f(m1));
        check(w0 > 0F && Math.abs(w1 / w0 - 0.4F) < 0.01F, "глушение: звуки мира на полной шкале 40 %: " + f(w0 > 0 ? w1 / w0 : -1));
        check(w0 > 0F && Math.abs(w5 / w0 - 0.7F) < 0.01F, "глушение: на половине шкалы 70 %: " + f(w0 > 0 ? w5 / w0 : -1));
        check(g0 > 0F && Math.abs(g1 / g0 - 0.79F) < 0.01F, "глушение: выстрел не глохнет в ноль, 79 %: " + f(g0 > 0 ? g1 / g0 : -1));
        check(m1 == m0, "музыка не глушится");
        step = 30;
    }

    private static SoundInstance sound(String id, SoundSource source) {
        var s = new SimpleSoundInstance(SoundEvent.createVariableRangeEvent(Identifier.parse(id)), source, 1F, 1F, RandomSource.create(1), EX, O.getY(), EZ);
        s.resolve(Minecraft.getInstance().getSoundManager());
        return s;
    }

    private static void grab(int next) {
        shot = null;
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> shot = image);
        step = next;
    }

    // the darkening by pixels: each pixel's brightness at a full scale over at an empty one (bright enough pixels only),
    // leaving out the crosshair (drawn inverting what is under it) and the hotbar (drawn over the darkening): the
    // darkest share, the middle's, and the edges' (the outermost rows and columns)
    private static void darkness(com.mojang.blaze3d.platform.NativeImage a, com.mojang.blaze3d.platform.NativeImage b) {
        int w = Math.min(a.getWidth(), b.getWidth()), h = Math.min(a.getHeight(), b.getHeight());
        double darkest = 1.0;
        int dx = -1, dy = -1, counted = 0;
        List<Double> all = new ArrayList<>();
        double[] sum = new double[5];
        int[] n = new int[5];
        for (int y = 0; y < h; y += 2) for (int x = 0; x < w; x += 2) {
            if (Math.abs(x - w / 2) < w / 12 && Math.abs(y - h / 2) < h / 12) continue; // the crosshair
            if (y > h * 3 / 4 && Math.abs(x - w / 2) < w / 3) continue; // the hotbar and the bars over it
            double l0 = lum(a.getPixel(x, y)), l1 = lum(b.getPixel(x, y));
            if (l0 < 40) continue;
            double k = l1 / l0;
            counted++;
            all.add(k);
            if (k < darkest) {
                darkest = k;
                dx = x;
                dy = y;
            }
            int region = Math.abs(x - w / 2) < w / 10 && Math.abs(y - h / 3) < h / 10 ? 0 : y < 4 ? 1 : y >= h - 4 ? 2 : x < 4 ? 3 : x >= w - 4 ? 4 : -1;
            // something of the interface drawn over the darkening there (the top right corner): not an edge's
            if (region >= 1 && k >= 0.95) region = -1;
            if (region >= 0) {
                sum[region] += k;
                n[region]++;
            }
        }
        double[] m = new double[5];
        for (int i = 0; i < 5; i++) m[i] = n[i] > 0 ? sum[i] / n[i] : -1;
        // a few pixels on a sharp edge of the floor move by a part of a pixel between the two frames: the darkest but
        // one in five hundred stands for the darkening
        java.util.Collections.sort(all);
        double p = all.isEmpty() ? -1 : all.get(all.size() / 500);
        log("затемнение: {} точек, самая тёмная {} яркости в {},{} (кадр {}×{}), темнее всех кроме 0,2 % — {}, середина {}, края: верх {}, низ {}, лево {}, право {}",
                counted, f(darkest), dx, dy, w, h, f(p), f(m[0]), f(m[1]), f(m[2]), f(m[3]), f(m[4]));
        check(counted > 1000, "кадр снят: " + counted + " точек");
        // a pixel's 8 bits round: up to 1.5 % off
        check(p >= 0.60 - 0.015, "нигде не темнее 40 %: темнее всех, кроме 0,2 % точек, " + f(p));
        check(Math.abs(m[0] - 0.78) < 0.03, "середина темнее на 22 %: " + f(m[0]));
        for (int i = 1; i < 5; i++) if (m[i] >= 0) check(m[i] < 0.625 && m[i] >= 0.585, "край " + i + " темнее на ~40 %: " + f(m[i]));
    }

    private static double lum(int abgr) {
        int r = abgr & 0xFF, g = abgr >> 8 & 0xFF, bl = abgr >> 16 & 0xFF;
        return 0.2126 * r + 0.7152 * g + 0.0722 * bl;
    }

    // ---- helpers

    private static void wait(int ticks, int next) {
        until = now() + ticks;
        step = next;
    }

    private static int now() {
        return Minecraft.getInstance().getSingleplayerServer().getTickCount();
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }

    private static <T> T onServer(java.util.function.Function<ServerPlayer, T> f) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        Object[] out = new Object[1];
        server.submit(() -> out[0] = f.apply(player(server))).join();
        @SuppressWarnings("unchecked")
        T t = (T) out[0];
        return t;
    }

    private static Path file() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve(ReaimTimes.FILE);
    }

    // the server's file with these keys set, or dropped for a null value (the rest as before the stand)
    private static void keys(String... kv) throws Exception {
        String base = fileBefore == null ? "" : fileBefore;
        StringBuilder add = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            base = base.replaceAll("(?m)^" + java.util.regex.Pattern.quote(kv[i]) + "\\s*=.*\\n?", "");
            if (kv[i + 1] != null) add.append(kv[i]).append(" = ").append(kv[i + 1]).append('\n');
        }
        Files.writeString(file(), base + (base.isEmpty() || base.endsWith("\n") ? "" : "\n") + add);
        Files.setLastModifiedTime(file(), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
    }

    private static void restore() {
        if (!prepared) return;
        try {
            if (fileExisted) Files.writeString(file(), fileBefore);
            else Files.deleteIfExists(file());
        } catch (Exception e) {
            GunFeel.LOGGER.warn("[suppression-stand] файл настроек не восстановлен", e);
        }
    }

    private static void fail(String text) {
        ClientSmoke.check(false, "подавление: " + text);
        restore();
        ClientSmoke.finish();
        step = 999;
    }

    private static void check(boolean ok, String text) {
        GunFeel.LOGGER.info("[suppression-stand] {} {}", ok ? "PASS" : "FAIL", text);
        ClientSmoke.check(ok, "подавление: " + text);
    }

    private static void log(String fmt, Object... args) {
        GunFeel.LOGGER.info("[suppression-stand] " + fmt, args);
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
