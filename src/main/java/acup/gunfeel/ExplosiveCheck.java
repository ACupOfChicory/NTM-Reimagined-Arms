package acup.gunfeel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.hbm.entity.effect.EntityFireLingering;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.extprop.HbmLivingProps;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.water.WaterBlast;
import acup.gunfeel.water.WaterBullet;
import acup.gunfeel.water.WaterClass;
import acup.gunfeel.water.WaterImpact;
import acup.gunfeel.water.WaterPath;
import acup.gunfeel.water.WaterSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

// The explosives in water, measured on a dedicated server with no client (a dev run only: ./gradlew runCheckBoom
// -PnoAssets). -Dntmgunfeel.checkBoom=<file.tsv>: in a stone basin of still water every NTM projectile with an impact
// fuze (no nukes) is fired, ticked by hand, and one tsv line is written per measurement:
// - into the water at 30° (goes off on the surface) and at 5° (skips, goes off later);
// - from 3 under the water at a wall 1, 3 and 6 blocks off (on the wall, or stopped short, sinks, on the floor);
// - stopped by water: sinks at no more than 1 block a second, goes off on the floor;
// - the damage a golem 2 blocks off takes from its blast in the water and over it, against the same blast in the air;
// and b75_inc's small blast, the fire and phosphorus put out, an explosive set off by a bullet and pellets of one
// shot that do not set each other off; a round stopped high over the floor with NTM's life ticking (gone without a
// blast when the life ends) and HE pellets shot straight down from inside the shooter's box. Without the property
// nothing is registered.
public final class ExplosiveCheck {
    public static final String PROPERTY = "ntmgunfeel.checkBoom";

    private ExplosiveCheck() {
    }

