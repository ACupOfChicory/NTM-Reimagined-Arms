package acup.gunfeel;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.hbm.capability.HbmLivingProps;
import com.hbm.entity.effect.EntityFireLingering;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.water.WaterBlast;
import acup.gunfeel.water.WaterBullet;
import acup.gunfeel.water.WaterClass;
import acup.gunfeel.water.WaterImpact;
import acup.gunfeel.water.WaterPath;
import acup.gunfeel.water.WaterSettings;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

// Dev (-Pscenario=boom, ShootScenario's flat world): 26.2's ExplosiveCheck, the explosives in water, on the integrated
// server. In a stone basin of still water beside the player (40 x 40, 8 deep) every NTM projectile with an impact fuze
// (no nukes) is fired, ticked by hand, and one tsv line is written per measurement:
// - into the water at 30° (goes off on the surface) and at 5° (skips, goes off later);
// - from 3 under the water at a wall 1, 3 and 6 blocks off (on the wall, or stopped short, sinks, on the floor);
// - stopped by water: sinks at no more than 1 block a second, goes off on the floor;
// - the damage a golem 2 blocks off takes from its blast in the water and over it, against the same blast in the air;
// and b75_inc's small blast, the fire and phosphorus put out (in water a phosphorus pellet hits as hard as a plain
// one), an explosive set off by a bullet, one going off on the surface that leaves one sinking under its line alone,
// and pellets of one shot that do not set each other off; a round stopped high over the floor with NTM's life ticking (gone without a
// blast when the life ends) and HE pellets shot straight down from inside the shooter's box; then the mod's handlers
// in each mixin target (MixinCheck). The rows go to boom.tsv; the game quits.
// As 26.2's: tick() is onUpdate(), tickCount is ticksExisted (the world counts it before the tick in both).
final class BoomScenario {
    private BoomScenario() {
    }

    private static final int PLACE = 40, RUN = 60, WAIT = 20 * 600;
    private static final int DEPTH = 8, SIZE = 40;
    private static final float DAMAGE = 20F;
    private static final int TICKS = 200;
    private static int minX, minZ, floor;
    private static double cx, cz, dryX, surface;
    private static World level;
    private static boolean ok = true;
    private static volatile boolean finished;
    private static int doneAt = -1;
    private static final List<String> lines = new ArrayList<String>();
    private static final List<double[]> BLASTS = new ArrayList<double[]>();

    static void step(Minecraft mc, int t) {
        if (t == 1) {
            // the server's lines of every bullet into the trace (logs/ntm-gunfeel-trace-boom.tsv)
            TestLog.set(false, true);
            Trace.recording = true;
            Trace.scenario = false;
        }
        if (t == PLACE + 1) mc.gameSettings.hideGUI = true;
        if (t == RUN) ShootScenario.onServer(BoomScenario::run);
        if (t > RUN && doneAt < 0 && (finished || t > RUN + WAIT)) {
            if (!finished) {
                ok = false;
                lines.add("FAIL\tnot finished in " + WAIT + " ticks");
            }
            check();
            doneAt = t;
        }
        if (doneAt > 0 && t == doneAt + 20) {
            GunFeel.LOGGER.info("Scenario boom: done");
            mc.shutdown();
        }
    }

    private static EntityPlayerMP player() {
        return (EntityPlayerMP) net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().getPlayers().get(0);
    }

    private static void run() {
        try {
            place();
            measure();
        } catch (Throwable e) {
            ok = false;
            lines.add("FAIL\tcrashed: " + e);
            for (StackTraceElement s : e.getStackTrace()) lines.add("    at " + s);
        } finally {
            WaterBlast.watch = null;
            finished = true;
        }
    }

    // the basin: from 4 past the player's feet along x, the floor under its feet, the water 8 over it; a dry pad past it
    // for the air's blasts. The middle is a block's middle: an edge of cells is where a walk may meet the side of the
    // next cell (water-1.md)
    private static void place() {
        EntityPlayerMP p = player();
        int fx = MathHelper.floor(p.posX), fy = MathHelper.floor(p.posY), fz = MathHelper.floor(p.posZ);
        level = p.world;
        level.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
        level.getGameRules().setOrCreateGameRule("doFireTick", "false");
        level.setWorldTime(6000);
        minX = fx + 4;
        minZ = fz - SIZE / 2;
        floor = fy - 1;
        cx = minX + SIZE / 2 + 0.5;
        cz = minZ + SIZE / 2 + 0.5;
        dryX = minX + SIZE + 12.5;
        lines.add(String.join("\t", "case", "ammo", "class", "want", "got", "diff", "ok", "note"));
        basin();
        WaterPath.Cell top = WaterPath.of(level).at(MathHelper.floor(cx), floor + DEPTH, MathHelper.floor(cz));
        surface = floor + DEPTH + (top != null ? top.height() : Double.NaN);
        lines.add("#\tfeet\t" + fx + "," + fy + "," + fz + "\tbasin x " + minX + ".." + (minX + SIZE - 1) + " z " + minZ + ".." + (minZ + SIZE - 1)
                + " water y " + (floor + 1) + ".." + (floor + DEPTH) + " dry x " + fmt(dryX));
        if (!WaterSettings.ballistics() || !WaterSettings.fuze()) {
            ok = false;
            lines.add("FAIL\twaterBallistics or waterFuze is off");
        }
        lines.add("#\tsurface\t" + fmt(surface) + "\tfuze skip " + WaterPath.Settings.current().fuzeSkipAngle() + "\tblast radius " + WaterSettings.blastRadius());
        WaterBlast.watch = e -> BLASTS.add(new double[] {e.posX, e.posY, e.posZ, e.size});
    }

    private static void measure() {
        Map<String, BulletConfig> all = projectiles();
        for (Map.Entry<String, BulletConfig> e : all.entrySet()) {
            String ammo = e.getKey();
            BulletConfig c = e.getValue();
            WaterClass wc = WaterClass.of(c);
            lines.add("#\t" + ammo + "\t" + wc + "\tfuze " + WaterClass.Fuze.of(c) + " velocity " + c.velocity + " gravity " + c.gravity
                    + " stop " + fmt(stop(wc)));
            entry(ammo, c, wc, 30);
            entry(ammo, c, wc, 5);
            for (int d : new int[] {1, 3, 6}) wall(ammo, c, wc, d);
            stopped(ammo, c, wc);
            damage(ammo, c, wc);
        }
        onSurface(all);
        weak(all);
        fire(all);
        phosphorus();
        touch(all);
        deadPath(all);
        samePellets(all);
        life(all);
        downward(all);
        basin();
    }

