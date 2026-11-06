package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.water.WaterBullet;
import acup.gunfeel.water.WaterClass;
import acup.gunfeel.water.WaterPath;
import acup.gunfeel.water.WaterSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

// How bullets behave in water, measured on a dedicated server with no client (a dev run only:
// ./gradlew runCheckWater -PnoAssets). -Dntmgunfeel.checkWater=<file.tsv>: the server builds a stone basin with still
// water, fires NTM bullets (no shooter, ticked by hand) of each water class and writes one tsv line per measurement:
// how deep a shot down stops, what share of its damage a golem under the water takes, the skip at 3 and 10 degrees,
// a shot from under the water along and up. Without the property nothing is registered.
public final class WaterCheck {
    public static final String PROPERTY = "ntmgunfeel.checkWater";

    private WaterCheck() {
    }

    public static void registerIfAsked() {
        String file = System.getProperty(PROPERTY);
        if (file == null || file.isBlank()) return;
        // not at once at the start: a mob put in a chunk that is still loading is not in the world's lookup, so the
        // run waits for the server's own ticks to make the chunks entity-ticking
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, started -> {
            for (int cx = 0; cx < 4; cx++) for (int cz = 0; cz < 4; cz++) started.getServer().overworld().setChunkForced(cx, cz, true);
            int[] ticks = {0};
            NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, e -> {
                if (++ticks[0] != 100) return;
                List<String> out = new ArrayList<>();
                int code;
                try {
                    code = run(out, e.getServer()) ? 0 : 1;
                } catch (Throwable t) {
                    out.add("FAIL\tcrashed: " + t);
                    for (StackTraceElement s : t.getStackTrace()) out.add("    at " + s);
                    code = 1;
                }
                BulletCheck.finish(file, out, code);
            });
        });
    }

    private static final int FLOOR = 100, DEPTH = 8, MIN = 4, SIZE = 24; // the basin: x, z from 4 to 27, water y 101..108
    private static final double CX = MIN + SIZE / 2.0, CZ = MIN + SIZE / 2.0, STOP = 0.15;
    private static final float DAMAGE = 20F;
    private static ServerLevel level;
    private static List<String> lines;
    private static boolean ok;
    private static double surface;

    private static boolean run(List<String> out, MinecraftServer server) {
        lines = out;
        ok = true;
        level = server.overworld();
        lines.add(String.join("\t", "case", "class", "ammo", "ожидание", "замер", "разница", "ок", "прим"));
        basin();
        BlockPos top = new BlockPos((int) CX, FLOOR + DEPTH, (int) CZ);
        surface = top.getY() + level.getFluidState(top).getHeight(level, top);
        if (!WaterSettings.ballistics()) {
            ok = false;
            lines.add("FAIL\twaterBallistics is off");
        }
        lines.add("#\tsurface\t" + fmt(surface));
        WaterClass[] classes = {WaterClass.PISTOL, WaterClass.RIFLE, WaterClass.HEAVY, WaterClass.AP, WaterClass.SLUG,
                WaterClass.SHOT, WaterClass.DART};
        for (WaterClass wc : classes) {
            BulletConfig c = pick(wc);
            if (c == null) {
                ok = false;
                lines.add("FAIL\tno config for " + wc);
                continue;
            }
            String ammo = ammoName(c);
            lines.add("#\t" + wc + "\t" + ammo + "\tvelocity " + c.velocity + " gravity " + c.gravity);
            downStop(wc, c, ammo);
            golem(wc, c, ammo, 0.5);
            golem(wc, c, ammo, 1.0);
            skip(wc, c, ammo, 3, true);
            skip(wc, c, ammo, 10, false);
            below(wc, c, ammo);
        }
        // expanding bullets: the opening lowers L (WaterClass.Expansion)
        for (String name : new String[] {"ammo_standard_p9_jhp", "ammo_standard_p9_sp", "ammo_standard_r556_sp"}) {
            BulletConfig c = byAmmo(name);
            if (c == null) {
                ok = false;
                lines.add("FAIL\tno config " + name);
                continue;
            }
            WaterClass wc = WaterClass.of(c);
            lines.add("#\t" + wc + "\t" + name + "\texpansion " + WaterClass.Expansion.of(name));
            downStop(wc, c, name);
            if (!name.startsWith("ammo_standard_r556")) {
                golem(wc, c, name, 0.5);
                golem(wc, c, name, 1.0);
            }
        }
        return ok;
    }

    private static BulletConfig byAmmo(String name) {
        for (BulletConfig c : BulletConfig.configs)
            if (c != null && c.velocity >= 5F && c.onImpact == null && ammoName(c).equals(name)) return c;
        return null;
    }

    private static void basin() {
        int lo = MIN - 1, hi = MIN + SIZE;
        for (int x = lo; x <= hi; x++) for (int z = lo; z <= hi; z++) {
            boolean wall = x == lo || x == hi || z == lo || z == hi;
            for (int y = FLOOR; y <= FLOOR + DEPTH + 1; y++) {
                var s = y == FLOOR || wall ? Blocks.STONE.defaultBlockState()
                        : y <= FLOOR + DEPTH ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
                level.setBlock(new BlockPos(x, y, z), s, Block.UPDATE_CLIENTS);
            }
        }
    }

    // a plain single bullet of the class: speed 5 and over, no impact lambda; the ammo wanted by name first
    private static final java.util.Map<WaterClass, String[]> WANT = java.util.Map.of(
            WaterClass.PISTOL, new String[] {"p9_fmj", "p9"},
            WaterClass.RIFLE, new String[] {"r556_fmj", "r556"},
            WaterClass.HEAVY, new String[] {"bmg50_fmj", "bmg50"},
            WaterClass.AP, new String[] {"r762_ap", "ap"},
            WaterClass.SLUG, new String[] {"g12_slug", "slug"},
            WaterClass.SHOT, new String[] {"g12_", "g12"},
            WaterClass.DART, new String[] {"flechette"});

    private static BulletConfig pick(WaterClass wc) {
        List<BulletConfig> all = candidates(wc);
        return all.isEmpty() ? null : all.get(0);
    }

    // the class's plain bullets, the wanted ammo first
    private static List<BulletConfig> candidates(WaterClass wc) {
        List<BulletConfig> all = new ArrayList<>();
        for (BulletConfig c : BulletConfig.configs)
            if (c != null && c.velocity >= 5F && c.onImpact == null && WaterClass.of(c) == wc) all.add(c);
        List<BulletConfig> sorted = new ArrayList<>();
        for (String w : WANT.get(wc)) for (BulletConfig c : all) if (ammoName(c).contains(w) && !sorted.contains(c)) sorted.add(c);
        for (BulletConfig c : all) if (!sorted.contains(c)) sorted.add(c);
        return sorted;
    }

    private static String ammoName(BulletConfig c) {
        try {
            if (c.ammoItem != null) return BuiltInRegistries.ITEM.getKey(c.ammoItem.get()).getPath();
        } catch (RuntimeException e) {
            // no item
        }
        return "id" + c.id;
    }

    private static EntityBulletBaseMK4 bullet(BulletConfig c, Vec3 at, Vec3 dir) {
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, c, 1F, 0F, 0F, 0F);
        b.setPos(at.x, at.y, at.z);
        b.setThrowableHeading(dir.x, dir.y, dir.z, c.velocity, 0F);
        b.damage = DAMAGE;
        return b;
    }

    private static void tickAll(EntityBulletBaseMK4 b) {
        for (int i = 0; i < 40 && !b.isRemoved(); i++) b.tick();
    }

    private static double f(EntityBulletBaseMK4 b) {
        return ((WaterBullet) b).gunfeel$waterF();
    }

    private static double stopDepth(WaterClass wc, WaterClass.Expansion ex) {
        return WaterPath.stopDepth(wc, ex, WaterPath.Settings.DEFAULT);
    }

    // a shot straight down from 3 blocks over the surface: the depth where the bullet is gone
    private static void downStop(WaterClass wc, BulletConfig c, String ammo) {
        EntityBulletBaseMK4 b = bullet(c, new Vec3(CX, surface + 3, CZ), new Vec3(0, -1, 0));
        tickAll(b);
        double got = surface - b.getY();
        row("a: shot down, stop depth", wc, ammo, stopDepth(wc, WaterClass.Expansion.of(ammo)), b.isRemoved() ? got : Double.NaN, .1, "removed=" + b.isRemoved());
    }

    // the share of damage a golem takes with its head top at a depth, against the same shot in the air
    private static void golem(WaterClass wc, BulletConfig c, String ammo, double depth) {
        var type = net.minecraft.world.entity.EntityTypes.IRON_GOLEM;
        var probe = type.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        double top = probe.getBbHeight() + 0.3; // NTM inflates the hitbox by 0.3
        probe.discard();
        double feet = surface - depth - top;
        double air = bodyTook(type, c, feet, DAMAGE, false);
        double wet = bodyTook(type, c, feet, DAMAGE, true);
        double share = air > 0 ? wet / air : Double.NaN;
        var ex = WaterClass.Expansion.of(ammo);
        double want = depth < stopDepth(wc, ex) ? Math.exp(-2 * WaterPath.drag(wc.length, ex, depth)) : 0;
        double dInferred = share > 0 ? -wc.length / 2 * Math.log(share) : Double.NaN;
        row("b: golem share at depth " + depth, wc, ammo, want, share, .03,
                "d=" + fmt(depth) + " air=" + fmt(air) + " wet=" + fmt(wet) + " d_from_share(no expansion)=" + fmt(dInferred));
    }

    private static double bodyTook(net.minecraft.world.entity.EntityType<? extends net.minecraft.world.entity.LivingEntity> type,
            BulletConfig c, double feet, float damage, boolean water) {
        double gx = water ? CX : 1.5;
        var g = type.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        g.setPos(gx, feet, CZ);
        if (g instanceof net.minecraft.world.entity.Mob m) m.setNoAi(true);
        g.setNoGravity(true);
        boolean added = level.addFreshEntity(g);
        EntityBulletBaseMK4 b = bullet(c, new Vec3(gx, surface + 3, CZ), new Vec3(0, -1, 0));
        b.damage = damage;
        String where = "added=" + added + " found=" + (level.getEntity(g.getUUID()) != null) + " box=" + g.getBoundingBox();
        float before = g.getHealth();
        tickAll(b);
        double took = before - g.getHealth();
        lastEnd = where + " removed=" + b.isRemoved() + " y=" + fmt(b.getY()) + " bulletDmg=" + b.damage + " health=" + g.getHealth() + " feet=" + fmt(feet);
        g.discard();
        return took;
    }

    private static String lastEnd = "";

    // a shot at a shallow angle to the surface from the side: 3 degrees skips once with f 0.7, 10 does not
    private static void skip(WaterClass wc, BulletConfig c, String ammo, int deg, boolean skips) {
        double r = Math.toRadians(deg), run = 10;
        Vec3 dir = new Vec3(Math.cos(r), -Math.sin(r), 0);
        EntityBulletBaseMK4 b = bullet(c, new Vec3(CX - run, surface + run * Math.tan(r), CZ), dir);
        b.tick();
        WaterBullet w = (WaterBullet) b;
        double dy = b.getDeltaMovement().y;
        String note = "removed=" + b.isRemoved() + " y-surface=" + fmt(b.getY() - surface) + " dy=" + fmt(dy) + " f=" + fmt(w.gunfeel$waterF());
        if (skips) {
            boolean good = !b.isRemoved() && b.getY() >= surface - 1e-6 && dy > 0 && w.gunfeel$waterSkips() == 1;
            rowFlag("c: skip at " + deg + " deg: skips", wc, ammo, "1, up, f=0.7", w.gunfeel$waterSkips() + ", dy " + fmt(dy), good && Math.abs(w.gunfeel$waterF() - 0.7) < .02, note);
        } else {
            boolean good = w.gunfeel$waterSkips() == 0 && (b.isRemoved() || b.getY() < surface);
            rowFlag("c: no skip at " + deg + " deg", wc, ammo, "0 skips, in the water", w.gunfeel$waterSkips() + ", y-surface " + fmt(b.getY() - surface), good, note);
        }
    }

    // from under the water: along 4 deep to the stop, and up from 1 deep out of the water
    private static void below(WaterClass wc, BulletConfig c, String ammo) {
        double x0 = MIN + 2;
        EntityBulletBaseMK4 b = bullet(c, new Vec3(x0, surface - 4, CZ), new Vec3(1, 0, 0));
        tickAll(b);
        row("d: from depth 4 along, stop distance", wc, ammo, stopDepth(wc, WaterClass.Expansion.of(ammo)), b.isRemoved() ? b.getX() - x0 : Double.NaN, .1,
                "removed=" + b.isRemoved() + " y=" + fmt(b.getY() - surface));

        EntityBulletBaseMK4 u = bullet(c, new Vec3(CX, surface - 1, CZ), new Vec3(0, 1, 0));
        u.tick();
        if (stopDepth(wc, WaterClass.Expansion.of(ammo)) < 1) {
            rowFlag("d: from depth 1 up, stopped in the water", wc, ammo, "removed", "removed=" + u.isRemoved(), u.isRemoved(),
                    "y-surface=" + fmt(u.getY() - surface));
            return;
        }
        double want = Math.exp(-WaterPath.drag(wc.length, WaterClass.Expansion.of(ammo), 1));
        double got = f(u);
        double f2 = Double.NaN;
        if (!u.isRemoved()) {
            u.tick();
            f2 = f(u);
        }
        row("d: from depth 1 up, f after leaving", wc, ammo, want, got, .01, "removed=" + u.isRemoved() + " y-surface=" + fmt(u.getY() - surface));
        double again = f2;
        rowFlag("d: f kept in the air", wc, ammo, fmt(got), fmt(again), Math.abs(got - again) < 1e-6, "");
    }

    private static void row(String name, WaterClass wc, String ammo, double want, double got, double tol, String note) {
        double diff = got - want;
        boolean pass = !Double.isNaN(got) && Math.abs(diff) <= tol;
        ok &= pass;
        lines.add(String.join("\t", name, wc.name(), ammo, fmt(want), fmt(got), fmt(diff), pass ? "OK" : "FAIL", note));
    }

    private static void rowFlag(String name, WaterClass wc, String ammo, String want, String got, boolean pass, String note) {
        ok &= pass;
        lines.add(String.join("\t", name, wc.name(), ammo, want, got, "", pass ? "OK" : "FAIL", note));
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "nan" : String.format(Locale.ROOT, "%.4f", v);
    }
}
