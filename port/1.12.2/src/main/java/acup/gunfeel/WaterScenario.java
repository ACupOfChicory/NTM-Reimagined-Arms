package acup.gunfeel;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.water.WaterBullet;
import acup.gunfeel.water.WaterClass;
import acup.gunfeel.water.WaterPath;
import acup.gunfeel.water.WaterSettings;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IWorldEventListener;
import net.minecraft.world.World;

// Dev (-Pscenario=water, ShootScenario's flat world): NTM bullets in water, 26.2's WaterCheck on the integrated
// server, plus what 26.2 checks elsewhere. A stone basin of still water (24 x 24, 8 deep) beside the player:
//  a-d: 26.2's WaterCheck rows, bullets the server ticks by hand, never added to the world (their tracer lines reach
//       the client all the same, TracerNet; nothing compares them): the stop depth of a shot down against
//       L·ln(1/0.15), also of a slow one that takes several ticks in the water, the share of damage a golem under the
//       water takes, the skip at 3 and 10 degrees and the length of the tick after it (bulletSpeed × f), a shot from
//       under the water along and up; for each plain class, then the hollow and soft points;
//  e:   the same shots down, along and at 3 degrees as real bullets the client traces, and slow ones down: the trace
//       (logs/ntm-gunfeel-trace-water.tsv) has the server's and the tracer's "end by water" for each id, compared
//       after the run (water-compare.py);
//  f:   the gun's sound fired with the eyes in water and out of it (WaterMuffle), as the server sends it;
//  g:   the mod's handlers in each mixin target (MixinCheck), the water's among them.
// The expectations are 26.2's default settings. The rows go to water.tsv; the game quits.
final class WaterScenario {
    private WaterScenario() {
    }

    // e: 7 classes × down, along, skip3; then SLOW slow shots down (SLOW_CLASSES)
    private static final int PLACE = 40, MEASURE = 60, FIRE = 100, GAP = 6, SHOTS = 7 * 3, SLOW = 3;
    private static final int MUFFLE = FIRE + (SHOTS + SLOW) * GAP + 40, CHECK = MUFFLE + 100, DONE = CHECK + 20;
    private static final int DEPTH = 8, SIZE = 24;
    private static final float DAMAGE = 20F;
    private static final WaterClass[] CLASSES = {WaterClass.PISTOL, WaterClass.RIFLE, WaterClass.HEAVY, WaterClass.AP, WaterClass.SLUG,
            WaterClass.SHOT, WaterClass.DART};
    private static final WaterClass[] SLOW_CLASSES = {WaterClass.PISTOL, WaterClass.SHOT, WaterClass.DART};
    // a slow shot's speed: × 0.1 of the gun's, 1.6 blocks a tick for velocity 10
    private static final float SLOW_SPEED = 0.1F;

    private static int[] feet;
    private static int minX, minZ, floor;
    private static double surface, cx, cz;
    private static World level;
    private static boolean ok = true;
    private static final List<String> lines = new ArrayList<String>();

    static void step(Minecraft mc, int t) {
        // the trace from the start, both sides (as PistolScenario): the tracer's and the server's lines for e
        if (t == 1) {
            TestLog.set(true, true);
            TestLog.set(false, true);
            Trace.recording = true;
            // not a scenario's own lines only (Trace.keep): the server's bullets too
            Trace.scenario = false;
        }
        if (t == PLACE) ShootScenario.onServer(WaterScenario::place);
        if (t == PLACE + 1) mc.gameSettings.hideGUI = true;
        if (t == MEASURE) ShootScenario.onServer(WaterScenario::measure);
        for (int i = 0; i < SHOTS + SLOW; i++) if (t == FIRE + i * GAP) {
            final int n = i;
            ShootScenario.onServer(() -> fire(n));
        }
        muffle(mc, t);
        if (t == CHECK) check();
        if (t == DONE) {
            GunFeel.LOGGER.info("Scenario water: done");
            mc.shutdown();
        }
    }

    private static EntityPlayerMP player() {
        return (EntityPlayerMP) net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().getPlayers().get(0);
    }

    // ---- the basin

