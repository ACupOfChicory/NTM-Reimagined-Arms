package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.entity.projectile.EntityBulletBeamBase;
import com.hbm.items.weapon.sedna.factory.XFactory9mm;
import com.hbm.items.weapon.sedna.factory.XFactoryEnergy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

// runCheckClient -PgunHitStand: a player hit by an NTM gun (GunHit). A flat world, the player in survival looking along
// +Z, a mannequin 6 blocks ahead the shooter. Each case: the player healed and still, one hit, 30 ticks watched each
// frame: the view's hurt tilt (as GameRenderer.bobHurt turns it), how far the player was moved, how far the view turned.
//  - a 9 mm round with the game's knockback and tilt given back, suppression on, then off: the same both times (the
//    suppression of X881 neither turns the view nor moves the player);
//  - a 9 mm round and a laser beam by default: no tilt, not moved, hurt all the same;
//  - a melee hit by default, alone and a second after a round: the tilt and the knockback as ever;
//  - a 9 mm round with only the tilt given back: it tilts, not moved.
// Lines "[gunhit-stand] ..." in the log, PASS/FAIL in the report.
final class GunHitStand {
    static final boolean ENABLED = Boolean.getBoolean("ntmgunfeel.gunHitStand");
    private static final BlockPos O = new BlockPos(8, 100, 8);
    private static final double EX = 8.5, EZ = 8.5;
    private static final String[] KEYS = {"gunHitKnockback", "gunHitTilt", "suppression", "suppressionFlinch"};

    private record Case(String name, String[] keys, Runnable hit, boolean tilts, boolean moves) {
    }

    private record Seen(float tilt, double moved, float turned, int hurt, float lost, int marks) {
    }

    private static final List<Case> CASES = new ArrayList<>();
    private static final List<Seen> SEEN = new ArrayList<>();
    private static int step, until, index;
    private static Mannequin shooter;
    private static String fileBefore;
    private static boolean fileExisted, prepared;
    private static Vec3 pos0;
    private static float yaw0, pitch0, tilt, turned, health0;
    private static double moved;
    private static int hurt, marks0;

    private GunHitStand() {
    }

    static void start() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        try {
            Path f = file();
            fileExisted = Files.exists(f);
            fileBefore = fileExisted ? Files.readString(f) : null;
            prepared = true;
        } catch (Exception e) {
            fail("подготовка: " + e);
            return;
        }
        var server = mc.getSingleplayerServer();
        server.submit(() -> {
            ServerLevel level = server.overworld();
            for (int x = -12; x <= 12; x++) for (int z = -12; z <= 16; z++) {
                level.setBlock(O.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 0; y <= 6; y++) level.setBlock(O.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
            }
            ServerPlayer p = player(server);
            p.setGameMode(GameType.SURVIVAL);
            p.getAbilities().flying = false;
            p.teleportTo(level, EX, O.getY(), EZ, Set.of(), 0F, 0F, false);
            shooter = EntityTypes.MANNEQUIN.create(level, EntitySpawnReason.COMMAND);
            shooter.snapTo(EX, O.getY(), EZ + 6, 180F, 0F);
            shooter.setYHeadRot(180F);
            shooter.setNoGravity(true);
            level.addFreshEntity(shooter);
        }).join();
        String[] old = {"gunHitKnockback", "true", "gunHitTilt", "true"};
        CASES.clear();
        CASES.add(new Case("пуля 9 мм, отброс и наклон вернули, подавление вкл", old, GunHitStand::round, true, true));
        CASES.add(new Case("пуля 9 мм, отброс и наклон вернули, подавление выкл", new String[] {"gunHitKnockback", "true", "gunHitTilt", "true",
                "suppression", "false"}, GunHitStand::round, true, true));
        CASES.add(new Case("пуля 9 мм, по умолчанию", new String[0], GunHitStand::round, false, false));
        CASES.add(new Case("луч лазера, по умолчанию", new String[0], GunHitStand::beam, false, false));
        CASES.add(new Case("удар рукой, по умолчанию", new String[0], GunHitStand::melee, true, true));
        CASES.add(new Case("пуля 9 мм, вернули только наклон", new String[] {"gunHitTilt", "true"}, GunHitStand::round, true, false));
        // after the bullet's invulnerability (the game's 20 ticks: a hit in them has no side effects at all)
        CASES.add(new Case("удар рукой через секунду после пули, по умолчанию", new String[0], () -> {
            round();
            later(22, GunHitStand::melee);
        }, true, true));
        step = 0;
        until = now() + 40;
    }

    // ---- the hits

    private static void round() {
        onServer(p -> {
            ServerLevel level = (ServerLevel) p.level();
            var r = XFactory9mm.p9_fmj;
            level.addFreshEntity(new EntityBulletBaseMK4(level, shooter, r, 1F, -r.spread, EX, O.getY() + 1.2, EZ + 4, 0, 0, -1));
            return null;
        });
    }

    private static void beam() {
        onServer(p -> {
            shooter.snapTo(EX, O.getY(), EZ + 6, 180F, 0F);
            shooter.setYHeadRot(180F);
            ((ServerLevel) p.level()).addFreshEntity(new EntityBulletBeamBase(shooter, XFactoryEnergy.energy_las, 0.3F, 0F, 0, 0, 0));
            return null;
        });
    }

