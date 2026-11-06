package acup.gunfeel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.water.WaterBullet;
import acup.gunfeel.water.WaterClass;
import acup.gunfeel.water.WaterPath;
import acup.gunfeel.water.WaterSettings;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

// port: 26.2's WaterCheck (df28728) on a 1.7.10 dedicated server (runServer -PcheckWater): the same basin, classes and
// rows. 1.7.10: the server waits 20 ticks instead of forcing chunks (the basin's blocks load its chunks), still water
// is Blocks.water, its surface is a source block's 8/9 (WaterPath.ownHeight), the golem's top is its model's box
// (ModelAABB, what the bullet meets on the server here) inflated by NTM's 0.3, and the bullets are put in the world
// (NTM's setDead tells their trackers).
// How bullets behave in water, measured on a dedicated server with no client. -Dntmgunfeel.checkWater=<file>: the
// server builds a stone basin with still water, fires NTM bullets (no shooter, ticked by hand) of each water class and
// writes one tsv line per measurement: how deep a shot down stops, what share of its damage a golem under the water
// takes, the skip at 3 and 10 degrees, a shot from under the water along and up. Without the property nothing is
// registered.
public final class WaterCheck {
    public static final String PROPERTY = "ntmgunfeel.checkWater";

    private WaterCheck() {
    }

    public static void registerIfAsked() {
        String file = System.getProperty(PROPERTY);
        if (file == null || file.trim().isEmpty()) return;
        FMLCommonHandler.instance().bus().register(new Once(file));
    }

    public static final class Once {
        private final String file;
        private int ticks;

        Once(String file) {
            this.file = file;
        }

        @SubscribeEvent
        public void tick(TickEvent.ServerTickEvent e) {
            if (e.phase != TickEvent.Phase.END || ++ticks != 20) return;
            List<String> out = new ArrayList<String>();
            try {
                if (!run(out, MinecraftServer.getServer())) out.add("FAIL\tsome rows failed");
                else out.add("OK\tall rows");
            } catch (Throwable t) {
                out.add("FAIL\tcrashed: " + t);
                for (StackTraceElement s : t.getStackTrace()) out.add("    at " + s);
            }
            try {
                Path f = Paths.get(file);
                if (f.toAbsolutePath().getParent() != null) Files.createDirectories(f.toAbsolutePath().getParent());
                Files.write(f, out, StandardCharsets.UTF_8);
            } catch (Exception ex) {
                GunFeel.LOGGER.warn("Could not write the water check", ex);
            }
            for (String l : out) GunFeel.LOGGER.info("[watercheck] {}", l);
            MinecraftServer.getServer().initiateShutdown();
        }
    }

    private static final int FLOOR = 100, DEPTH = 8, MIN = 4, SIZE = 24; // the basin: x, z from 4 to 27, water y 101..108
    private static final double CX = MIN + SIZE / 2.0, CZ = MIN + SIZE / 2.0;
    private static final float DAMAGE = 20F;
    private static WorldServer level;
    private static List<String> lines;
    private static boolean ok;
    private static double surface;