    private static void place() {
        EntityPlayerMP p = player();
        feet = new int[] {(int) Math.floor(p.posX), (int) Math.floor(p.posY), (int) Math.floor(p.posZ)};
        level = p.world;
        level.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
        level.setWorldTime(6000);
        minX = feet[0] + 4;
        minZ = feet[2] - SIZE / 2;
        floor = feet[1] - 1;
        cx = minX + SIZE / 2.0;
        cz = minZ + SIZE / 2.0;
        int loX = minX - 1, hiX = minX + SIZE, loZ = minZ - 1, hiZ = minZ + SIZE;
        // flag 2: no block updates, so the water stays still
        for (int x = loX; x <= hiX; x++) for (int z = loZ; z <= hiZ; z++) {
            boolean wall = x == loX || x == hiX || z == loZ || z == hiZ;
            for (int y = floor; y <= floor + DEPTH + 1; y++) {
                Block b = y == floor || wall ? Blocks.STONE : y <= floor + DEPTH ? Blocks.WATER : Blocks.AIR;
                Mc.setBlock(level, x, y, z, b, 0, 2);
            }
        }
        WaterPath.Cell top = WaterPath.of(level).at((int) cx, floor + DEPTH, (int) cz);
        surface = floor + DEPTH + (top != null ? top.height() : Double.NaN);
        lines.add(String.join("\t", "case", "class", "ammo", "want", "got", "diff", "ok", "note"));
        lines.add("#\tfeet\t" + feet[0] + "," + feet[1] + "," + feet[2] + "\tbasin x " + minX + ".." + (minX + SIZE - 1) + " z " + minZ + ".." + (minZ + SIZE - 1)
                + " water y " + (floor + 1) + ".." + (floor + DEPTH));
        lines.add("#\tsurface\t" + fmt(surface) + "\tsettings " + WaterPath.Settings.current());
        if (!WaterSettings.ballistics()) {
            ok = false;
            lines.add("FAIL\twaterBallistics is off");
        }
        if (!defaults(WaterPath.Settings.current())) {
            ok = false;
            lines.add("FAIL\tthe water settings are not the defaults the rows expect: " + WaterPath.Settings.current());
        }
    }

    // the settings are 26.2's defaults (the config keeps the fractions as floats: 0.15F)
    private static boolean defaults(WaterPath.Settings s) {
        WaterPath.Settings d = WaterPath.Settings.DEFAULT;
        return s.on() == d.on() && s.maxSkips() == d.maxSkips() && Math.abs(s.stopFraction() - d.stopFraction()) < 1e-6
                && Math.abs(s.rangeScale() - d.rangeScale()) < 1e-6 && Math.abs(s.skipAngle() - d.skipAngle()) < 1e-6
                && Math.abs(s.fuzeSkipAngle() - d.fuzeSkipAngle()) < 1e-6;
    }

    // ---- a-d: 26.2's WaterCheck, by hand

    private static void measure() {
        try {
            for (WaterClass wc : CLASSES) {
                BulletConfig c = pick(wc);
                if (c == null) {
                    ok = false;
                    lines.add("FAIL\tno config for " + wc);
                    continue;
                }
                String ammo = WaterClass.ammoName(c);
                lines.add("#\t" + wc + "\t" + ammo + "\tvelocity " + c.velocity + " gravity " + c.gravity);
                downStop(wc, c, ammo);
                slowStop(wc, c, ammo);
                golem(wc, c, ammo, 0.5);
                golem(wc, c, ammo, 1.0);
                skip(wc, c, ammo, 3, true);
                skip(wc, c, ammo, 10, false);
                below(wc, c, ammo);
            }
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
        } catch (Throwable e) {
            ok = false;
            lines.add("FAIL\tcrashed: " + e);
            for (StackTraceElement s : e.getStackTrace()) lines.add("    at " + s);
        }
    }

    private static BulletConfig byAmmo(String name) {
        for (BulletConfig c : BulletConfig.configs)
            if (c != null && c.velocity >= 5F && c.onImpact == null && WaterClass.ammoName(c).equals(name)) return c;
        return null;
    }