    // NTM's projectiles with an impact fuze, one per ammo (or per field, for those its ammo does not tell); no nukes
    // (they would take the basin and the chunks with them; the turret's W9 shell is one)
    private static Map<String, BulletConfig> projectiles() {
        Map<String, BulletConfig> out = new LinkedHashMap<String, BulletConfig>();
        for (BulletConfig c : BulletConfig.configs) {
            if (c == null || !WaterClass.Fuze.of(c).any()) continue;
            String field = WaterClass.Fuze.field(c);
            String ammo = field != null ? field : WaterClass.ammoName(c);
            if (ammo.contains("nuke") || ammo.equals("shell_w9") || out.containsKey(ammo)) continue;
            out.put(ammo, c);
        }
        return out;
    }

    // flag 2: no block updates, so the water stays still; a block already as it should be is left as it is
    private static void basin() {
        int loX = minX - 1, hiX = minX + SIZE, loZ = minZ - 1, hiZ = minZ + SIZE;
        for (int x = loX; x <= hiX; x++) for (int z = loZ; z <= hiZ; z++) {
            boolean wall = x == loX || x == hiX || z == loZ || z == hiZ;
            for (int y = floor; y <= floor + DEPTH + 4; y++) {
                Block b = y == floor || wall && y <= floor + DEPTH + 1 ? Blocks.STONE : y <= floor + DEPTH ? Blocks.WATER : Blocks.AIR;
                Mc.setBlock(level, x, y, z, b, 0, 2);
            }
        }
        // the dry pad
        int dx = MathHelper.floor(dryX), dz = MathHelper.floor(cz);
        for (int x = dx - 6; x <= dx + 6; x++) for (int z = dz - 6; z <= dz + 6; z++)
            for (int y = floor; y <= floor + 8; y++) Mc.setBlock(level, x, y, z, y == floor ? Blocks.STONE : Blocks.AIR, 0, 2);
        clear();
    }

    // what blasts and bullets left: items, fire, golems, bullets
    private static void clear() {
        AxisAlignedBB box = new AxisAlignedBB(minX - 8, floor - 4, minZ - 8, dryX + 10, floor + 24, minZ + SIZE + 8);
        for (Class<? extends Entity> k : java.util.Arrays.<Class<? extends Entity>>asList(EntityItem.class, EntityFireLingering.class, EntityIronGolem.class,
                EntityBulletBaseMK4.class, EntityArmorStand.class))
            for (Entity e : level.getEntitiesWithinAABB(k, box)) {
                e.setDead();
                // port: 1.12.2 leaves a dead one in its chunk till the world's next tick, where NTM's spawnFire still
                // finds it and lays no fire of its own: the stand runs with no tick between its runs
                if (e.addedToChunk) level.getChunk(e.chunkCoordX, e.chunkCoordZ).removeEntity(e);
                e.addedToChunk = false;
            }
    }

    private static BulletConfig byAmmo(Map<String, BulletConfig> all, String name) {
        BulletConfig c = all.get(name);
        if (c != null) return c;
        for (BulletConfig k : BulletConfig.configs) if (k != null && WaterClass.ammoName(k).equals(name)) return k;
        return null;
    }