    private static boolean run(List<String> out, MinecraftServer server) {
        lines = out;
        ok = true;
        level = server.worldServerForDimension(0);
        lines.add(String.join("\t", "case", "class", "ammo", "ожидание", "замер", "разница", "ок", "прим"));
        basin();
        surface = FLOOR + DEPTH + WaterPath.ownHeight(level.getBlockMetadata((int) CX, FLOOR + DEPTH, (int) CZ));
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
            String ammo = WaterClass.ammoName(c);
            lines.add("#\t" + wc + "\t" + ammo + "\tvelocity " + c.velocity + " gravity " + c.gravity);
            downStop(wc, c, ammo);
            golem(wc, c, ammo, 0.5);
            golem(wc, c, ammo, 1.0);
            twoTicks(wc, c, ammo);
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
            WaterClass wc = WaterClass.ofConfig(c);
            lines.add("#\t" + wc + "\t" + name + "\texpansion " + WaterClass.Expansion.of(name));
            downStop(wc, c, name);
            twoTicks(wc, c, name);
            if (!name.startsWith("ammo_standard_r556")) {
                golem(wc, c, name, 0.5);
                golem(wc, c, name, 1.0);
            }
        }
        muffle();
        clear();
        return ok;
    }

    // e (port, as the 1.12.2 port's f): NTM's revolver fired by a zombie (not a player: NTM sends its animations to
    // players) on land and with its eyes in the water: the shot's sound as the world plays it, × 0.6 and × 0.7 in water
    // (waterMuffle). The whole way: the gun's key handling marks the shooter (MixinWaterMuffle), World's sound call
    // muffles (MixinWorldMuffle)
    private static void muffle() {
        float[] land = shotSound(1.5, FLOOR + DEPTH + 3, CZ);
        float[] wet = shotSound(CX, surface - 2.5, CZ);
        String name = "e: revolver shot sound";
        if (land == null || wet == null) {
            ok = false;
            lines.add("FAIL	" + name + ": no sound, land " + java.util.Arrays.toString(land) + " water " + java.util.Arrays.toString(wet));
            return;
        }
        rowFlag(name + " on land", WaterClass.PISTOL, "gun_light_revolver", "volume, pitch as NTM's", fmt(land[0]) + ", " + fmt(land[1]),
                land[2] == 1F, "eyes in water: false");
        rowFlag(name + " in water", WaterClass.PISTOL, "gun_light_revolver", fmt(land[0] * 0.6) + ", " + fmt(land[1] * 0.7),
                fmt(wet[0]) + ", " + fmt(wet[1]), Math.abs(wet[0] - land[0] * 0.6F) < 1e-4 && Math.abs(wet[1] - land[1] * 0.7F) < 1e-4 && wet[2] == 1F,
                "sounds " + (int) wet[2]);
    }

    // the revolver's shot from a zombie standing there: {volume, pitch, how many hbm sounds}; null: none was played
    private static float[] shotSound(double x, double y, double z) {
        final List<float[]> heard = new ArrayList<float[]>();
        net.minecraft.world.IWorldAccess ear = (net.minecraft.world.IWorldAccess) java.lang.reflect.Proxy.newProxyInstance(
                WaterCheck.class.getClassLoader(), new Class<?>[] {net.minecraft.world.IWorldAccess.class}, (proxy, m, a) -> {
                    if (m.getName().equals("equals")) return proxy == a[0];
                    if (m.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (m.getName().equals("toString")) return "WaterCheck ear";
                    // (the dev game's names: the stand runs in the dev server only)
                    if (m.getName().equals("playSound") && a[0] instanceof String && ((String) a[0]).startsWith("hbm:"))
                        heard.add(new float[] {(Float) a[4], (Float) a[5]});
                    return null;
                });
        net.minecraft.entity.monster.EntityZombie shooter = new net.minecraft.entity.monster.EntityZombie(level);
        shooter.setPosition(x, y, z);
        net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(com.hbm.items.ModItems.gun_light_revolver);
        com.hbm.items.weapon.sedna.ItemGunBaseNT gun = (com.hbm.items.weapon.sedna.ItemGunBaseNT) stack.getItem();
        gun.getConfig(stack, 0).getReceivers(stack)[0].getMagazine(stack).setAmount(stack, 6);
        com.hbm.items.weapon.sedna.ItemGunBaseNT.setState(stack, 0, com.hbm.items.weapon.sedna.ItemGunBaseNT.GunState.IDLE);
        shooter.setCurrentItemOrArmor(0, stack);
        level.addWorldAccess(ear);
        try {
            gun.handleKeybind(shooter, null, stack, com.hbm.handler.HbmKeybinds.EnumKeybind.GUN_PRIMARY, true);
        } finally {
            level.removeWorldAccess(ear);
        }
        for (Object e : new ArrayList<Object>(level.loadedEntityList))
            if (e instanceof EntityBulletBaseMK4 && ((EntityBulletBaseMK4) e).getThrower() == shooter) ((EntityBulletBaseMK4) e).setDead();
        lines.add("#	shot at " + fmt(x) + " " + fmt(y) + " " + fmt(z) + ": eyes in water " + shooter.isInsideOfMaterial(net.minecraft.block.material.Material.water)
                + ", heard " + heard.size());
        if (heard.isEmpty()) return null;
        return new float[] {heard.get(0)[0], heard.get(0)[1], heard.size()};
    }

    private static BulletConfig byAmmo(String name) {
        for (BulletConfig c : BulletConfig.configs)
            if (c != null && c.velocity >= 5F && c.onImpact == null && WaterClass.ammoName(c).equals(name)) return c;
        return null;
    }

    private static void basin() {
        int lo = MIN - 1, hi = MIN + SIZE;
        for (int x = lo; x <= hi; x++) for (int z = lo; z <= hi; z++) {
            boolean wall = x == lo || x == hi || z == lo || z == hi;
            for (int y = FLOOR; y <= FLOOR + DEPTH + 1; y++) {
                net.minecraft.block.Block b = y == FLOOR || wall ? Blocks.stone : y <= FLOOR + DEPTH ? Blocks.water : Blocks.air;
                level.setBlock(x, y, z, b, 0, 2);
            }
        }
    }

    // a plain single bullet of the class: speed 5 and over, no impact lambda; the ammo wanted by name first
    private static final Map<WaterClass, String[]> WANT = new HashMap<WaterClass, String[]>();
    static {
        WANT.put(WaterClass.PISTOL, new String[] {"p9_fmj", "p9"});
        WANT.put(WaterClass.RIFLE, new String[] {"r556_fmj", "r556"});
        WANT.put(WaterClass.HEAVY, new String[] {"bmg50_fmj", "bmg50"});
        WANT.put(WaterClass.AP, new String[] {"r762_ap", "ap"});
        WANT.put(WaterClass.SLUG, new String[] {"g12_slug", "slug"});
        WANT.put(WaterClass.SHOT, new String[] {"g12_", "g12"});
        WANT.put(WaterClass.DART, new String[] {"flechette"});
    }

    private static BulletConfig pick(WaterClass wc) {
        List<BulletConfig> all = new ArrayList<BulletConfig>();
        for (BulletConfig c : BulletConfig.configs)
            if (c != null && c.velocity >= 5F && c.onImpact == null && WaterClass.ofConfig(c) == wc) all.add(c);
        for (String w : WANT.get(wc)) for (BulletConfig c : all) if (WaterClass.ammoName(c).contains(w)) return c;
        return all.isEmpty() ? null : all.get(0);
    }

    private static EntityBulletBaseMK4 bullet(BulletConfig c, double x, double y, double z, double dx, double dy, double dz) {
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, c, 1F, 0F, 0F, 0F);
        b.setPosition(x, y, z);
        // a unit heading, as NTM's own constructors: the step per tick is motionMult (the config's velocity), so the
        // shots below run over several ticks and carry f, the stop and the damage scale from tick to tick
        b.setThrowableHeading(dx, dy, dz, 1F, 0F);
        b.damage = DAMAGE;
        level.spawnEntityInWorld(b); // NTM's setDead tells the bullet's trackers
        SPAWNED.add(b);
        return b;
    }

    // bullets the stand put in the world: gone before the server ticks them itself
    private static final List<EntityBulletBaseMK4> SPAWNED = new ArrayList<EntityBulletBaseMK4>();

    private static void clear() {
        for (EntityBulletBaseMK4 b : SPAWNED) if (!b.isDead) b.setDead();
        SPAWNED.clear();
    }

    // ticks until the bullet is gone (at most 40): how many it took
    private static int tickAll(EntityBulletBaseMK4 b) {
        int i = 0;
        while (i < 40 && !b.isDead) {
            b.onUpdate();
            i++;
        }
        return i;
    }

    // how far the bullet goes in one tick in the air (motionMult: the config's velocity and the speed setting)
    private static double step(BulletConfig c) {
        EntityBulletBaseMK4 b = bullet(c, 1.5, surface + 30, CZ, 1, 0, 0);
        b.onUpdate();
        double d = b.posX - 1.5;
        b.setDead();
        return d;
    }

    // port: the rows a and b again with the water met 0.3 before the end of the first tick, so the stop and the golem
    // fall in the second: f, the opening and the damage scale carried over a tick (WaterTick.end, then the next path)
    private static void twoTicks(WaterClass wc, BulletConfig c, String ammo) {
        double above = step(c) - 0.3;
        WaterClass.Expansion ex = WaterClass.Expansion.of(ammo);
        EntityBulletBaseMK4 b = bullet(c, CX, surface + above, CZ, 0, -1, 0);
        int ticks = tickAll(b);
        double got = b.isDead ? surface - b.posY : Double.NaN;
        double want = stopDepth(wc, ex);
        rowFlag("a2: shot down, stop depth in the 2nd tick", wc, ammo, fmt(want) + ", 2 ticks", fmt(got) + ", " + ticks + " ticks",
                ticks == 2 && Math.abs(got - want) <= .1, "from " + fmt(above) + " over");
        if (ammo.startsWith("ammo_standard_r556")) return;
        double depth = Math.min(1.0, want - 0.2);
        if (depth <= 0.3) return; // stops before a golem under 0.3 of water would be in the second tick
        EntityIronGolem probe = new EntityIronGolem(level);
        probe.setPosition(0, 0, 0);
        double feet = surface - depth - (ModelAABB.of(probe).maxY - probe.boundingBox.minY + 0.3);
        double air = bodyTook(c, feet, false, above);
        double wet = bodyTook(c, feet, true, above);
        double share = air > 0 ? wet / air : Double.NaN;
        row("b2: golem share at depth " + fmt(depth) + " in the 2nd tick", wc, ammo, Math.exp(-2 * WaterPath.drag(wc.length, ex, depth)), share, .03,
                "air=" + fmt(air) + " wet=" + fmt(wet));
    }

    private static double f(EntityBulletBaseMK4 b) {
        return ((WaterBullet) b).gunfeel$waterF();
    }

    private static double stopDepth(WaterClass wc, WaterClass.Expansion ex) {
        return WaterPath.stopDepth(wc, ex, WaterPath.Settings.DEFAULT);
    }

    // a shot straight down from 3 blocks over the surface: the depth where the bullet is gone
    private static void downStop(WaterClass wc, BulletConfig c, String ammo) {
        EntityBulletBaseMK4 b = bullet(c, CX, surface + 3, CZ, 0, -1, 0);
        tickAll(b);
        double got = surface - b.posY;
        row("a: shot down, stop depth", wc, ammo, stopDepth(wc, WaterClass.Expansion.of(ammo)), b.isDead ? got : Double.NaN, .1, "removed=" + b.isDead);
    }

    // the share of damage a golem takes with its head top at a depth, against the same shot in the air
    private static void golem(WaterClass wc, BulletConfig c, String ammo, double depth) {
        EntityIronGolem probe = new EntityIronGolem(level);
        probe.setPosition(0, 0, 0);
        double top = ModelAABB.of(probe).maxY - probe.boundingBox.minY + 0.3; // NTM inflates the hitbox by 0.3
        double feet = surface - depth - top;
        double air = bodyTook(c, feet, false, 3);
        double wet = bodyTook(c, feet, true, 3);
        double share = air > 0 ? wet / air : Double.NaN;
        WaterClass.Expansion ex = WaterClass.Expansion.of(ammo);
        double want = depth < stopDepth(wc, ex) ? Math.exp(-2 * WaterPath.drag(wc.length, ex, depth)) : 0;
        double dInferred = share > 0 ? -wc.length / 2 * Math.log(share) : Double.NaN;
        row("b: golem share at depth " + depth, wc, ammo, want, share, .03,
                "d=" + fmt(depth) + " top=" + fmt(top) + " air=" + fmt(air) + " wet=" + fmt(wet) + " d_from_share(no expansion)=" + fmt(dInferred));
    }

    private static double bodyTook(BulletConfig c, double feet, boolean water, double above) {
        double gx = water ? CX : 1.5;
        EntityIronGolem g = new EntityIronGolem(level);
        g.setPosition(gx, feet, CZ);
        g.motionX = g.motionY = g.motionZ = 0;
        boolean added = level.spawnEntityInWorld(g);
        EntityBulletBaseMK4 b = bullet(c, gx, surface + above, CZ, 0, -1, 0);
        float before = g.getHealth();
        try {
            tickAll(b);
            return before - g.getHealth();
        } finally {
            if (!added) lines.add("#\tgolem not added at " + fmt(gx) + " " + fmt(feet));
            g.setDead();
            level.removeEntity(g);
        }
    }

    // a shot at a shallow angle to the surface from the side: 3 degrees skips once with f 0.7, 10 does not
    private static void skip(WaterClass wc, BulletConfig c, String ammo, int deg, boolean skips) {
        // port (as the 1.12.2 port): aimed at the middle of a cell, not at its edge (CX), where the walk may meet the
        // water by the side face of the next cell just under the surface
        double r = Math.toRadians(deg), run = 10, x = CX + 0.5;
        EntityBulletBaseMK4 b = bullet(c, x - run, surface + run * Math.tan(r), CZ + 0.5, Math.cos(r), -Math.sin(r), 0);
        WaterBullet w = (WaterBullet) b;
        // up to the tick that meets the water: a slow bullet takes more than one to the 10 blocks
        for (int i = 0; i < 10 && !b.isDead && w.gunfeel$waterSkips() == 0 && b.posY >= surface; i++) b.onUpdate();
        double dy = b.motionY;
        String note = "removed=" + b.isDead + " y-surface=" + fmt(b.posY - surface) + " dy=" + fmt(dy) + " f=" + fmt(w.gunfeel$waterF());
        if (skips) {
            boolean good = !b.isDead && b.posY >= surface - 1e-6 && dy > 0 && w.gunfeel$waterSkips() == 1;
            rowFlag("c: skip at " + deg + " deg: skips", wc, ammo, "1, up, f=0.7", w.gunfeel$waterSkips() + ", dy " + fmt(dy),
                    good && Math.abs(w.gunfeel$waterF() - 0.7) < .02, note);
        } else {
            boolean good = w.gunfeel$waterSkips() == 0 && (b.isDead || b.posY < surface);
            rowFlag("c: no skip at " + deg + " deg", wc, ammo, "0 skips, in the water", w.gunfeel$waterSkips() + ", y-surface " + fmt(b.posY - surface),
                    good, note);
        }
    }

    // from under the water: along 4 deep to the stop, and up from 1 deep out of the water
    private static void below(WaterClass wc, BulletConfig c, String ammo) {
        double x0 = MIN + 2;
        WaterClass.Expansion ex = WaterClass.Expansion.of(ammo);
        EntityBulletBaseMK4 b = bullet(c, x0, surface - 4, CZ, 1, 0, 0);
        tickAll(b);
        row("d: from depth 4 along, stop distance", wc, ammo, stopDepth(wc, ex), b.isDead ? b.posX - x0 : Double.NaN, .1,
                "removed=" + b.isDead + " y=" + fmt(b.posY - surface));

        EntityBulletBaseMK4 u = bullet(c, CX, surface - 1, CZ, 0, 1, 0);
        u.onUpdate();
        if (stopDepth(wc, ex) < 1) {
            rowFlag("d: from depth 1 up, stopped in the water", wc, ammo, "removed", "removed=" + u.isDead, u.isDead, "y-surface=" + fmt(u.posY - surface));
            return;
        }
        double want = Math.exp(-WaterPath.drag(wc.length, ex, 1));
        double got = f(u);
        double f2 = Double.NaN;
        if (!u.isDead) {
            u.onUpdate();
            f2 = f(u);
        }
        row("d: from depth 1 up, f after leaving", wc, ammo, want, got, .01, "removed=" + u.isDead + " y-surface=" + fmt(u.posY - surface));
        rowFlag("d: f kept in the air", wc, ammo, fmt(got), fmt(f2), Math.abs(got - f2) < 1e-6, "");
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