    // a plain single bullet of the class: speed 5 and over, no impact lambda; the ammo wanted by name first
    private static final Map<WaterClass, String[]> WANT = new LinkedHashMap<WaterClass, String[]>();

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
            if (c != null && c.velocity >= 5F && c.onImpact == null && WaterClass.of(c) == wc) all.add(c);
        for (String w : WANT.get(wc)) for (BulletConfig c : all) if (WaterClass.ammoName(c).contains(w)) return c;
        return all.isEmpty() ? null : all.get(0);
    }

    // NTM CE's bullet as a gun makes it: a unit heading, the speed from its config (motionMult)
    private static EntityBulletBaseMK4 bullet(BulletConfig c, double x, double y, double z, double dx, double dy, double dz) {
        return bullet(c, x, y, z, dx, dy, dz, 1F);
    }

    private static EntityBulletBaseMK4 bullet(BulletConfig c, double x, double y, double z, double dx, double dy, double dz, float speed) {
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, c, 1F, 0F, 0F, 0F);
        b.setPosition(x, y, z);
        b.shoot(dx, dy, dz, speed, 0F);
        b.damage = DAMAGE;
        return b;
    }

    private static void tickAll(EntityBulletBaseMK4 b) {
        for (int i = 0; i < 40 && !b.isDead; i++) b.onUpdate();
    }

    private static double f(EntityBulletBaseMK4 b) {
        return ((WaterBullet) b).gunfeel$waterF();
    }

    private static double stopDepth(WaterClass wc, WaterClass.Expansion ex) {
        return WaterPath.stopDepth(wc, ex, WaterPath.Settings.DEFAULT);
    }

    // a shot straight down from 3 blocks over the surface: the depth where the bullet is gone
    private static void downStop(WaterClass wc, BulletConfig c, String ammo) {
        EntityBulletBaseMK4 b = bullet(c, cx, surface + 3, cz, 0, -1, 0);
        tickAll(b);
        double got = surface - b.posY;
        row("a: shot down, stop depth", wc, ammo, stopDepth(wc, WaterClass.Expansion.of(ammo)), b.isDead ? got : Double.NaN, .1, "removed=" + b.isDead);
    }

    // the same, slow: several ticks in the water, the speed × f at each tick's end; the stop at the same depth
    private static void slowStop(WaterClass wc, BulletConfig c, String ammo) {
        EntityBulletBaseMK4 b = bullet(c, cx + 1, surface + 3, cz + 1, 0, -1, 0, SLOW_SPEED);
        int ticks = 0;
        for (; ticks < 80 && !b.isDead; ticks++) b.onUpdate();
        double got = surface - b.posY;
        row("a: slow shot down, stop depth", wc, ammo, stopDepth(wc, WaterClass.Expansion.of(ammo)), b.isDead ? got : Double.NaN, .1,
                "removed=" + b.isDead + " ticks=" + ticks + " expires=" + c.expires);
    }

    // the share of damage a golem takes with its head top at a depth, against the same shot in the air
    private static void golem(WaterClass wc, BulletConfig c, String ammo, double depth) {
        EntityIronGolem probe = new EntityIronGolem(level);
        double top = probe.height + 0.3; // NTM inflates the hitbox by 0.3
        double feetY = surface - depth - top;
        double air = bodyTook(c, feetY, false);
        String airNote = lastEnd;
        double wet = bodyTook(c, feetY, true);
        double share = air > 0 ? wet / air : Double.NaN;
        WaterClass.Expansion ex = WaterClass.Expansion.of(ammo);
        double want = depth < stopDepth(wc, ex) ? Math.exp(-2 * WaterPath.drag(wc.length, ex, depth)) : 0;
        row("b: golem share at depth " + depth, wc, ammo, want, share, .03,
                "d=" + fmt(depth) + " air=" + fmt(air) + " wet=" + fmt(wet) + " [air " + airNote + "] [wet " + lastEnd + "]");
    }

    private static String lastEnd = "";

    private static double bodyTook(BulletConfig c, double feetY, boolean water) {
        // in the air beyond the basin's far wall, else in the middle of the water
        double gx = water ? cx : minX + SIZE + 4.5;
        EntityIronGolem g = new EntityIronGolem(level);
        g.setPosition(gx, feetY, cz);
        g.setNoAI(true);
        g.setNoGravity(true);
        boolean added = level.spawnEntity(g);
        EntityBulletBaseMK4 b = bullet(c, gx, surface + 3, cz, 0, -1, 0);
        float before = g.getHealth();
        tickAll(b);
        double took = before - g.getHealth();
        lastEnd = "added=" + added + " removed=" + b.isDead + " y=" + fmt(b.posY) + " health=" + g.getHealth();
        g.setDead();
        return took;
    }

    // a shot at a shallow angle to the surface from the side: 3 degrees skips once with f 0.7, 10 does not. It meets
    // the surface in the middle of a block: at a block's edge (26.2's cx - run) the walk may find it going in through
    // the side of the next cell, just under the surface, and that is no skip
    private static void skip(WaterClass wc, BulletConfig c, String ammo, int deg, boolean skips) {
        double r = Math.toRadians(deg), run = 10;
        EntityBulletBaseMK4 b = bullet(c, cx - run + 0.5, surface + run * Math.tan(r), cz, Math.cos(r), -Math.sin(r), 0);
        b.onUpdate();
        WaterBullet w = (WaterBullet) b;
        double dy = b.motionY;
        String note = "removed=" + b.isDead + " y-surface=" + fmt(b.posY - surface) + " dy=" + fmt(dy) + " f=" + fmt(w.gunfeel$waterF());
        if (skips) {
            boolean good = !b.isDead && b.posY >= surface - 1e-6 && dy > 0 && w.gunfeel$waterSkips() == 1;
            flag("c: skip at " + deg + " deg: skips", wc, ammo, "1, up, f=0.7", w.gunfeel$waterSkips() + ", dy " + fmt(dy),
                    good && Math.abs(w.gunfeel$waterF() - 0.7) < .02, note);
            // the next tick flies off at the gun's speed × bulletSpeed × f (motionMult: bulletSpeed and the water both)
            if (good) {
                double x0 = b.posX, y0 = b.posY, z0 = b.posZ, f0 = w.gunfeel$waterF();
                b.onUpdate();
                double moved = Math.sqrt(sq(b.posX - x0) + sq(b.posY - y0) + sq(b.posZ - z0));
                double want = c.velocity * ReaimTimes.number("bulletSpeed", 1.6F) * f0;
                row("c: skip at " + deg + " deg: the next tick's length", wc, ammo, want, moved, .03 * want + .1,
                        "velocity=" + c.velocity + " f=" + fmt(f0) + " removed=" + b.isDead);
            }
        } else {
            boolean good = w.gunfeel$waterSkips() == 0 && (b.isDead || b.posY < surface);
            flag("c: no skip at " + deg + " deg", wc, ammo, "0 skips, in the water", w.gunfeel$waterSkips() + ", y-surface " + fmt(b.posY - surface), good, note);
        }
    }

    // from under the water: along 4 deep to the stop, and up from 1 deep out of the water
    private static void below(WaterClass wc, BulletConfig c, String ammo) {
        double x0 = minX + 2;
        WaterClass.Expansion ex = WaterClass.Expansion.of(ammo);
        EntityBulletBaseMK4 b = bullet(c, x0, surface - 4, cz, 1, 0, 0);
        tickAll(b);
        row("d: from depth 4 along, stop distance", wc, ammo, stopDepth(wc, ex), b.isDead ? b.posX - x0 : Double.NaN, .1,
                "removed=" + b.isDead + " y=" + fmt(b.posY - surface));
        EntityBulletBaseMK4 u = bullet(c, cx, surface - 1, cz, 0, 1, 0);
        u.onUpdate();
        if (stopDepth(wc, ex) < 1) {
            flag("d: from depth 1 up, stopped in the water", wc, ammo, "removed", "removed=" + u.isDead, u.isDead, "y-surface=" + fmt(u.posY - surface));
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
        flag("d: f kept in the air", wc, ammo, fmt(got), fmt(f2), Math.abs(got - f2) < 1e-6, "");
    }

    // ---- e: real bullets, for the tracer

    // shot n: class n / 3, down, along from 4 deep, or at 3 degrees; each in its own lane across the basin. Past
    // SHOTS: slow shots down at the basin's far side, where the others do not reach
    private static void fire(int n) {
        if (n >= SHOTS) {
            int k = n - SHOTS;
            WaterClass wc = SLOW_CLASSES[k];
            BulletConfig c = pick(wc);
            if (c == null) return;
            EntityBulletBaseMK4 b = bullet(c, minX + 20.5, surface + 3, minZ + 1.5 + k * 3, 0, -1, 0, SLOW_SPEED);
            level.spawnEntity(b);
            shotLine("slow-down", wc, c, b);
            return;
        }
        WaterClass wc = CLASSES[n / 3];
        BulletConfig c = pick(wc);
        if (c == null) return;
        int kind = n % 3;
        double z = minZ + 1.5 + n;
        EntityBulletBaseMK4 b;
        String what;
        if (kind == 0) {
            b = bullet(c, minX + 2.5 + n % 9 * 2, surface + 3, z, 0, -1, 0);
            what = "down";
        } else if (kind == 1) {
            b = bullet(c, minX + 1.5, surface - 4, z, 1, 0, 0);
            what = "along";
        } else {
            double r = Math.toRadians(3), run = 6;
            b = bullet(c, minX + 0.5, surface + run * Math.tan(r), z, Math.cos(r), -Math.sin(r), 0);
            what = "skip3";
        }
        level.spawnEntity(b);
        shotLine(what, wc, c, b);
    }

    private static void shotLine(String what, WaterClass wc, BulletConfig c, EntityBulletBaseMK4 b) {
        lines.add(String.format(Locale.ROOT, "shot\t%s\t%s\t%s\tid %d\tat %.3f %.3f %.3f\tstop %.4f", what, wc, WaterClass.ammoName(c), b.getEntityId(),
                b.posX, b.posY, b.posZ, stopDepth(wc, WaterClass.Expansion.of(WaterClass.ammoName(c)))));
    }

    // ---- f: the gun's sound in water and out of it

    private static final List<String> heard = new ArrayList<String>();
    private static IWorldEventListener ear;
    private static final String GUN = "gun_light_revolver";

    private static void muffle(Minecraft mc, int t) {
        int m = t - MUFFLE;
        // dry: on the ground before the basin; wet: at its bottom, eyes in the water
        if (m == 0) ShootScenario.onServer(() -> {
            ShootScenario.give(0, GUN);
            player().inventory.currentItem = 0;
            ear = new Ear();
            level.addEventListener(ear);
            stand(false);
        });
        if (m == 1) mc.player.inventory.currentItem = 0;
        if (m == 10 || m == 60) ShootScenario.trigger(true, mc);
        if (m == 14 || m == 64) ShootScenario.trigger(false, mc);
        if (m == 40) ShootScenario.onServer(() -> {
            ShootScenario.give(0, GUN);
            stand(true);
        });
        if (m == 56) ShootScenario.onServer(() -> heard.add("eyes " + player().isInsideOfMaterial(net.minecraft.block.material.Material.WATER)));
        if (m == 90) ShootScenario.onServer(() -> level.removeEventListener(ear));
    }

    private static void stand(boolean wet) {
        double x = wet ? cx : feet[0] + .5, y = wet ? floor + 1 : feet[1], z = wet ? cz : feet[2] + .5;
        player().connection.setPlayerLocation(x, y, z, -90F, 0F);
        heard.add((wet ? "wet" : "dry") + String.format(Locale.ROOT, " at %.2f %.2f %.2f", x, y, z));
    }

    // what the server sends of each sound near the player (World.playSound's listeners get it after the muffle)
    private static final class Ear implements IWorldEventListener {
        @Override
        public void playSoundToAllNearExcept(EntityPlayer player, SoundEvent sound, SoundCategory category, double x, double y, double z, float volume, float pitch) {
            EntityPlayerMP p = player();
            if (p.getDistanceSq(x, y, z) > 16) return;
            heard.add(String.format(Locale.ROOT, "sound %s %s volume %.4f pitch %.4f", SoundEvent.REGISTRY.getNameForObject(sound), category, volume, pitch));
        }

        @Override
        public void notifyBlockUpdate(World world, BlockPos pos, IBlockState oldState, IBlockState newState, int flags) {
        }

        @Override
        public void notifyLightSet(BlockPos pos) {
        }

        @Override
        public void markBlockRangeForRenderUpdate(int x1, int y1, int z1, int x2, int y2, int z2) {
        }

        @Override
        public void playRecord(SoundEvent sound, BlockPos pos) {
        }

        @Override
        public void spawnParticle(int id, boolean ignoreRange, double x, double y, double z, double dx, double dy, double dz, int... params) {
        }

        @Override
        public void spawnParticle(int id, boolean ignoreRange, boolean minimise, double x, double y, double z, double dx, double dy, double dz, int... params) {
        }

        @Override
        public void onEntityAdded(Entity entity) {
        }

        @Override
        public void onEntityRemoved(Entity entity) {
        }

        @Override
        public void broadcastSound(int id, BlockPos pos, int data) {
        }

        @Override
        public void playEvent(EntityPlayer player, int type, BlockPos pos, int data) {
        }

        @Override
        public void sendBlockBreakProgress(int breakerId, BlockPos pos, int progress) {
        }
    }

    // ---- the file

    private static void check() {
        for (String h : heard) lines.add("muffle\t" + h);
        muffleRows();
        List<String> wrong = new ArrayList<String>();
        lines.add("#\tmixins\t" + MixinCheck.report(wrong));
        ok &= wrong.isEmpty();
        lines.add(String.join("\t", "g: mixin handlers", "-", "-", "as MixinCheck wants", wrong.isEmpty() ? "all" : String.join("; ", wrong), "",
                wrong.isEmpty() ? "OK" : "FAIL", ""));
        lines.add("result\t" + (ok ? "OK" : "FAIL"));
        try (PrintWriter pw = new PrintWriter(new File(Minecraft.getMinecraft().gameDir, "water.tsv"), StandardCharsets.UTF_8.name())) {
            for (String l : lines) pw.println(l);
        } catch (java.io.IOException e) {
            GunFeel.LOGGER.error("Scenario water: water.tsv", e);
        }
        for (String l : lines) GunFeel.LOGGER.info("Scenario water: {}", l);
    }

    // f's rows: each NTM sound heard both dry and wet is muffled under water by VOLUME and PITCH (with waterMuffle
    // on), and the eyes were in the water
    private static void muffleRows() {
        Map<String, float[]> dry = new LinkedHashMap<String, float[]>(), wet = new LinkedHashMap<String, float[]>();
        Map<String, float[]> into = null;
        boolean eyes = false;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("sound (\\S+) \\S+ volume (\\S+) pitch (\\S+)");
        for (String h : heard) {
            if (h.startsWith("dry at")) into = dry;
            else if (h.startsWith("wet at")) into = wet;
            else if (h.equals("eyes true")) eyes = true;
            java.util.regex.Matcher m = p.matcher(h);
            if (into != null && m.matches() && m.group(1).startsWith("hbm:"))
                into.putIfAbsent(m.group(1), new float[] {Float.parseFloat(m.group(2)), Float.parseFloat(m.group(3))});
        }
        float kv = WaterSettings.muffle() ? acup.gunfeel.water.WaterMuffle.VOLUME : 1F, kp = WaterSettings.muffle() ? acup.gunfeel.water.WaterMuffle.PITCH : 1F;
        int pairs = 0;
        for (Map.Entry<String, float[]> d : dry.entrySet()) {
            float[] w = wet.get(d.getKey());
            if (w == null) continue;
            pairs++;
            float[] v = d.getValue();
            boolean pass = Math.abs(w[0] - v[0] * kv) < 1e-3 && Math.abs(w[1] - v[1] * kp) < 1e-3;
            flag("f: muffled " + d.getKey(), WaterClass.PISTOL, GUN, fmt(v[0] * kv) + "/" + fmt(v[1] * kp), fmt(w[0]) + "/" + fmt(w[1]), pass,
                    "dry " + fmt(v[0]) + "/" + fmt(v[1]));
        }
        flag("f: the gun heard dry and wet, eyes in water", WaterClass.PISTOL, GUN, "pairs > 0, eyes true", pairs + ", eyes " + eyes, pairs > 0 && eyes, "");
    }

    private static double sq(double v) {
        return v * v;
    }

    private static void row(String name, WaterClass wc, String ammo, double want, double got, double tol, String note) {
        double diff = got - want;
        boolean pass = !Double.isNaN(got) && Math.abs(diff) <= tol;
        ok &= pass;
        lines.add(String.join("\t", name, wc.name(), ammo, fmt(want), fmt(got), fmt(diff), pass ? "OK" : "FAIL", note));
    }

    private static void flag(String name, WaterClass wc, String ammo, String want, String got, boolean pass, String note) {
        ok &= pass;
        lines.add(String.join("\t", name, wc.name(), ammo, want, got, "", pass ? "OK" : "FAIL", note));
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "nan" : String.format(Locale.ROOT, "%.4f", v);
    }
}