    public static void registerIfAsked() {
        String file = System.getProperty(PROPERTY);
        if (file == null || file.isBlank()) return;
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, started -> {
            for (int cx = -1; cx < 5; cx++) for (int cz = -1; cz < 5; cz++) started.getServer().overworld().setChunkForced(cx, cz, true);
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
                } finally {
                    WaterBlast.watch = null;
                }
                BulletCheck.finish(file, out, code);
            });
        });
    }

    // the basin: x, z from 4 to 43, the floor at y 100, water 101..108; a dry stone pad for the air's blasts
    private static final int FLOOR = 100, DEPTH = 8, MIN = 4, SIZE = 40;
    private static final double CX = MIN + SIZE / 2.0, CZ = MIN + SIZE / 2.0;
    private static final double DRY_X = MIN + SIZE + 12;
    private static final float DAMAGE = 20F;
    private static final int TICKS = 200;
    private static ServerLevel level;
    private static List<String> lines;
    private static boolean ok;
    private static double surface;
    private static final List<double[]> BLASTS = new ArrayList<>();

    private static boolean run(List<String> out, MinecraftServer server) {
        lines = out;
        ok = true;
        level = server.overworld();
        lines.add(String.join("\t", "case", "ammo", "class", "ожидание", "замер", "разница", "ок", "прим"));
        basin();
        BlockPos top = new BlockPos((int) CX, FLOOR + DEPTH, (int) CZ);
        surface = top.getY() + level.getFluidState(top).getHeight(level, top);
        if (!WaterSettings.ballistics() || !WaterSettings.fuze()) {
            ok = false;
            lines.add("FAIL\twaterBallistics or waterFuze is off");
        }
        lines.add("#\tsurface\t" + fmt(surface) + "\tfuze skip " + WaterSettings.fuzeSkipAngle() + "\tblast radius " + WaterSettings.blastRadius());
        WaterBlast.watch = e -> BLASTS.add(new double[] {e.posX, e.posY, e.posZ, e.size});
        Map<String, BulletConfig> all = projectiles();
        for (var e : all.entrySet()) {
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
        weak(all);
        fire(all);
        phosphorus();
        touch(all);
        samePellets(all);
        life(all);
        downward(all);
        return ok;
    }

    // NTM's projectiles with an impact fuze, one per ammo; no nukes (they would take the basin and the chunks with them)
    private static Map<String, BulletConfig> projectiles() {
        Map<String, BulletConfig> out = new LinkedHashMap<>();
        for (BulletConfig c : BulletConfig.configs) {
            if (c == null || !WaterClass.Fuze.of(c).any()) continue;
            String ammo = ammoName(c);
            if (ammo.contains("nuke") || out.containsKey(ammo)) continue;
            out.put(ammo, c);
        }
        return out;
    }

    private static void basin() {
        int lo = MIN - 1, hi = MIN + SIZE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = lo; x <= hi; x++) for (int z = lo; z <= hi; z++) {
            boolean wall = x == lo || x == hi || z == lo || z == hi;
            for (int y = FLOOR; y <= FLOOR + DEPTH + 4; y++) {
                var s = y == FLOOR || wall && y <= FLOOR + DEPTH + 1 ? Blocks.STONE.defaultBlockState()
                        : y <= FLOOR + DEPTH ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
                level.setBlock(p.set(x, y, z), s, Block.UPDATE_CLIENTS);
            }
        }
        // the dry pad
        for (int x = (int) DRY_X - 6; x <= DRY_X + 6; x++) for (int z = (int) CZ - 6; z <= CZ + 6; z++)
            for (int y = FLOOR; y <= FLOOR + 8; y++)
                level.setBlock(p.set(x, y, z), y == FLOOR ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        clear();
    }

    // what blasts and bullets left: items, fire, golems
    private static void clear() {
        AABB box = new AABB(MIN - 8, FLOOR - 4, MIN - 8, DRY_X + 10, FLOOR + 24, MIN + SIZE + 8);
        for (var e : level.getEntitiesOfClass(ItemEntity.class, box, x -> true)) e.discard();
        for (var e : level.getEntitiesOfClass(EntityFireLingering.class, box, x -> true)) e.discard();
        for (var e : level.getEntitiesOfClass(IronGolem.class, box, x -> true)) e.discard();
        for (var e : level.getEntitiesOfClass(EntityBulletBaseMK4.class, box, x -> true)) e.discard();
    }

    private static String ammoName(BulletConfig c) {
        try {
            if (c.ammoItem != null) return BuiltInRegistries.ITEM.getKey(c.ammoItem.get()).getPath();
        } catch (RuntimeException e) {
            // no item
        }
        return "id" + c.id;
    }

    private static BulletConfig byAmmo(Map<String, BulletConfig> all, String name) {
        BulletConfig c = all.get(name);
        if (c != null) return c;
        for (BulletConfig k : BulletConfig.configs) if (k != null && ammoName(k).equals(name)) return k;
        return null;
    }

    // as NTM fires it: the heading at 1, the speed is motionMult's
    private static EntityBulletBaseMK4 bullet(BulletConfig c, Vec3 at, Vec3 dir) {
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(level, c, 1F, 0F, 0F, 0F);
        b.setPos(at.x, at.y, at.z);
        b.setThrowableHeading(dir.x, dir.y, dir.z, 1F, 0F);
        b.damage = DAMAGE;
        // NTM counts the ticks in the world; here it is ticked by hand: past the bomblets' 2 ticks of arming
        b.tickCount = 10;
        return b;
    }

    // the stop distance in still water (density 1)
    private static double stop(WaterClass wc) {
        return WaterPath.stopDepth(wc, null, WaterPath.Settings.current());
    }

    // ticks it till it is gone; the first blast, or null
    private static double[] fly(EntityBulletBaseMK4 b, int ticks) {
        BLASTS.clear();
        for (int i = 0; i < ticks && !b.isRemoved(); i++) b.tick();
        return BLASTS.isEmpty() ? null : BLASTS.get(0);
    }

    // into the water from the air at an angle: steeper than the fuze's 10° it goes off on the surface, shallower it
    // skips and goes off at its next touch. The angle is the one it meets the water at (a grenade's falls steeper).
    private static void entry(String ammo, BulletConfig c, WaterClass wc, int deg) {
        basin();
        double r = Math.toRadians(deg), run = deg >= 20 ? 4 : 10;
        Vec3 dir = new Vec3(Math.cos(r), -Math.sin(r), 0);
        Vec3 aim = new Vec3(MIN + 14, surface, CZ);
        // after a skip its next touch: a wall further on
        if (deg < 20) for (int y = FLOOR + 1; y <= FLOOR + DEPTH + 8; y++) for (int z = (int) CZ - 4; z <= CZ + 4; z++)
            level.setBlock(new BlockPos(MIN + 30, y, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        EntityBulletBaseMK4 b = bullet(c, aim.subtract(dir.scale(run)), dir);
        BLASTS.clear();
        double angle = Double.NaN, firstTouchX = Double.NaN;
        int skips = 0;
        for (int i = 0; i < TICKS && !b.isRemoved(); i++) {
            Vec3 v = b.getDeltaMovement();
            double a = Math.toDegrees(Math.atan2(-v.y, Math.hypot(v.x, v.z)));
            int before = ((WaterBullet) b).gunfeel$waterSkips();
            Vec3 at = b.position();
            b.tick();
            int now = ((WaterBullet) b).gunfeel$waterSkips();
            if (now > before && skips == 0) {
                firstTouchX = b.getX();
                angle = a;
            }
            skips = now;
            if (!BLASTS.isEmpty() && Double.isNaN(angle)) angle = a;
            if (!BLASTS.isEmpty() || b.isRemoved()) break;
            if (b.getY() < FLOOR - 2 || b.getX() > MIN + SIZE + 20) break;
            if (Double.isNaN(angle) && at.y > surface && b.getY() < surface - 0.01) angle = a;
        }
        double[] blast = BLASTS.isEmpty() ? null : BLASTS.get(0);
        String note = "skips=" + skips + " angle=" + fmt(angle) + " blast=" + str(blast) + " removed=" + b.isRemoved();
        double fuze = WaterSettings.fuzeSkipAngle();
        if (skips == 0) {
            // on the surface where it came in
            boolean good = blast != null && blast[1] >= surface - 0.05 && blast[1] <= surface + 0.35 && !(angle < fuze - 0.5);
            rowFlag("a: into water at " + deg + "°: goes off on the surface", ammo, wc, "blast y " + fmt(surface) + "..+0.3",
                    blast == null ? "none" : fmt(blast[1]), good, note);
        } else {
            // skipped where it met the water (shallower than the fuze), went off later, not there
            // b75_inc: its next touch is in the air, where it is NTM's (no blast)
            boolean weak = WaterClass.Fuze.of(c) == WaterClass.Fuze.WEAK;
            boolean good = angle < fuze + 0.5 && (weak ? blast == null && b.isRemoved() : blast != null && Math.abs(blast[0] - firstTouchX) > 0.5);
            rowFlag("a: into water at " + deg + "°: skips, goes off later", ammo, wc, "skip, blast past x " + fmt(firstTouchX),
                    blast == null ? "none" : "x " + fmt(blast[0]) + " y " + fmt(blast[1]), good, note);
        }
    }

    // from 3 under the water along x at a stone wall d blocks off: on the wall if it gets there, else it stops,
    // sinks and goes off on the floor under where it stopped
    private static void wall(String ammo, BulletConfig c, WaterClass wc, int d) {
        basin();
        double x0 = MIN + 3.5;
        int wx = (int) Math.floor(x0 + d);
        for (int y = FLOOR + 1; y <= FLOOR + DEPTH; y++) for (int z = (int) CZ - 3; z <= CZ + 3; z++)
            level.setBlock(new BlockPos(wx, y, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        EntityBulletBaseMK4 b = bullet(c, new Vec3(x0, surface - 3, CZ), new Vec3(1, 0, 0));
        double[] blast = fly(b, TICKS);
        double s = stop(wc), face = wx;
        String note = "blast=" + str(blast) + " stopped=" + WaterBullet.stopped(b) + " removed=" + b.isRemoved() + " stop=" + fmt(s);
        if (x0 + s > face) {
            boolean good = blast != null && Math.abs(blast[0] - face) < 0.45 && blast[1] > FLOOR + 1.5;
            rowFlag("b: under water, wall " + d + ": on the wall", ammo, wc, "x " + fmt(face), blast == null ? "none" : "x " + fmt(blast[0]), good, note);
        } else {
            boolean good = blast != null && blast[1] < FLOOR + 1.4 && blast[0] < face;
            rowFlag("b: under water, wall " + d + ": stops, on the floor", ammo, wc, "y " + (FLOOR + 1) + ", x < " + fmt(face),
                    blast == null ? "none" : "x " + fmt(blast[0]) + " y " + fmt(blast[1]), good, note);
        }
    }

    // stopped by water: from 5 under the water along x with nothing in its way; then down at no more than 0.05 a
    // tick, and off on the floor
    private static void stopped(String ammo, BulletConfig c, WaterClass wc) {
        basin();
        double x0 = MIN + 3.5;
        EntityBulletBaseMK4 b = bullet(c, new Vec3(x0, surface - 5, CZ), new Vec3(1, 0, 0));
        BLASTS.clear();
        double fastest = 0, stopX = Double.NaN;
        int sinkTicks = 0;
        for (int i = 0; i < 400 && !b.isRemoved() && BLASTS.isEmpty(); i++) {
            Vec3 was = b.position();
            boolean wasStopped = WaterBullet.stopped(b);
            b.tick();
            if (wasStopped && BLASTS.isEmpty() && !b.isRemoved()) {
                fastest = Math.max(fastest, was.y - b.getY());
                sinkTicks++;
                if (Math.abs(b.getX() - was.x) > 1e-9) fastest = Double.POSITIVE_INFINITY;
            }
            if (!wasStopped && WaterBullet.stopped(b)) stopX = b.getX();
        }
        double[] blast = BLASTS.isEmpty() ? null : BLASTS.get(0);
        String note = "stopX-x0=" + fmt(stopX - x0) + " sinkTicks=" + sinkTicks + " blast=" + str(blast);
        row("c: stopped, distance", ammo, wc, stop(wc), stopX - x0, 0.15, note);
        rowFlag("c: stopped, sinks ≤ 0.05/tick", ammo, wc, "≤ 0.0500", fmt(fastest), !Double.isNaN(stopX) && fastest <= 0.05 + 1e-9 && sinkTicks > 0, note);
        rowFlag("c: stopped, goes off on the floor", ammo, wc, "y " + (FLOOR + 1), blast == null ? "none" : fmt(blast[1]),
                blast != null && blast[1] < FLOOR + 1.4, note);
    }

    // the health a golem 2 blocks off (box to centre) takes from the blast set off at `at`
    private static double golemTook(BulletConfig c, Vec3 at, Vec3 golemFeet, double[] blastOut) {
        clear();
        IronGolem g = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
        g.setPos(golemFeet.x, golemFeet.y, golemFeet.z);
        g.setNoAi(true);
        g.setNoGravity(true);
        level.addFreshEntity(g);
        // its in-water state
        g.tick();
        g.setPos(golemFeet.x, golemFeet.y, golemFeet.z);
        float before = g.getHealth();
        EntityBulletBaseMK4 b = bullet(c, at, new Vec3(0, -1, 0));
        BlockPos under = BlockPos.containing(at.x, at.y - 0.5, at.z);
        BLASTS.clear();
        ((WaterImpact.Hits) b).gunfeel$impact(new BlockHitResult(at, Direction.UP, under, false));
        double took = before - g.getHealth();
        if (!BLASTS.isEmpty()) {
            double[] k = BLASTS.get(0);
            System.arraycopy(k, 0, blastOut, 0, 4);
            // the box's distance to the centre, as NTM counts it
            AABB box = g.getBoundingBox();
            double dx = Math.max(0, Math.max(box.minX - k[0], k[0] - box.maxX)), dy = Math.max(0, Math.max(box.minY - k[1], k[1] - box.maxY)),
                    dz = Math.max(0, Math.max(box.minZ - k[2], k[2] - box.maxZ));
            blastOut[4] = Math.sqrt(dx * dx + dy * dy + dz * dz);
            blastOut[5] = WaterBlast.inWater(g) ? 1 : 0;
        }
        g.discard();
        return took;
    }

    // a golem 2 blocks off: in the air, in the water by a blast in it, and over the water by a blast just under the
    // surface. CrossSmooth: fixed × (1 - d / range), range 2 × size, in water × waterBlastRadius; over it half.
    private static void damage(String ammo, BulletConfig c, WaterClass wc) {
        if (WaterClass.Fuze.of(c) == WaterClass.Fuze.WEAK) return; // no blast in the air: weak()
        basin();
        double half = 0.7; // the golem's half width
        double[] air = new double[6], wet = new double[6], over = new double[6];
        Vec3 dryAt = new Vec3(DRY_X, FLOOR + 3, CZ);
        double tAir = golemTook(c, dryAt, new Vec3(DRY_X + 2 + half, FLOOR + 3 - 1.35, CZ), air);
        basin();
        Vec3 wetAt = new Vec3(CX, surface - 5, CZ);
        double tWet = golemTook(c, wetAt, new Vec3(CX + 2 + half, surface - 5 - 1.35, CZ), wet);
        basin();
        Vec3 overAt = new Vec3(CX, surface - 1, CZ);
        double tOver = golemTook(c, overAt, new Vec3(CX, surface + 1, CZ), over);
        float mult = Math.max(1F, WaterSettings.blastRadius());
        double size = air[3];
        String note = "air=" + fmt(tAir) + " d=" + fmt(air[4]) + " | wet=" + fmt(tWet) + " d=" + fmt(wet[4]) + " golemWet=" + (int) wet[5]
                + " | over=" + fmt(tOver) + " d=" + fmt(over[4]) + " golemWet=" + (int) over[5] + " | size=" + fmt(size);
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
        double half = 0.7;
        double[] a = new double[6], b = new double[6];
        basin();
        double tInc = golemTook(inc, new Vec3(CX, surface - 5, CZ), new Vec3(CX + 2 + half, surface - 5 - 1.35, CZ), a);
        basin();
        double tExp = golemTook(exp, new Vec3(CX, surface - 5, CZ), new Vec3(CX + 2 + half, surface - 5 - 1.35, CZ), b);
        float mult = Math.max(1F, WaterSettings.blastRadius());
        // CrossSmooth both: damage × 0.25, the range 2.5 against 5
        double want = 0.25 * (1 - a[4] / (2 * 2.5 * mult)) / (1 - b[4] / (2 * 5 * mult));
        row("e: b75_inc under water, × b75_exp's", "ammo_standard_b75_inc", WaterClass.of(inc), want, tExp > 0 ? tInc / tExp : Double.NaN, 0.05,
                "inc=" + fmt(tInc) + " size=" + fmt(a[3]) + " exp=" + fmt(tExp) + " size=" + fmt(b[3]));
        // and in the air it is NTM's: no blast
        basin();
        EntityBulletBaseMK4 k = bullet(inc, new Vec3(DRY_X, FLOOR + 3, CZ), new Vec3(0, -1, 0));
        BLASTS.clear();
        ((WaterImpact.Hits) k).gunfeel$impact(new BlockHitResult(new Vec3(DRY_X, FLOOR + 1, CZ), Direction.UP, new BlockPos((int) DRY_X, FLOOR, (int) CZ), false));
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
                Vec3 at = wet == 1 ? new Vec3(CX, surface - 3, CZ) : new Vec3(DRY_X, FLOOR + 1, CZ);
                EntityBulletBaseMK4 b = bullet(c, at.add(0, 1, 0), new Vec3(0, -1, 0));
                b.tickCount = 10;
                BLASTS.clear();
                ((WaterImpact.Hits) b).gunfeel$impact(new BlockHitResult(at, Direction.UP, BlockPos.containing(at.x, at.y - 0.5, at.z), false));
                blasts[wet] = BLASTS.size();
                fires[wet] = level.getEntitiesOfClass(EntityFireLingering.class, new AABB(at, at).inflate(8), x -> true).size();
            }
            rowFlag("f: fire in water: blast, no fire", name, WaterClass.of(c), "blast 1, fire 0", "blast " + blasts[1] + ", fire " + fires[1],
                    blasts[1] == 1 && fires[1] == 0, "in the air: blast " + blasts[0] + ", fire " + fires[0]);
            rowFlag("f: fire in the air (NTM's)", name, WaterClass.of(c), "fire ≥ 1", "fire " + fires[0], fires[0] >= 1, "");
        }
    }

    // a phosphorus pellet on a golem in water sets it not alight; in the air NTM's 300 ticks
    private static void phosphorus() {
        BulletConfig c = null;
        // the shotgun's own (the Shredder's shares the ammo)
        for (BulletConfig k : BulletConfig.configs) if (c == null && k != null && ammoName(k).equals("ammo_standard_g12_phosphorus")) c = k;
        if (c == null) {
            lines.add("#\tno config ammo_standard_g12_phosphorus");
            return;
        }
        int[] got = new int[2];
        for (int wet = 0; wet < 2; wet++) {
            basin();
            Vec3 feet = wet == 1 ? new Vec3(CX, surface - 4, CZ) : new Vec3(DRY_X, FLOOR + 1, CZ);
            IronGolem g = EntityTypes.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
            g.setPos(feet.x, feet.y, feet.z);
            g.setNoAi(true);
            g.setNoGravity(true);
            level.addFreshEntity(g);
            g.tick();
            g.setPos(feet.x, feet.y, feet.z);
            HbmLivingProps.getData(g).phosphorus = 0;
            EntityBulletBaseMK4 b = bullet(c, feet.add(-1.2, 1.2, 0), new Vec3(1, 0, 0));
            fly(b, 10);
            got[wet] = HbmLivingProps.getData(g).phosphorus;
            g.discard();
        }
        rowFlag("g: g12_phosphorus on a golem in water: not alight", "ammo_standard_g12_phosphorus", WaterClass.of(c), "phosphorus 0",
                "phosphorus " + got[1], got[1] == 0, "in the air: " + got[0]);
        rowFlag("g: g12_phosphorus in the air (NTM's)", "ammo_standard_g12_phosphorus", WaterClass.of(c), "phosphorus 300", "phosphorus " + got[0],
                got[0] >= 300, "");
    }

    // a sinking r762_he set off by a plain bullet that runs into it
    private static void touch(Map<String, BulletConfig> all) {
        BulletConfig he = byAmmo(all, "ammo_standard_r762_he");
        BulletConfig plain = null;
        for (BulletConfig k : BulletConfig.configs) if (k != null && ammoName(k).equals("ammo_standard_p9_fmj")) plain = k;
        if (he == null || plain == null) {
            lines.add("#\tno config for the touch");
            return;
        }
        basin();
        double x0 = MIN + 3.5;
        EntityBulletBaseMK4 h = bullet(he, new Vec3(x0, surface - 4, CZ), new Vec3(1, 0, 0));
        for (int i = 0; i < 40 && !WaterBullet.stopped(h) && !h.isRemoved(); i++) h.tick();
        h.tick();
        boolean sinking = WaterBullet.stopped(h) && !h.isRemoved();
        level.addFreshEntity(h);
        Vec3 at = h.position();
        EntityBulletBaseMK4 p = bullet(plain, new Vec3(at.x - 1.0, at.y, at.z), new Vec3(1, 0, 0));
        BLASTS.clear();
        for (int i = 0; i < 6 && !p.isRemoved() && BLASTS.isEmpty(); i++) p.tick();
        double[] blast = BLASTS.isEmpty() ? null : BLASTS.get(0);
        rowFlag("h: a sinking r762_he hit by a 9 mm: goes off", "ammo_standard_r762_he", WaterClass.of(he), "blast at " + str3(at),
                str(blast), sinking && blast != null && new Vec3(blast[0], blast[1], blast[2]).distanceTo(at) < 1.0,
                "sinking=" + sinking + " heRemoved=" + h.isRemoved());
        h.discard();
    }

    // HE pellets as a gun fires them: one loop of NTM's bullets from the shooter's eyes, the same tick, a spread; ticked
    // in the world's order (its tickCount up, then its tick, one pellet after the other), so a pellet's touch runs
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
                Vec3 feet = k == 0 ? new Vec3(DRY_X - 5, FLOOR + 1, CZ) : k == 1 ? new Vec3(MIN + 3.5, FLOOR + 1, CZ)
                        : new Vec3(MIN + 3.5, FLOOR + 1.5 - EYES, CZ);
                ArmorStand g = shooter(feet, 0F);
                int s0 = WaterImpact.searches, t0 = WaterImpact.touches, m0 = WaterImpact.sameShots;
                BLASTS.clear();
                List<EntityBulletBaseMK4> shot = shoot(g, c, n, spread);
                int ticks = ticks(shot, k == 0 ? 6 : TICKS, false);
                int searches = WaterImpact.searches - s0, touches = WaterImpact.touches - t0, blasts = BLASTS.size();
                // in water the pellets of the shot did meet (pairs left alone), so 0 touches is the rule, not a miss
                int same = WaterImpact.sameShots - m0;
                // the air: the owner's case (pellets going off at the muzzle); in water a pellet goes off on the floor
                // a pellet in water goes off on the floor, or NTM's life of it ends (no blast) while it sinks
                boolean pass = touches == 0 && (k == 0 ? searches == 0 && blasts == 0 : same > 0 && blasts <= n && (k == 1 || blasts > 0));
                rowFlag("h: one shot, " + name + ": no touch", ammo, wc, k == 0 ? "0 searches, 0 touches, 0 blasts" : k == 1 ? "0 touches"
                        : "0 touches, blasts on the floor",
                        searches + " searches, " + same + " pairs of the shot, " + touches + " touches, " + blasts + " blasts", pass,
                        "ticks " + ticks + ", sameShot " + WaterImpact.sameShot(shot.get(0), shot.get(n - 1)) + ", blasts at y " + heights());
                for (var b : shot) b.discard();
                g.discard();
            }
            // another shot into the sinking pellets of the first, both without spread so they meet
            basin();
            ArmorStand g = shooter(new Vec3(MIN + 3.5, FLOOR + 1, CZ), 0F);
            List<EntityBulletBaseMK4> first = shoot(g, c, n, 0F);
            ticks(first, 40, true);
            boolean sinking = first.stream().anyMatch(b -> WaterBullet.stopped(b) && !b.isRemoved());
            int t0 = WaterImpact.touches;
            BLASTS.clear();
            List<EntityBulletBaseMK4> second = shoot(g, c, n, 0F);
            for (var b : second) ((WaterBullet) b).gunfeel$shot(((WaterBullet) b).gunfeel$shot() + 1);
            List<EntityBulletBaseMK4> both = new ArrayList<>(second);
            both.addAll(first);
            ticks(both, 10, false);
            int touches = WaterImpact.touches - t0;
            rowFlag("h: another shot into one's sinking pellets: goes off", ammo, wc, "touches > 0, a blast", touches + " touches, "
                    + BLASTS.size() + " blasts", sinking && touches > 0 && !BLASTS.isEmpty(), "sinking=" + sinking + ", blasts at y " + heights());
            for (var b : both) b.discard();
            g.discard();
        }
    }

    // NTM's life of a round (BulletConfig.expires): NTM removes a round older than that without a blast
    // (EntityBulletBaseMK4.tick), in water as in the air; it has no timed fuze for bullets. Ticked as the world ticks
    // (tickCount up, then the tick), from its first tick: stopped high in the basin, a round with a short life is gone
    // on the way down, one with a long life reaches the floor; stopped near the floor, any goes off there.
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
                EntityBulletBaseMK4 b = bullet(c, new Vec3(MIN + 3.5, high ? surface - 1 : FLOOR + 1.6, CZ), new Vec3(1, 0, 0));
                b.tickCount = 0;
                BLASTS.clear();
                double stopY = Double.NaN, lastY = Double.NaN;
                int stopTick = -1, gone = -1;
                for (int i = 0; i < c.expires + 20 && !b.isRemoved(); i++) {
                    b.tickCount++;
                    boolean was = WaterBullet.stopped(b);
                    lastY = b.getY();
                    b.tick();
                    if (!was && WaterBullet.stopped(b)) {
                        stopY = b.getY();
                        stopTick = b.tickCount;
                    }
                    if (b.isRemoved()) gone = b.tickCount;
                }
                double[] blast = BLASTS.isEmpty() ? null : BLASTS.get(0);
                boolean floor = !high || (Boolean) k[1];
                boolean pass = stopTick > 0 && (floor ? blast != null && blast[1] < FLOOR + 1.4
                        : blast == null && gone == c.expires + 1 && lastY > FLOOR + 1.5);
                String note = "life " + c.expires + ", stopped tick " + stopTick + " y " + fmt(stopY) + ", gone tick " + gone + " y " + fmt(lastY)
                        + ", blast " + str(blast);
                rowFlag("i: stopped " + (high ? "high (" + fmt(surface - 1 - FLOOR - 1) + " over the floor)" : "near the floor") + ", NTM's life",
                        ammo, wc, floor ? "goes off on the floor" : "gone at tick " + (c.expires + 1) + ", no blast",
                        blast != null ? "blast at y " + fmt(blast[1]) : gone > 0 ? "gone at tick " + gone + ", no blast" : "still there", pass, note);
                b.discard();
            }
        }
    }

    // HE pellets fired straight down by a shooter standing in water: water stops them within 0.57 of its eyes, inside
    // its box. NTM tests a mob's box from the pellet's path (AABB.clip), and a path that starts inside the box meets
    // none of its faces, so a pellet that water stopped in its shooter does not hit it from inside (MixinThrowableHit).
    // With the feet on the floor the pellets sink and go off on it; with the floor farther than their life takes them
    // (NTM's 30 ticks) they are gone on the way, as any round NTM's life ends.
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
            Vec3 feet = new Vec3(MIN + 3.5, onFloor ? FLOOR + 1 : FLOOR + 4, CZ);
            ArmorStand g = shooter(feet, 90F);
            AABB box = g.getBoundingBox();
            BLASTS.clear();
            List<EntityBulletBaseMK4> shot = shoot(g, c, n, c.spread);
            int inBox = 0, silentEarly = 0, goneLo = Integer.MAX_VALUE, goneHi = -1;
            boolean[] seen = new boolean[n];
            for (int t = 0; t < c.expires + 20; t++) {
                boolean any = false;
                for (int i = 0; i < n; i++) {
                    EntityBulletBaseMK4 b = shot.get(i);
                    if (b.isRemoved()) continue;
                    any = true;
                    int blasts = BLASTS.size();
                    boolean was = WaterBullet.stopped(b);
                    b.tickCount++;
                    b.tick();
                    if (!was && WaterBullet.stopped(b) && box.contains(b.position())) inBox++;
                    if (b.isRemoved() && !seen[i]) {
                        seen[i] = true;
                        goneLo = Math.min(goneLo, b.tickCount);
                        goneHi = Math.max(goneHi, b.tickCount);
                        if (BLASTS.size() == blasts && b.tickCount <= c.expires) silentEarly++;
                    }
                }
                if (!any) break;
            }
            // a blast in the shooter's box over its feet: a pellet that hit its shooter
            int onShooter = 0, onFloorBlasts = 0;
            for (double[] bl : BLASTS) {
                if (bl[1] < FLOOR + 1.4) onFloorBlasts++;
                else if (box.contains(bl[0], bl[1], bl[2])) onShooter++;
            }
            boolean pass = inBox == n && onShooter == 0 && silentEarly == 0
                    && (onFloor ? onFloorBlasts == n : BLASTS.isEmpty() && goneLo == c.expires + 1 && goneHi == c.expires + 1);
            rowFlag("j: shot straight down in water, feet " + (onFloor ? "on the floor" : "3 over the floor"), ammo, wc,
                    n + " stop in the shooter's box, none hits it, " + (onFloor ? n + " blasts on the floor" : "all gone at tick " + (c.expires + 1) + ", no blast"),
                    inBox + " stop in the box, " + onShooter + " on the shooter, " + onFloorBlasts + " on the floor, " + BLASTS.size() + " blasts, gone ticks "
                            + goneLo + ".." + goneHi,
                    pass, "life " + c.expires + ", box y " + fmt(box.minY) + ".." + fmt(box.maxY) + ", silent before the life's end " + silentEarly
                            + ", blasts at y " + heights());
            for (var b : shot) b.discard();
            g.discard();
        }
    }

    // a shooter as narrow as a player (an armour stand, 0.5 wide, its eyes 1.78 over its feet), facing +x; a pellet
    // water stops within 0.57 of the eyes (g10) is out of its box, as out of a player's
    private static final double EYES = 1.7775;

    private static ArmorStand shooter(Vec3 feet, float pitch) {
        ArmorStand g = EntityTypes.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        g.setPos(feet.x, feet.y, feet.z);
        g.setNoGravity(true);
        g.setYRot(-90F);
        g.setXRot(pitch);
        level.addFreshEntity(g);
        return g;
    }

    // the pellets of one shot, made as a gun makes them (EntityBulletBaseMK4 from the shooter, in a loop)
    private static List<EntityBulletBaseMK4> shoot(ArmorStand g, BulletConfig c, int n, float spread) {
        List<EntityBulletBaseMK4> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            EntityBulletBaseMK4 b = new EntityBulletBaseMK4(g, c, DAMAGE, spread, 0, 0, 0);
            level.addFreshEntity(b);
            out.add(b);
        }
        return out;
    }

    // the world's order: each one's tickCount up, then its tick; till all are gone or `ticks` pass (or, `stopped`, till
    // water stopped them all); the ticks it took
    private static int ticks(List<EntityBulletBaseMK4> bullets, int ticks, boolean stopped) {
        for (int i = 0; i < ticks; i++) {
            boolean any = false, moving = false;
            for (var b : bullets) {
                if (b.isRemoved()) continue;
                any = true;
                b.tickCount++;
                b.tick();
                if (!WaterBullet.stopped(b) && !b.isRemoved()) moving = true;
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

    private static String str3(Vec3 v) {
        return "(" + fmt(v.x) + " " + fmt(v.y) + " " + fmt(v.z) + ")";
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "nan" : String.format(Locale.ROOT, "%.4f", v);
    }
}