    private static void melee() {
        onServer(p -> {
            p.hurtServer((ServerLevel) p.level(), p.level().damageSources().mobAttack(shooter), 2F);
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

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        try {
            for (int i = 0; i < LATER.size(); i++) if (now() >= LATER_AT.get(i)[0]) {
                Runnable r = LATER.remove(i);
                LATER_AT.remove(i);
                i--;
                r.run();
            }
            LocalPlayer p = mc.player;
            if (step == 11) {
                tilt = Math.max(tilt, Math.abs(GunHitClient.lastTilt));
                moved = Math.max(moved, Math.hypot(p.getX() - pos0.x, p.getZ() - pos0.z));
                turned = Math.max(turned, Math.abs(p.getYRot() - yaw0) + Math.abs(p.getXRot() - pitch0));
                hurt = Math.max(hurt, p.hurtTime);
            }
            if (now() < until) return;
            switch (step) {
                case 0 -> prepare();
                case 10 -> {
                    // still now: where it stands, then the hit
                    pos0 = p.position();
                    yaw0 = p.getYRot();
                    pitch0 = p.getXRot();
                    health0 = onServer(sp -> sp.getHealth());
                    tilt = 0F;
                    moved = 0;
                    turned = 0F;
                    hurt = 0;
                    marks0 = GunHitClient.marks;
                    CASES.get(index).hit.run();
                    step = 11;
                    until = now() + 30;
                }
                case 11 -> {
                    Case c = CASES.get(index);
                    Seen s = new Seen(tilt, moved, turned, hurt, health0 - onServer(sp -> sp.getHealth()), GunHitClient.marks - marks0);
                    SEEN.add(s);
                    log("{}: наклон до {}°, сдвиг до {} бл, взгляд повернулся на {}°, hurtTime до {}, урон {}, отметок «из оружия» {}", c.name,
                            f(s.tilt), f(s.moved), f(s.turned), s.hurt, f(s.lost), s.marks);
                    check(s.hurt > 0 && s.lost > 0F, c.name + ": попадание прошло (hurtTime " + s.hurt + ", урон " + f(s.lost) + ")");
                    check(c.tilts ? s.tilt > 3F : s.tilt < 1e-3F, c.name + ": наклон " + f(s.tilt) + "° (ждали " + (c.tilts ? "больше 3" : "0") + ")");
                    check(c.moves ? s.moved > 0.01 : s.moved < 2e-3, c.name + ": сдвиг " + f(s.moved) + " бл (ждали " + (c.moves ? "больше 0,01" : "0") + ")");
                    check(s.turned < 1e-4F, c.name + ": взгляд не повернулся (" + f(s.turned) + "°)");
                    if (index == 1) {
                        Seen a = SEEN.get(0);
                        check(Math.abs(a.tilt - s.tilt) < 1F && Math.abs(a.moved - s.moved) <= Math.max(0.01, 0.25 * a.moved),
                                "подавление не меняет отдачу попадания: наклон " + f(a.tilt) + " / " + f(s.tilt) + "°, сдвиг " + f(a.moved) + " / " + f(s.moved));
                    }
                    index++;
                    if (index >= CASES.size()) {
                        restore();
                        ClientSmoke.finish();
                        step = 999;
                    } else prepare();
                }
                default -> {
                }
            }
        } catch (Exception e) {
            GunFeel.LOGGER.error("[gunhit-stand]", e);
            fail("исключение: " + e);
        }
    }

    // the case's keys, the player healed, back in place and still for 20 ticks
    private static void prepare() throws Exception {
        Case c = CASES.get(index);
        String[] kv = new String[KEYS.length * 2];
        for (int i = 0; i < KEYS.length; i++) kv[2 * i] = KEYS[i];
        for (int i = 0; i < c.keys.length; i += 2) for (int k = 0; k < KEYS.length; k++) if (KEYS[k].equals(c.keys[i])) kv[2 * k + 1] = c.keys[i + 1];
        keys(kv);
        onServer(p -> {
            p.setHealth(p.getMaxHealth());
            // no regeneration in the watch: a full food bar heals a hit back in 30 ticks
            p.getFoodData().setFoodLevel(10);
            p.getFoodData().setSaturation(0F);
            p.invulnerableTime = 0;
            p.setDeltaMovement(Vec3.ZERO);
            p.teleportTo((ServerLevel) p.level(), EX, O.getY(), EZ, Set.of(), 0F, 0F, false);
            return null;
        });
        Suppression.clear();
        step = 10;
        until = now() + 25;
    }

    // ---- helpers

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
            GunFeel.LOGGER.warn("[gunhit-stand] файл настроек не восстановлен", e);
        }
    }

    private static void fail(String text) {
        ClientSmoke.check(false, "попадание из оружия: " + text);
        restore();
        ClientSmoke.finish();
        step = 999;
    }

    private static void check(boolean ok, String text) {
        GunFeel.LOGGER.info("[gunhit-stand] {} {}", ok ? "PASS" : "FAIL", text);
        ClientSmoke.check(ok, "попадание из оружия: " + text);
    }

    private static void log(String fmt, Object... args) {
        GunFeel.LOGGER.info("[gunhit-stand] " + fmt, args);
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}