    // as NTM fires it: the heading at 1, the speed is motionMult's
    private static EntityBulletBaseMK4 bullet(BulletConfig c, Vec3d at, Vec3d dir) {
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, c, 1F, 0F, 0F, 0F);
        b.setPosition(at.x, at.y, at.z);
        b.shoot(dir.x, dir.y, dir.z, 1F, 0F);
        b.damage = DAMAGE;
        // NTM counts the ticks in the world; here it is ticked by hand: past the bomblets' 2 ticks of arming
        b.ticksExisted = 10;
        return b;
    }

    // the stop distance in still water (density 1)
    private static double stop(WaterClass wc) {
        return WaterPath.stopDepth(wc, null, WaterPath.Settings.current());
    }

    // ticks it till it is gone; the first blast, or null
    private static double[] fly(EntityBulletBaseMK4 b, int ticks) {
        BLASTS.clear();
        for (int i = 0; i < ticks && !b.isDead; i++) b.onUpdate();
        return BLASTS.isEmpty() ? null : BLASTS.get(0);
    }

    private static Vec3d pos(Entity e) {
        return new Vec3d(e.posX, e.posY, e.posZ);
    }

    private static void stone(int x, int yLo, int yHi, int zLo, int zHi) {
        for (int y = yLo; y <= yHi; y++) for (int z = zLo; z <= zHi; z++) Mc.setBlock(level, x, y, z, Blocks.STONE, 0, 2);
    }

    // into the water from the air at an angle: steeper than the fuze's 10° it goes off on the surface, shallower it
    // skips and goes off at its next touch. The angle is the one it meets the water at (a grenade's falls steeper).
    private static void entry(String ammo, BulletConfig c, WaterClass wc, int deg) {
        basin();
        double r = Math.toRadians(deg), run = deg >= 20 ? 4 : 10;
        Vec3d dir = new Vec3d(Math.cos(r), -Math.sin(r), 0);
        // on a cells' edge, as in 26.2: NTM CE's ticks here meet the surface exactly there (the cut takes the top)
        Vec3d aim = new Vec3d(minX + 14, surface, cz);
        // after a skip its next touch: a wall further on
        int czi = MathHelper.floor(cz);
        if (deg < 20) stone(minX + 30, floor + 1, floor + DEPTH + 8, czi - 4, czi + 4);
        EntityBulletBaseMK4 b = bullet(c, aim.subtract(dir.scale(run)), dir);
        BLASTS.clear();
        double angle = Double.NaN, firstTouchX = Double.NaN;
        int skips = 0;
        for (int i = 0; i < TICKS && !b.isDead; i++) {
            double a = Math.toDegrees(Math.atan2(-b.motionY, Math.hypot(b.motionX, b.motionZ)));
            int before = ((WaterBullet) b).gunfeel$waterSkips();
            double y0 = b.posY;
            b.onUpdate();
            int now = ((WaterBullet) b).gunfeel$waterSkips();
            if (now > before && skips == 0) {
                firstTouchX = b.posX;
                angle = a;
            }
            skips = now;
            if (!BLASTS.isEmpty() && Double.isNaN(angle)) angle = a;
            if (!BLASTS.isEmpty() || b.isDead) break;
            if (b.posY < floor - 2 || b.posX > minX + SIZE + 20) break;
            if (Double.isNaN(angle) && y0 > surface && b.posY < surface - 0.01) angle = a;
        }
        double[] blast = BLASTS.isEmpty() ? null : BLASTS.get(0);
        String note = "skips=" + skips + " angle=" + fmt(angle) + " blast=" + str(blast) + " removed=" + b.isDead;
        double fuze = WaterPath.Settings.current().fuzeSkipAngle();
        if (skips == 0) {
            // on the surface where it came in (at any angle what does not skip: the Daybreaker's flame)
            boolean good = blast != null && blast[1] >= surface - 0.05 && blast[1] <= surface + 0.35 && (!(angle < fuze - 0.5) || !wc.slows());
            rowFlag("a: into water at " + deg + "°: goes off on the surface", ammo, wc, "blast y " + fmt(surface) + "..+0.3",
                    blast == null ? "none" : fmt(blast[1]), good, note);
        } else {
            // skipped where it met the water (shallower than the fuze), went off later, not there
            // b75_inc: its next touch is in the air, where it is NTM's (no blast)
            boolean weak = WaterClass.Fuze.of(c) == WaterClass.Fuze.WEAK;
            boolean good = angle < fuze + 0.5 && (weak ? blast == null && b.isDead : blast != null && Math.abs(blast[0] - firstTouchX) > 0.5);
            rowFlag("a: into water at " + deg + "°: skips, goes off later", ammo, wc, "skip, blast past x " + fmt(firstTouchX),
                    blast == null ? "none" : "x " + fmt(blast[0]) + " y " + fmt(blast[1]), good, note);
        }
    }

    // from 3 under the water along x at a stone wall d blocks off: on the wall if it gets there, else it stops,
    // sinks and goes off on the floor under where it stopped
    private static void wall(String ammo, BulletConfig c, WaterClass wc, int d) {
        basin();
        double x0 = minX + 3.5;
        int wx = MathHelper.floor(x0 + d), czi = MathHelper.floor(cz);
        stone(wx, floor + 1, floor + DEPTH, czi - 3, czi + 3);
        EntityBulletBaseMK4 b = bullet(c, new Vec3d(x0, surface - 3, cz), new Vec3d(1, 0, 0));
        BLASTS.clear();
        double stopX = Double.NaN;
        for (int i = 0; i < TICKS && !b.isDead; i++) {
            boolean was = WaterBullet.stopped(b);
            b.onUpdate();
            if (!was && WaterBullet.stopped(b)) stopX = b.posX;
        }
        double[] blast = BLASTS.isEmpty() ? null : BLASTS.get(0);
        double s = stop(wc), face = wx;
        String note = "blast=" + str(blast) + " stopped=" + WaterBullet.stopped(b) + " removed=" + b.isDead + " stop=" + fmt(s) + " stopX=" + fmt(stopX);
        // straight on it reaches the wall within its stop; one NTM's gravity bends down (0.05 a tick and more) stops by
        // the same path in water nearer, short of it
        boolean reaches = c.gravity >= 0.05 ? Double.isNaN(stopX) : x0 + s > face;
        if (reaches) {
            boolean good = blast != null && Math.abs(blast[0] - face) < 0.45 && blast[1] > floor + 1.5;
            rowFlag("b: under water, wall " + d + ": on the wall", ammo, wc, "x " + fmt(face), blast == null ? "none" : "x " + fmt(blast[0]), good, note);
        } else {
            boolean good = blast != null && blast[1] < floor + 1.4 && blast[0] < face;
            rowFlag("b: under water, wall " + d + ": stops, on the floor", ammo, wc, "y " + (floor + 1) + ", x < " + fmt(face),
                    blast == null ? "none" : "x " + fmt(blast[0]) + " y " + fmt(blast[1]), good, note);
        }
    }

    // stopped by water: from 5 under the water along x with nothing in its way; then down at no more than 0.05 a
    // tick, and off on the floor
    private static void stopped(String ammo, BulletConfig c, WaterClass wc) {
        basin();
        double x0 = minX + 3.5;
        EntityBulletBaseMK4 b = bullet(c, new Vec3d(x0, surface - 5, cz), new Vec3d(1, 0, 0));
        BLASTS.clear();
        double fastest = 0, stopX = Double.NaN, path = 0;
        int sinkTicks = 0;
        for (int i = 0; i < 400 && !b.isDead && BLASTS.isEmpty(); i++) {
            double wx = b.posX, wy = b.posY, wz = b.posZ;
            boolean wasStopped = WaterBullet.stopped(b);
            b.onUpdate();
            // the path till water stopped it (bent by NTM's gravity: longer than how far along x)
            if (!wasStopped) path += Math.sqrt((b.posX - wx) * (b.posX - wx) + (b.posY - wy) * (b.posY - wy) + (b.posZ - wz) * (b.posZ - wz));
            if (wasStopped && BLASTS.isEmpty() && !b.isDead) {
                fastest = Math.max(fastest, wy - b.posY);
                sinkTicks++;
                if (Math.abs(b.posX - wx) > 1e-9) fastest = Double.POSITIVE_INFINITY;
            }
            if (!wasStopped && WaterBullet.stopped(b)) stopX = b.posX;
        }
        double[] blast = BLASTS.isEmpty() ? null : BLASTS.get(0);
        String note = "stopX-x0=" + fmt(stopX - x0) + " path=" + fmt(path) + " sinkTicks=" + sinkTicks + " blast=" + str(blast);
        row("c: stopped, path in water", ammo, wc, stop(wc), Double.isNaN(stopX) ? Double.NaN : path, 0.15, note);
        rowFlag("c: stopped, sinks ≤ 0.05/tick", ammo, wc, "≤ 0.0500", fmt(fastest), !Double.isNaN(stopX) && fastest <= 0.05 + 1e-9 && sinkTicks > 0, note);
        rowFlag("c: stopped, goes off on the floor", ammo, wc, "y " + (floor + 1), blast == null ? "none" : fmt(blast[1]),
                blast != null && blast[1] < floor + 1.4, note);
    }

    private static EntityIronGolem golem(Vec3d feet) {
        EntityIronGolem g = new EntityIronGolem(level);
        g.setPosition(feet.x, feet.y, feet.z);
        g.setNoAI(true);
        g.setNoGravity(true);
        level.spawnEntity(g);
        // its in-water state
        g.onUpdate();
        g.setPosition(feet.x, feet.y, feet.z);
        return g;
    }

    // the health a golem takes from the blast set off at `at`
    private static double golemTook(BulletConfig c, Vec3d at, Vec3d golemFeet, double[] blastOut) {
        clear();
        EntityIronGolem g = golem(golemFeet);
        float before = g.getHealth();
        EntityBulletBaseMK4 b = bullet(c, at, new Vec3d(0, -1, 0));
        BLASTS.clear();
        ((WaterImpact.Hits) b).gunfeel$impact(Mc.blockHit(MathHelper.floor(at.x), MathHelper.floor(at.y - 0.5), MathHelper.floor(at.z), 1, at));
        double took = before - g.getHealth();
        if (!BLASTS.isEmpty()) {
            double[] k = BLASTS.get(0);
            System.arraycopy(k, 0, blastOut, 0, 4);
            // the box's distance to the centre, as NTM counts it
            AxisAlignedBB box = g.getEntityBoundingBox();
            double dx = Math.max(0, Math.max(box.minX - k[0], k[0] - box.maxX)), dy = Math.max(0, Math.max(box.minY - k[1], k[1] - box.maxY)),
                    dz = Math.max(0, Math.max(box.minZ - k[2], k[2] - box.maxZ));
            blastOut[4] = Math.sqrt(dx * dx + dy * dy + dz * dz);
            blastOut[5] = WaterBlast.inWater(g) ? 1 : 0;
        }
        g.setDead();
        return took;
    }

    // a golem 2 blocks off: in the air, in the water by a blast in it, and over the water by a blast just under the
    // surface. CrossSmooth: fixed × (1 - d / range), range 2 × size, in water × waterBlastRadius; over it half.
    private static void damage(String ammo, BulletConfig c, WaterClass wc) {
        if (WaterClass.Fuze.of(c) == WaterClass.Fuze.WEAK) return; // no blast in the air: weak()
        basin();
        double half = 0.7; // the golem's half width
        double[] air = new double[6], wet = new double[6], over = new double[6];
        Vec3d dryAt = new Vec3d(dryX, floor + 3, cz);
        double tAir = golemTook(c, dryAt, new Vec3d(dryX + 2 + half, floor + 3 - 1.35, cz), air);
        basin();
        Vec3d wetAt = new Vec3d(cx, surface - 5, cz);
        double tWet = golemTook(c, wetAt, new Vec3d(cx + 2 + half, surface - 5 - 1.35, cz), wet);
        basin();
        Vec3d overAt = new Vec3d(cx, surface - 1, cz);
        double tOver = golemTook(c, overAt, new Vec3d(cx, surface + 1, cz), over);
        float mult = Math.max(1F, WaterSettings.blastRadius());
        double size = air[3];
        String note = "air=" + fmt(tAir) + " d=" + fmt(air[4]) + " | wet=" + fmt(tWet) + " d=" + fmt(wet[4]) + " golemWet=" + (int) wet[5]
                + " | over=" + fmt(tOver) + " d=" + fmt(over[4]) + " golemWet=" + (int) over[5] + " | size=" + fmt(size);
        if (size > 0 && 2 * size <= air[4]) {
            // a blast too small to reach the golem 2 blocks off: CrossSmooth's range is 2 x size
            lines.add("#\t" + ammo + "\td: the blast does not reach 2 blocks\t" + note);
            return;
        }
        if (tAir <= 0 || size <= 0) {
            rowFlag("d: blast damage in the air", ammo, wc, "> 0", fmt(tAir), false, note);
            return;
        }
        double ref = 1 - air[4] / (2 * size);
        double wantWet = (1 - wet[4] / (2 * size * mult)) / ref;
        double wantOver = WaterBlast.ABOVE * (1 - over[4] / (2 * size)) / ref;
        row("d: golem in water, × the air's", ammo, wc, wantWet, tWet / tAir, 0.06, note);
        row("d: golem over water, × the air's", ammo, wc, wantOver, tOver / tAir, 0.06, note);
    }

    // b75_inc under water: a small blast, about a quarter of b75_exp's there
    private static void weak(Map<String, BulletConfig> all) {
        BulletConfig inc = byAmmo(all, "ammo_standard_b75_inc"), exp = byAmmo(all, "ammo_standard_b75_exp");
        if (inc == null || exp == null) {
            rowFlag("e: b75_inc", "ammo_standard_b75_inc", WaterClass.HEAVY, "configs", "missing", false, "");
            return;
        }
        if (WaterClass.Fuze.of(inc) == WaterClass.Fuze.NONE) {
            // port: NTM CE's Bolter rounds are plain bullets (no onImpact: no blast, no fire), so water gives them none
            for (BulletConfig c : new BulletConfig[] {inc, exp}) {
                basin();
                EntityBulletBaseMK4 k = bullet(c, new Vec3d(cx, surface - 4, cz), new Vec3d(0, -1, 0));
                BLASTS.clear();
                ((WaterImpact.Hits) k).gunfeel$impact(Mc.blockHit(MathHelper.floor(cx), MathHelper.floor(surface - 5.5), MathHelper.floor(cz), 1,
                        new Vec3d(cx, surface - 5, cz)));
                String name = WaterClass.ammoName(c);
                rowFlag("e: " + name.substring(name.lastIndexOf('_') + 1) + " (NTM CE: no blast, no fire) under water: none", name, WaterClass.of(c),
                        "fuze NONE, 0 blasts", "fuze " + WaterClass.Fuze.of(c) + ", " + BLASTS.size() + " blasts",
                        WaterClass.Fuze.of(c) == WaterClass.Fuze.NONE && BLASTS.isEmpty(), "onImpact " + (c.onImpact != null));
            }
            return;
        }
        double half = 0.7;
        double[] a = new double[6], b = new double[6];
        basin();
        double tInc = golemTook(inc, new Vec3d(cx, surface - 5, cz), new Vec3d(cx + 2 + half, surface - 5 - 1.35, cz), a);
        basin();
        double tExp = golemTook(exp, new Vec3d(cx, surface - 5, cz), new Vec3d(cx + 2 + half, surface - 5 - 1.35, cz), b);
        float mult = Math.max(1F, WaterSettings.blastRadius());
        // CrossSmooth both: damage × 0.25, the range 2.5 against 5
        double want = 0.25 * (1 - a[4] / (2 * 2.5 * mult)) / (1 - b[4] / (2 * 5 * mult));
        row("e: b75_inc under water, × b75_exp's", "ammo_standard_b75_inc", WaterClass.of(inc), want, tExp > 0 ? tInc / tExp : Double.NaN, 0.05,
                "inc=" + fmt(tInc) + " size=" + fmt(a[3]) + " exp=" + fmt(tExp) + " size=" + fmt(b[3]));
        // and in the air it is NTM's: no blast
        basin();
        EntityBulletBaseMK4 k = bullet(inc, new Vec3d(dryX, floor + 3, cz), new Vec3d(0, -1, 0));
        BLASTS.clear();
        ((WaterImpact.Hits) k).gunfeel$impact(Mc.blockHit(MathHelper.floor(dryX), floor, MathHelper.floor(cz), 1, new Vec3d(dryX, floor + 1, cz)));
        rowFlag("e: b75_inc in the air: no blast", "ammo_standard_b75_inc", WaterClass.of(inc), "0 blasts", BLASTS.size() + " blasts", BLASTS.isEmpty(), "");
    }

    // fire grenades and rockets: their blast in water, no lingering fire; in the air NTM's fire
    private static void fire(Map<String, BulletConfig> all) {
        for (String name : new String[] {"ammo_standard_g40_inc", "ammo_standard_g40_phosphorus", "ammo_standard_rocket_inc", "ammo_standard_rocket_phosphorus"}) {
            BulletConfig c = byAmmo(all, name);
            if (c == null) {
                lines.add("#\tno config " + name);
                continue;
            }
            int[] fires = new int[2], blasts = new int[2];
            for (int wet = 0; wet < 2; wet++) {
                basin();
                Vec3d at = wet == 1 ? new Vec3d(cx, surface - 3, cz) : new Vec3d(dryX, floor + 1, cz);
                EntityBulletBaseMK4 b = bullet(c, at.add(0, 1, 0), new Vec3d(0, -1, 0));
                BLASTS.clear();
                ((WaterImpact.Hits) b).gunfeel$impact(Mc.blockHit(MathHelper.floor(at.x), MathHelper.floor(at.y - 0.5), MathHelper.floor(at.z), 1, at));
                blasts[wet] = BLASTS.size();
                fires[wet] = level.getEntitiesWithinAABB(EntityFireLingering.class, new AxisAlignedBB(at, at).grow(8), e -> !e.isDead).size();
            }
            rowFlag("f: fire in water: blast, no fire", name, WaterClass.of(c), "blast 1, fire 0", "blast " + blasts[1] + ", fire " + fires[1],
                    blasts[1] == 1 && fires[1] == 0, "in the air: blast " + blasts[0] + ", fire " + fires[0]);
            rowFlag("f: fire in the air (NTM's)", name, WaterClass.of(c), "fire ≥ 1", "fire " + fires[0], fires[0] >= 1, "");
            // NTM's guard in the first ticks, kept in water: a rocket does nothing on any mob it hits before its 3rd tick
            // (XFactoryRocket), a 40 mm grenade only spares its shooter (XFactory40mm)
            basin();
            EntityIronGolem g = golem(new Vec3d(cx, surface - 4, cz));
            EntityBulletBaseMK4 e = bullet(c, new Vec3d(cx - 1.2, surface - 2.8, cz), new Vec3d(1, 0, 0));
            e.ticksExisted = 1;
            BLASTS.clear();
            ((WaterImpact.Hits) e).gunfeel$impact(new net.minecraft.util.math.RayTraceResult(g, new Vec3d(g.posX - 0.7, surface - 2.8, cz)));
            boolean rocket = name.startsWith("ammo_standard_rocket_");
            int early = BLASTS.size();
            rowFlag("f: in water, a mob hit at tick 1 (NTM's guard)", name, WaterClass.of(c), rocket ? "0 blasts" : "1 blast", early + " blasts",
                    early == (rocket ? 0 : 1), "removed=" + e.isDead);
            g.setDead();
        }
        // the Daybreaker's flame: NTM's blast, then its fire (XFactoryFlamer.spawnFire) only out of the water
        for (String name : new String[] {"flame_daybreaker_diesel", "flame_daybreaker_napalm", "flame_daybreaker_balefire"}) {
            BulletConfig c = null;
            for (BulletConfig k : BulletConfig.configs) if (c == null && k != null && name.equals(WaterClass.Fuze.field(k))) c = k;
            if (c == null) {
                ok = false;
                lines.add("FAIL\tno config " + name);
                continue;
            }
            int[] fires = new int[2], blasts = new int[2];
            for (int wet = 0; wet < 2; wet++) {
                basin();
                Vec3d at = wet == 1 ? new Vec3d(cx, surface - 3, cz) : new Vec3d(dryX, floor + 1, cz);
                EntityBulletBaseMK4 b = bullet(c, at.add(0, 1, 0), new Vec3d(0, -1, 0));
                BLASTS.clear();
                ((WaterImpact.Hits) b).gunfeel$impact(Mc.blockHit(MathHelper.floor(at.x), MathHelper.floor(at.y - 0.5), MathHelper.floor(at.z), 1, at));
                blasts[wet] = BLASTS.size();
                fires[wet] = level.getEntitiesWithinAABB(EntityFireLingering.class, new AxisAlignedBB(at, at).grow(8), e -> !e.isDead).size();
            }
            rowFlag("f: fire in water: blast, no fire", name, WaterClass.of(c), "blast 1, fire 0", "blast " + blasts[1] + ", fire " + fires[1],
                    blasts[1] == 1 && fires[1] == 0, "in the air: blast " + blasts[0] + ", fire " + fires[0]);
            rowFlag("f: fire in the air (NTM's)", name, WaterClass.of(c), "fire ≥ 1", "fire " + fires[0], fires[0] >= 1, "");
        }
    }

    // a tick that ended right on the surface (a rocket at 30 degrees: 0 + 0.4 + 0.8 + 1.2 + 1.6 = 4 blocks in 5 ticks):
    // the next one starts on it and comes in from the air, so it goes off there, not sinks to the floor (26.2 X560)
    private static void onSurface(Map<String, BulletConfig> all) {
        for (String ammo : new String[] {"ammo_standard_rocket_he", "ammo_standard_g40_he", "ammo_standard_r762_he"}) {
            BulletConfig c = byAmmo(all, ammo);
            if (c == null) {
                ok = false;
                lines.add("FAIL\tno config " + ammo);
                continue;
            }
            basin();
            double r = Math.toRadians(30);
            Vec3d on = new Vec3d(minX + 14, surface, cz);
            EntityBulletBaseMK4 b = bullet(c, on, new Vec3d(Math.cos(r), -Math.sin(r), 0));
            double[] blast = fly(b, TICKS);
            rowFlag("a: from right on the surface at 30°: goes off there", ammo, WaterClass.of(c), "blast y " + fmt(surface) + "..+0.3",
                    blast == null ? "none" : fmt(blast[1]), blast != null && blast[1] >= surface - 0.05 && blast[1] <= surface + 0.35,
                    "blast=" + str(blast) + " stopped=" + WaterBullet.stopped(b));
        }
    }

    // a phosphorus pellet on a golem in water sets it not alight; in the air NTM's 300 ticks
    private static void phosphorus() {
        BulletConfig c = null, plain = null;
        // the shotgun's own (the Shredder's shares the ammo)
        for (BulletConfig k : BulletConfig.configs) {
            if (c == null && k != null && WaterClass.ammoName(k).equals("ammo_standard_g12_phosphorus")) c = k;
            if (plain == null && k != null && WaterClass.ammoName(k).equals("ammo_standard_g12")) plain = k;
        }
        if (c == null || plain == null) {
            ok = false;
            lines.add("FAIL\tno config ammo_standard_g12_phosphorus or ammo_standard_g12");
            return;
        }
        int[] got = new int[2];
        double[] took = new double[3];
        for (int run = 0; run < 3; run++) {
            boolean wet = run != 0;
            basin();
            Vec3d feet = wet ? new Vec3d(cx, surface - 4, cz) : new Vec3d(dryX, floor + 1, cz);
            EntityIronGolem g = golem(feet);
            HbmLivingProps.getData(g).setPhosphorus(0);
            float before = g.getHealth();
            EntityBulletBaseMK4 b = bullet(run == 2 ? plain : c, feet.add(-1.2, 1.2, 0), new Vec3d(1, 0, 0));
            fly(b, 10);
            took[run] = before - g.getHealth();
            if (run < 2) got[run] = HbmLivingProps.getData(g).getPhosphorus();
            g.setDead();
        }
        rowFlag("g: g12_phosphorus on a golem in water: not alight", "ammo_standard_g12_phosphorus", WaterClass.of(c), "phosphorus 0",
                "phosphorus " + got[1], got[1] == 0, "in the air: " + got[0]);
        rowFlag("g: g12_phosphorus in the air (NTM's)", "ammo_standard_g12_phosphorus", WaterClass.of(c), "phosphorus 300", "phosphorus " + got[0],
                got[0] >= 300, "");
        // in water it hits as a plain pellet: the damage x f^2 where the golem is, as a buckshot pellet's (g12) the same way
        row("g: g12_phosphorus on a golem in water, damage x g12's", "ammo_standard_g12_phosphorus", WaterClass.of(c), 1.0,
                took[2] > 0 ? took[1] / took[2] : Double.NaN, 0.01, "phosphorus " + fmt(took[1]) + ", g12 " + fmt(took[2]) + ", in the air "
                        + fmt(took[0]) + " (x f^2 " + fmt(took[0] > 0 ? took[1] / took[0] : Double.NaN) + ")");
    }

    // a sinking r762_he set off by a plain bullet that runs into it
    private static void touch(Map<String, BulletConfig> all) {
        BulletConfig he = byAmmo(all, "ammo_standard_r762_he");
        BulletConfig plain = null;
        for (BulletConfig k : BulletConfig.configs) if (k != null && WaterClass.ammoName(k).equals("ammo_standard_p9_fmj")) plain = k;
        if (he == null || plain == null) {
            lines.add("#\tno config for the touch");
            return;
        }
        basin();
        double x0 = minX + 3.5;
        EntityBulletBaseMK4 h = bullet(he, new Vec3d(x0, surface - 4, cz), new Vec3d(1, 0, 0));
        for (int i = 0; i < 40 && !WaterBullet.stopped(h) && !h.isDead; i++) h.onUpdate();
        h.onUpdate();
        boolean sinking = WaterBullet.stopped(h) && !h.isDead;
        level.spawnEntity(h);
        Vec3d at = pos(h);
        EntityBulletBaseMK4 p = bullet(plain, new Vec3d(at.x - 1.0, at.y, at.z), new Vec3d(1, 0, 0));
        BLASTS.clear();
        for (int i = 0; i < 6 && !p.isDead && BLASTS.isEmpty(); i++) p.onUpdate();
        double[] blast = BLASTS.isEmpty() ? null : BLASTS.get(0);
        rowFlag("h: a sinking r762_he hit by a 9 mm: goes off", "ammo_standard_r762_he", WaterClass.of(he), "blast at " + str3(at),
                str(blast), sinking && blast != null && new Vec3d(blast[0], blast[1], blast[2]).distanceTo(at) < 1.0,
                "sinking=" + sinking + " heRemoved=" + h.isDead);
        h.setDead();
    }

    // 26.2's deadPath (X560): an r762_he going off on the surface over a sinking one. NTM moves a bullet the whole tick's
    // way after its impact, also one gone there, so its path to touch others ends where it hit the water, not under it.
    // The one sinking under the line it came down along stays.
    private static void deadPath(Map<String, BulletConfig> all) {
        BulletConfig he = byAmmo(all, "ammo_standard_r762_he");
        if (he == null) {
            ok = false;
            lines.add("FAIL\tno config for the dead path");
            return;
        }
        basin();
        EntityBulletBaseMK4 h = bullet(he, new Vec3d(minX + 3.5, surface - 1.5, cz), new Vec3d(1, 0, 0));
        for (int i = 0; i < 40 && !WaterBullet.stopped(h) && !h.isDead; i++) h.onUpdate();
        h.onUpdate();
        boolean sinking = WaterBullet.stopped(h) && !h.isDead;
        level.spawnEntity(h);
        Vec3d s = pos(h);
        // 60 degrees down onto the surface right over it, from a block above: the tick ends past it, under the water
        double r = Math.toRadians(60), depth = surface - s.y;
        Vec3d dir = new Vec3d(Math.cos(r), -Math.sin(r), 0);
        Vec3d aim = new Vec3d(s.x - depth / Math.tan(r), surface, s.z);
        EntityBulletBaseMK4 p = bullet(he, aim.subtract(dir.scale(1.0)), dir);
        // another shot: the stand makes both in one game tick, with no shooter, which WaterImpact.sameShot leaves alone
        ((WaterBullet) p).gunfeel$shot(((WaterBullet) p).gunfeel$shot() + 1);
        BLASTS.clear();
        p.onUpdate();
        Vec3d after = pos(p);
        int blasts = BLASTS.size();
        rowFlag("h: one going off on the surface over a sinking one: that one stays", "ammo_standard_r762_he", WaterClass.of(he),
                "1 blast on the surface, the sinking one there", blasts + " blasts, sinking one " + (h.isDead ? "gone" : "there"),
                sinking && blasts == 1 && Math.abs(BLASTS.get(0)[1] - surface) < 0.35 && !h.isDead && p.isDead && after.y < s.y - 0.5,
                "sinking at " + str3(s) + ", the other moved on to " + str3(after) + ", blasts at y " + heights());
        h.setDead();
        p.setDead();
    }

    // HE pellets as a gun fires them: one loop of NTM's bullets from the shooter's eyes, the same tick, a spread; ticked
    // in the world's order (its ticksExisted up, then its tick, one pellet after the other), so a pellet's touch runs
    // with the next one a tick younger. In the air: no search for other bullets, no touch, no blast. In water at the
    // muzzle and fired at the floor: no touch within the shot. A shot into another's sinking pellets: they go off.
    private static void samePellets(Map<String, BulletConfig> all) {
        for (String ammo : new String[] {"ammo_standard_g12_explosive", "ammo_standard_g10_explosive"}) {
            BulletConfig c = byAmmo(all, ammo);
            if (c == null) {
                ok = false;
                lines.add("FAIL\tno config " + ammo);
                continue;
            }
            WaterClass wc = WaterClass.of(c);
            float spread = c.spread + 0.02F;
            int n = Math.max(2, c.projectilesMax);
            lines.add("#\t" + ammo + "\tpellets " + n + " spread " + fmt(spread) + " fuze " + WaterClass.Fuze.of(c));
            for (int k = 0; k < 3; k++) {
                basin();
                String name = k == 0 ? "air, at the muzzle" : k == 1 ? "in water, at the muzzle" : "in water, on the floor";
                // on the floor: the eyes half a block over it (the stand's feet in the floor), so the pellets water
                // stops lie on the floor together a few ticks later
                Vec3d feet = k == 0 ? new Vec3d(dryX - 5, floor + 1, cz) : k == 1 ? new Vec3d(minX + 3.5, floor + 1, cz)
                        : new Vec3d(minX + 3.5, floor + 1.5 - EYES, cz);
                EntityArmorStand g = shooter(feet, 0F);
                int s0 = WaterImpact.searches, t0 = WaterImpact.touches, m0 = WaterImpact.sameShots;
                BLASTS.clear();
                List<EntityBulletBaseMK4> shot = shoot(g, c, n, spread);
                int ticks = ticks(shot, k == 0 ? 6 : TICKS, false);
                int searches = WaterImpact.searches - s0, touches = WaterImpact.touches - t0, blasts = BLASTS.size();
                // port: in the air the pellets go on to the flat world's ground (26.2's basin hangs over nothing); what
                // counts is none at the muzzle
                Vec3d eyes = feet.add(0, EYES, 0);
                int nearMuzzle = 0;
                double nearest = Double.NaN;
                for (double[] bl : BLASTS) {
                    double d = eyes.distanceTo(new Vec3d(bl[0], bl[1], bl[2]));
                    nearest = Double.isNaN(nearest) ? d : Math.min(nearest, d);
                    if (d < 3) nearMuzzle++;
                }
                // in water the pellets of the shot did meet (pairs left alone), so 0 touches is the rule, not a miss
                int same = WaterImpact.sameShots - m0;
                // the air: the owner's case (pellets going off at the muzzle); in water a pellet goes off on the floor,
                // or NTM's life of it ends (no blast) while it sinks
                boolean pass = touches == 0 && (k == 0 ? searches == 0 && nearMuzzle == 0 : same > 0 && blasts <= n && (k == 1 || blasts > 0));
                rowFlag("h: one shot, " + name + ": no touch", ammo, wc, k == 0 ? "0 searches, 0 touches, 0 blasts within 3 of the muzzle" : k == 1 ? "0 touches"
                        : "0 touches, blasts on the floor",
                        searches + " searches, " + same + " pairs of the shot, " + touches + " touches, " + blasts + " blasts, " + nearMuzzle
                                + " within 3 of the muzzle", pass,
                        "ticks " + ticks + ", sameShot " + WaterImpact.sameShot(shot.get(0), shot.get(n - 1)) + ", blasts at y " + heights()
                                + ", the nearest " + fmt(nearest) + " from the muzzle");
                for (EntityBulletBaseMK4 b : shot) b.setDead();
                g.setDead();
            }
            // another shot into the sinking pellets of the first, both without spread so they meet
            basin();
            EntityArmorStand g = shooter(new Vec3d(minX + 3.5, floor + 1, cz), 0F);
            List<EntityBulletBaseMK4> first = shoot(g, c, n, 0F);
            ticks(first, 40, true);
            boolean sinking = false;
            for (EntityBulletBaseMK4 b : first) sinking |= WaterBullet.stopped(b) && !b.isDead;
            int t0 = WaterImpact.touches;
            BLASTS.clear();
            List<EntityBulletBaseMK4> second = shoot(g, c, n, 0F);
            for (EntityBulletBaseMK4 b : second) ((WaterBullet) b).gunfeel$shot(((WaterBullet) b).gunfeel$shot() + 1);
            List<EntityBulletBaseMK4> both = new ArrayList<EntityBulletBaseMK4>(second);
            both.addAll(first);
            ticks(both, 10, false);
            int touches = WaterImpact.touches - t0;
            rowFlag("h: another shot into one's sinking pellets: goes off", ammo, wc, "touches > 0, a blast", touches + " touches, "
                    + BLASTS.size() + " blasts", sinking && touches > 0 && !BLASTS.isEmpty(), "sinking=" + sinking + ", blasts at y " + heights());
            for (EntityBulletBaseMK4 b : both) b.setDead();
            g.setDead();
        }
    }

    // NTM's life of a round (BulletConfig.expires): NTM removes a round older than that without a blast
    // (EntityBulletBaseMK4.onUpdate), in water as in the air; it has no timed fuze for bullets. Ticked as the world
    // ticks (ticksExisted up, then the tick), from its first tick: stopped high in the basin, a round with a short
    // life is gone on the way down, one with a long life reaches the floor; stopped near the floor, any goes off there.
    private static void life(Map<String, BulletConfig> all) {
        // ammo, and does it reach the floor from high up within its life
        Object[][] cases = {{"ammo_standard_r762_he", false}, {"ammo_standard_g12_explosive", false}, {"ammo_standard_g40_he", true}};
        for (Object[] k : cases) {
            String ammo = (String) k[0];
            BulletConfig c = byAmmo(all, ammo);
            if (c == null) {
                ok = false;
                lines.add("FAIL\tno config " + ammo);
                continue;
            }
            WaterClass wc = WaterClass.of(c);
            for (boolean high : new boolean[] {true, false}) {
                basin();
                EntityBulletBaseMK4 b = bullet(c, new Vec3d(minX + 3.5, high ? surface - 1 : floor + 1.6, cz), new Vec3d(1, 0, 0));
                b.ticksExisted = 0;
                BLASTS.clear();
                double stopY = Double.NaN, lastY = Double.NaN;
                int stopTick = -1, gone = -1;
                for (int i = 0; i < c.expires + 20 && !b.isDead; i++) {
                    b.ticksExisted++;
                    boolean was = WaterBullet.stopped(b);
                    lastY = b.posY;
                    b.onUpdate();
                    if (!was && WaterBullet.stopped(b)) {
                        stopY = b.posY;
                        stopTick = b.ticksExisted;
                    }
                    if (b.isDead) gone = b.ticksExisted;
                }
                double[] blast = BLASTS.isEmpty() ? null : BLASTS.get(0);
                boolean floorWanted = !high || (Boolean) k[1];
                boolean pass = stopTick > 0 && (floorWanted ? blast != null && blast[1] < floor + 1.4
                        : blast == null && gone == c.expires + 1 && lastY > floor + 1.5);
                String note = "life " + c.expires + ", stopped tick " + stopTick + " y " + fmt(stopY) + ", gone tick " + gone + " y " + fmt(lastY)
                        + ", blast " + str(blast);
                rowFlag("i: stopped " + (high ? "high (" + fmt(surface - 1 - floor - 1) + " over the floor)" : "near the floor") + ", NTM's life",
                        ammo, wc, floorWanted ? "goes off on the floor" : "gone at tick " + (c.expires + 1) + ", no blast",
                        blast != null ? "blast at y " + fmt(blast[1]) : gone > 0 ? "gone at tick " + gone + ", no blast" : "still there", pass, note);
                b.setDead();
            }
        }
    }

    // HE pellets fired straight down by a shooter standing in water: water stops them within 0.57 of its eyes, inside
    // its box. A pellet water stopped in its shooter does not hit it from inside (MixinThrowableClip). With the feet on
    // the floor the pellets sink and go off on it; with the floor farther than their life takes them (NTM's 30 ticks)
    // they are gone on the way, as any round NTM's life ends.
    private static void downward(Map<String, BulletConfig> all) {
        String ammo = "ammo_standard_g10_explosive";
        BulletConfig c = byAmmo(all, ammo);
        if (c == null) {
            ok = false;
            lines.add("FAIL\tno config " + ammo);
            return;
        }
        WaterClass wc = WaterClass.of(c);
        int n = Math.max(2, c.projectilesMax);
        for (boolean onFloor : new boolean[] {true, false}) {
            basin();
            Vec3d feet = new Vec3d(minX + 3.5, onFloor ? floor + 1 : floor + 4, cz);
            EntityArmorStand g = shooter(feet, 90F);
            AxisAlignedBB box = g.getEntityBoundingBox();
            BLASTS.clear();
            List<EntityBulletBaseMK4> shot = shoot(g, c, n, c.spread);
            int inBox = 0, silentEarly = 0, goneLo = Integer.MAX_VALUE, goneHi = -1;
            boolean[] seen = new boolean[n];
            for (int t = 0; t < c.expires + 20; t++) {
                boolean any = false;
                for (int i = 0; i < n; i++) {
                    EntityBulletBaseMK4 b = shot.get(i);
                    if (b.isDead) continue;
                    any = true;
                    int blasts = BLASTS.size();
                    boolean was = WaterBullet.stopped(b);
                    b.ticksExisted++;
                    b.onUpdate();
                    if (!was && WaterBullet.stopped(b) && box.contains(pos(b))) inBox++;
                    if (b.isDead && !seen[i]) {
                        seen[i] = true;
                        goneLo = Math.min(goneLo, b.ticksExisted);
                        goneHi = Math.max(goneHi, b.ticksExisted);
                        if (BLASTS.size() == blasts && b.ticksExisted <= c.expires) silentEarly++;
                    }
                }
                if (!any) break;
            }
            // a blast in the shooter's box over its feet: a pellet that hit its shooter
            int onShooter = 0, onFloorBlasts = 0;
            for (double[] bl : BLASTS) {
                if (bl[1] < floor + 1.4) onFloorBlasts++;
                else if (box.contains(new Vec3d(bl[0], bl[1], bl[2]))) onShooter++;
            }
            boolean pass = inBox == n && onShooter == 0 && silentEarly == 0
                    && (onFloor ? onFloorBlasts == n : BLASTS.isEmpty() && goneLo == c.expires + 1 && goneHi == c.expires + 1);
            rowFlag("j: shot straight down in water, feet " + (onFloor ? "on the floor" : "3 over the floor"), ammo, wc,
                    n + " stop in the shooter's box, none hits it, " + (onFloor ? n + " blasts on the floor" : "all gone at tick " + (c.expires + 1) + ", no blast"),
                    inBox + " stop in the box, " + onShooter + " on the shooter, " + onFloorBlasts + " on the floor, " + BLASTS.size() + " blasts, gone ticks "
                            + goneLo + ".." + goneHi,
                    pass, "life " + c.expires + ", box y " + fmt(box.minY) + ".." + fmt(box.maxY) + ", silent before the life's end " + silentEarly
                            + ", blasts at y " + heights());
            for (EntityBulletBaseMK4 b : shot) b.setDead();
            g.setDead();
        }
    }

    // a shooter as narrow as a player (an armour stand, 0.5 wide, its eyes 1.78 over its feet), facing +x; a pellet
    // water stops within 0.57 of the eyes (g10) is out of its box, as out of a player's
    private static final double EYES = 1.7775;

    private static EntityArmorStand shooter(Vec3d feet, float pitch) {
        EntityArmorStand g = new EntityArmorStand(level);
        g.setPosition(feet.x, feet.y, feet.z);
        g.setNoGravity(true);
        g.rotationYaw = g.prevRotationYaw = g.rotationYawHead = -90F;
        g.rotationPitch = g.prevRotationPitch = pitch;
        level.spawnEntity(g);
        return g;
    }

    // the pellets of one shot, made as a gun makes them (EntityBulletBaseMK4 from the shooter, in a loop)
    private static List<EntityBulletBaseMK4> shoot(EntityArmorStand g, BulletConfig c, int n, float spread) {
        List<EntityBulletBaseMK4> out = new ArrayList<EntityBulletBaseMK4>();
        for (int i = 0; i < n; i++) {
            EntityBulletBaseMK4 b = new EntityBulletBaseMK4(g, c, DAMAGE, spread, 0, 0, 0);
            level.spawnEntity(b);
            out.add(b);
        }
        return out;
    }

    // the world's order: each one's ticksExisted up, then its tick; till all are gone or `ticks` pass (or, `stopped`,
    // till water stopped them all); the ticks it took
    private static int ticks(List<EntityBulletBaseMK4> bullets, int ticks, boolean stopped) {
        for (int i = 0; i < ticks; i++) {
            boolean any = false, moving = false;
            for (EntityBulletBaseMK4 b : bullets) {
                if (b.isDead) continue;
                any = true;
                b.ticksExisted++;
                b.onUpdate();
                if (!WaterBullet.stopped(b) && !b.isDead) moving = true;
            }
            if (!any || stopped && !moving) return i + 1;
        }
        return ticks;
    }

    // the blasts' heights, lowest..highest
    private static String heights() {
        double lo = Double.NaN, hi = Double.NaN;
        for (double[] b : BLASTS) {
            lo = Double.isNaN(lo) ? b[1] : Math.min(lo, b[1]);
            hi = Double.isNaN(hi) ? b[1] : Math.max(hi, b[1]);
        }
        return fmt(lo) + ".." + fmt(hi);
    }

    private static void check() {
        List<String> wrong = new ArrayList<String>();
        lines.add("#\tmixins\t" + MixinCheck.report(wrong));
        ok &= wrong.isEmpty();
        lines.add(String.join("\t", "k: mixin handlers", "-", "-", "as MixinCheck wants", wrong.isEmpty() ? "all" : String.join("; ", wrong), "",
                wrong.isEmpty() ? "OK" : "FAIL", ""));
        lines.add("#\tcounters\tsearches " + WaterImpact.searches + " sameShots " + WaterImpact.sameShots + " touches " + WaterImpact.touches);
        lines.add("result\t" + (ok ? "OK" : "FAIL"));
        try (PrintWriter pw = new PrintWriter(new File(Minecraft.getMinecraft().gameDir, "boom.tsv"), StandardCharsets.UTF_8.name())) {
            for (String l : lines) pw.println(l);
        } catch (java.io.IOException e) {
            GunFeel.LOGGER.error("Scenario boom: boom.tsv", e);
        }
        GunFeel.LOGGER.info("Scenario boom: {} lines, result {}", lines.size(), ok ? "OK" : "FAIL");
    }

    private static void row(String name, String ammo, WaterClass wc, double want, double got, double tol, String note) {
        double diff = got - want;
        boolean pass = !Double.isNaN(got) && Math.abs(diff) <= tol;
        ok &= pass;
        lines.add(String.join("\t", name, ammo, wc.name(), fmt(want), fmt(got), fmt(diff), pass ? "OK" : "FAIL", note));
    }

    private static void rowFlag(String name, String ammo, WaterClass wc, String want, String got, boolean pass, String note) {
        ok &= pass;
        lines.add(String.join("\t", name, ammo, wc.name(), want, got, "", pass ? "OK" : "FAIL", note));
    }

    private static String str(double[] b) {
        return b == null ? "none" : "(" + fmt(b[0]) + " " + fmt(b[1]) + " " + fmt(b[2]) + " size " + fmt(b[3]) + ")";
    }

    private static String str3(Vec3d v) {
        return "(" + fmt(v.x) + " " + fmt(v.y) + " " + fmt(v.z) + ")";
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "nan" : String.format(Locale.ROOT, "%.4f", v);
    }
}
