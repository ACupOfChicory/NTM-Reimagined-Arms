package acup.gunfeel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.hbm.entity.effect.EntityFireLingering;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.entity.projectile.EntityBulletBaseNT;
import com.hbm.entity.projectile.EntityThrowableNT;
import com.hbm.handler.BulletConfigSyncingUtil;
import com.hbm.handler.BulletConfiguration;
import com.hbm.extprop.HbmLivingProps;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.phys.Vec3;
import acup.gunfeel.water.WaterBlast;
import acup.gunfeel.water.WaterBullet;
import acup.gunfeel.water.WaterClass;
import acup.gunfeel.water.WaterImpact;
import acup.gunfeel.water.WaterPath;
import acup.gunfeel.water.WaterSettings;
import acup.gunfeel.water.WaterTick;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.WorldServer;

// port: 26.2's ExplosiveCheck (a1f618b) on a 1.7.10 dedicated server (runServer -PcheckBoom): the same basin, ammo and
// rows. 1.7.10: the server waits 20 ticks instead of forcing chunks, still water is Blocks.water and its surface a
// source block's 8/9, the bullets are put in the world (NTM's setDead tells their trackers) and, ticked by hand, moved
// to the chunk they are in (the world does it in its own tick; the touch finds other bullets by their chunks), the
// shooter is a zombie (no armour stand; its eyes 1.53 over its feet, 0.6 wide), a golem's in-water state is
// handleWaterMovement, and what a case leaves is taken out of the world at once (removePlayerEntityDangerously).
// The explosives in water, measured on a dedicated server with no client. -Dntmgunfeel.checkBoom=<file.tsv>: in a
// stone basin of still water every NTM projectile with an impact fuze (no nukes) is fired, ticked by hand, and one tsv
// line is written per measurement:
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
            } finally {
                WaterBlast.watch = null;
            }
            try {
                Path f = Paths.get(file);
                if (f.toAbsolutePath().getParent() != null) Files.createDirectories(f.toAbsolutePath().getParent());
                Files.write(f, out, StandardCharsets.UTF_8);
            } catch (Exception ex) {
                GunFeel.LOGGER.warn("Could not write the explosives check", ex);
            }
            for (String l : out) GunFeel.LOGGER.info("[boomcheck] {}", l);
            MinecraftServer.getServer().initiateShutdown();
        }
    }

    // the basin: x, z from 4 to 43, the floor at y 100, water 101..108; a dry stone pad for the air's blasts
    private static final int FLOOR = 100, DEPTH = 8, MIN = 4, SIZE = 40;
    private static final double CX = MIN + SIZE / 2.0, CZ = MIN + SIZE / 2.0;
    private static final double DRY_X = MIN + SIZE + 12;
    private static final float DAMAGE = 20F;
    private static final int TICKS = 200;
    private static WorldServer level;
    private static List<String> lines;
    private static boolean ok;
    private static double surface;
    private static final List<double[]> BLASTS = new ArrayList<double[]>();

    private static boolean run(List<String> out, MinecraftServer server) {
        lines = out;
        ok = true;
        level = server.worldServerForDimension(0);
        lines.add(String.join("\t", "case", "ammo", "class", "ожидание", "замер", "разница", "ок", "прим"));
        basin();
        surface = FLOOR + DEPTH + WaterPath.ownHeight(level.getBlockMetadata((int) CX, FLOOR + DEPTH, (int) CZ));
        if (!WaterSettings.ballistics() || !WaterSettings.fuze()) {
            ok = false;
            lines.add("FAIL\twaterBallistics or waterFuze is off");
        }
        lines.add("#\tsurface\t" + fmt(surface) + "\tfuze skip " + WaterSettings.fuzeSkipAngle() + "\tblast radius " + WaterSettings.blastRadius());
        WaterBlast.watch = e -> BLASTS.add(new double[] {e.posX, e.posY, e.posZ, e.size});
        Map<String, BulletConfig> all = projectiles();
        for (Map.Entry<String, BulletConfig> e : all.entrySet()) {
            String ammo = e.getKey();
            BulletConfig c = e.getValue();
            WaterClass wc = WaterClass.ofConfig(c);
            lines.add("#\t" + ammo + "\t" + wc + "\tfuze " + WaterClass.Fuze.ofConfig(c) + " velocity " + c.velocity + " gravity " + c.gravity
                    + " life " + c.expires + " stop " + fmt(stop(wc)));
            Round r = mk4(c);
            entry(ammo, r, wc, 30);
            entry(ammo, r, wc, 5);
            for (int d : new int[] {1, 3, 6}) wall(ammo, r, wc, d);
            stopped(ammo, r, wc);
            damage(ammo, r, wc);
        }
        oldRounds();
        onSurface(all);
        weak(all);
        fire(all);
        phosphorus();
        r556Incendiary();
        touch(all);
        deadPath(all);
        samePellets(all);
        life(all);
        downward(all);
        clear();
        return ok;
    }

    // NTM's projectiles with an impact fuze, one per ammo (or per field, for those its ammo does not tell); no nukes
    // (they would take the basin and the chunks with them), nor the UFO's rocket and a pile's debris: their blasts are
    // not NTM's ExplosionVNT, which the stand counts
    private static Map<String, BulletConfig> projectiles() {
        Map<String, BulletConfig> out = new LinkedHashMap<String, BulletConfig>();
        // a copy: the first Fuze.field loads the pile's class, whose debris config NTM adds to the list then
        for (BulletConfig c : new ArrayList<BulletConfig>(BulletConfig.configs)) {
            if (c == null || !WaterClass.Fuze.ofConfig(c).any()) continue;
            String field = WaterClass.Fuze.field(c);
            String ammo = field != null ? field : ammoName(c);
            if (ammo.contains("nuke") || ammo.equals("ammo_shell_w9") || ammo.equals("ufo_rocket") || ammo.equals("debris") || ammo.equals("pile_debris")
                    || out.containsKey(ammo)) continue;
            out.put(ammo, c);
        }
        return out;
    }

    private static void basin() {
        int lo = MIN - 1, hi = MIN + SIZE;
        for (int x = lo; x <= hi; x++) for (int z = lo; z <= hi; z++) {
            boolean wall = x == lo || x == hi || z == lo || z == hi;
            for (int y = FLOOR; y <= FLOOR + DEPTH + 4; y++) {
                net.minecraft.block.Block b = y == FLOOR || wall && y <= FLOOR + DEPTH + 1 ? Blocks.stone
                        : y <= FLOOR + DEPTH ? Blocks.water : Blocks.air;
                level.setBlock(x, y, z, b, 0, 2);
            }
        }
        // the dry pad
        for (int x = (int) DRY_X - 6; x <= DRY_X + 6; x++) for (int z = (int) CZ - 6; z <= CZ + 6; z++)
            for (int y = FLOOR; y <= FLOOR + 8; y++) level.setBlock(x, y, z, y == FLOOR ? Blocks.stone : Blocks.air, 0, 2);
        clear();
    }

    // what blasts and bullets left: items, fire, golems, shooters, bullets; out of the world at once (the world ticks
    // none of it while the stand runs, and dead ones would still be found by the blasts and the touch)
    private static void clear() {
        AxisAlignedBB box = AxisAlignedBB.getBoundingBox(MIN - 8, FLOOR - 4, MIN - 8, DRY_X + 10, FLOOR + 24, MIN + SIZE + 8);
        List<Entity> gone = new ArrayList<Entity>();
        for (Class<?> k : new Class<?>[] {EntityItem.class, EntityFireLingering.class, EntityIronGolem.class, EntityZombie.class,
                EntityBulletBaseMK4.class, EntityBulletBaseNT.class})
            for (Object e : level.getEntitiesWithinAABB(k, box)) gone.add((Entity) e);
        for (Entity b : SPAWNED) if (!gone.contains(b)) gone.add(b);
        SPAWNED.clear();
        for (Entity e : gone) level.removePlayerEntityDangerously(e);
    }

    // bullets the stand made: wherever their chunk is, they go with the case
    private static final List<Entity> SPAWNED = new ArrayList<Entity>();

    private static String ammoName(BulletConfig c) {
        String n = WaterClass.ammoName(c);
        return n.isEmpty() ? "id" + c.id : n;
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
        b.setPosition(at.x, at.y, at.z);
        b.setThrowableHeading(dir.x, dir.y, dir.z, 1F, 0F);
        b.damage = DAMAGE;
        // NTM counts the ticks in the world; here it is ticked by hand: past the bomblets' 2 ticks of arming
        b.ticksExisted = 10;
        put(b);
        return b;
    }

    // a round the cases fire: NTM's bullet by its BulletConfig, or (port) an old EntityBulletBaseNT by its config's id
    private interface Round {
        EntityThrowableNT make(Vec3 at, Vec3 dir);

        double gravity();

        WaterClass.Fuze fuze();

        // its blast goes through EntityProcessorStandard (an old bullet's), not CrossSmooth
        boolean standard();
    }

    private static Round mk4(BulletConfig c) {
        return new Round() {
            public EntityThrowableNT make(Vec3 at, Vec3 dir) {
                return bullet(c, at, dir);
            }

            public double gravity() {
                return c.gravity;
            }

            public WaterClass.Fuze fuze() {
                return WaterClass.Fuze.ofConfig(c);
            }

            public boolean standard() {
                return false;
            }
        };
    }

    // as NTM's masked man fires it (EntityBulletBaseNT(world, config), the heading at 1: its speed is the config's
    // velocity, motionMult); its own random seeded, so a high-ricochet roll (HBRC) is the same every run
    private static Round old(int id) {
        BulletConfiguration c = BulletConfigSyncingUtil.pullConfig(id);
        return new Round() {
            public EntityThrowableNT make(Vec3 at, Vec3 dir) {
                EntityBulletBaseNT b = new EntityBulletBaseNT(level, id);
                b.setPosition(at.x, at.y, at.z);
                b.setThrowableHeading(dir.x, dir.y, dir.z, 1F, 0F);
                java.util.Random rand = cpw.mods.fml.relauncher.ReflectionHelper.getPrivateValue(Entity.class, b, "rand", "field_70146_Z");
                rand.setSeed(7);
                put(b);
                return b;
            }

            public double gravity() {
                return c.gravity;
            }

            public WaterClass.Fuze fuze() {
                return WaterClass.Fuze.ofOld(c);
            }

            public boolean standard() {
                return true;
            }
        };
    }

    // port: NTM 1.7.10's old bullets that blow up where they hit, as 26.2 X560 has these rounds (XFactoryNPC): the
    // masked man's orb, bolt, rocket and meteor. Their life (maxAge) is lifted while they run, as the cases wait for
    // them to land; the UFO's rocket is not here (its blast is NTM's dealDamage, not an ExplosionVNT), only its fuze
    private static void oldRounds() {
        String[] names = {"maskman_orb", "maskman_bolt", "maskman_rocket", "maskman_meteor"};
        int[] ids = {BulletConfigSyncingUtil.MASKMAN_ORB, BulletConfigSyncingUtil.MASKMAN_BOLT, BulletConfigSyncingUtil.MASKMAN_ROCKET,
                BulletConfigSyncingUtil.MASKMAN_METEOR};
        for (int i = 0; i < ids.length; i++) {
            BulletConfiguration c = BulletConfigSyncingUtil.pullConfig(ids[i]);
            String ammo = names[i] + " (old)";
            if (c == null) {
                rowFlag("g: old config", ammo, WaterClass.PISTOL, "there", "missing", false, "");
                continue;
            }
            WaterClass wc = WaterClass.ofOld(c);
            lines.add("#	" + ammo + "	" + wc + "	fuze " + WaterClass.Fuze.ofOld(c) + " velocity " + c.velocity + " gravity " + c.gravity + " life "
                    + c.maxAge + " explosive " + c.explosive + " stop " + fmt(stop(wc)));
            int life = c.maxAge;
            c.maxAge = Math.max(life, 1000);
            try {
                Round r = old(ids[i]);
                entry(ammo, r, wc, 30);
                entry(ammo, r, wc, 5);
                for (int d : new int[] {1, 3, 6}) wall(ammo, r, wc, d);
                stopped(ammo, r, wc);
                damage(ammo, r, wc);
                if (c.incendiary > 0) oldFire(ammo, r, wc);
            } finally {
                c.maxAge = life;
            }
        }
        oldHit(BulletConfigSyncingUtil.MASKMAN_BOLT, "maskman_bolt (old)", true);
        oldRicochet();
        oldPlain();
        BulletConfiguration ufo = BulletConfigSyncingUtil.pullConfig(BulletConfigSyncingUtil.UFO_ROCKET),
                tracer = BulletConfigSyncingUtil.pullConfig(BulletConfigSyncingUtil.MASKMAN_TRACER),
                worm = BulletConfigSyncingUtil.pullConfig(BulletConfigSyncingUtil.WORM_BOLT);
        rowFlag("g: old fuzes: the UFO's rocket yes, the tracer and BOT Prime's bolt no", "ufo_rocket, maskman_tracer, worm_bolt (old)", WaterClass.ofOld(ufo),
                "IMPACT NONE NONE", WaterClass.Fuze.ofOld(ufo) + " " + WaterClass.Fuze.ofOld(tracer) + " " + WaterClass.Fuze.ofOld(worm),
                WaterClass.Fuze.ofOld(ufo) == WaterClass.Fuze.IMPACT && WaterClass.Fuze.ofOld(tracer) == WaterClass.Fuze.NONE
                        && WaterClass.Fuze.ofOld(worm) == WaterClass.Fuze.NONE, "");
        BulletConfiguration test = BulletConfigSyncingUtil.pullConfig(BulletConfigSyncingUtil.TEST_CONFIG),
                turbine = BulletConfigSyncingUtil.pullConfig(BulletConfigSyncingUtil.TURBINE);
        rowFlag("g: old rounds in water: the UFO's rocket and the tracer yes, the test and turbine configs no", "ufo_rocket, maskman_tracer, test, turbine (old)",
                WaterClass.ofOld(ufo), "true true false false", WaterClass.Fuze.oldInWater(ufo) + " " + WaterClass.Fuze.oldInWater(tracer) + " "
                        + WaterClass.Fuze.oldInWater(test) + " " + WaterClass.Fuze.oldInWater(turbine),
                WaterClass.Fuze.oldInWater(ufo) && WaterClass.Fuze.oldInWater(tracer) && !WaterClass.Fuze.oldInWater(test) && !WaterClass.Fuze.oldInWater(turbine), "");
    }

    // the old rounds with no fuze that 26.2 fires as its own bullets (the masked man's tracer, BOT Prime's bolts):
    // in water they slow and are gone where water stops them, as NTM's plain bullet (no blast, no sinking, no meteor
    // called down by the tracer); into water from the air no blast on the surface; a creature's hit × f²
    private static void oldPlain() {
        String[] names = {"maskman_tracer", "worm_bolt", "worm_laser"};
        int[] ids = {BulletConfigSyncingUtil.MASKMAN_TRACER, BulletConfigSyncingUtil.WORM_BOLT, BulletConfigSyncingUtil.WORM_LASER};
        for (int i = 0; i < ids.length; i++) {
            BulletConfiguration c = BulletConfigSyncingUtil.pullConfig(ids[i]);
            String ammo = names[i] + " (old)";
            if (c == null) {
                rowFlag("p: old config", ammo, WaterClass.PISTOL, "there", "missing", false, "");
                continue;
            }
            WaterClass wc = WaterClass.ofOld(c);
            lines.add("#	" + ammo + "	" + wc + "	fuze " + WaterClass.Fuze.ofOld(c) + " in water " + WaterClass.Fuze.oldInWater(c) + " velocity " + c.velocity
                    + " gravity " + c.gravity + " life " + c.maxAge + " penetrates " + c.doesPenetrate + " stop " + fmt(stop(wc)));
            rowFlag("p: plain old round: in water, no fuze, a pistol's class (v " + fmt(c.velocity) + ")", ammo, wc, "true NONE PISTOL",
                    WaterClass.Fuze.oldInWater(c) + " " + WaterClass.Fuze.ofOld(c) + " " + wc,
                    WaterClass.Fuze.oldInWater(c) && WaterClass.Fuze.ofOld(c) == WaterClass.Fuze.NONE && wc == WaterClass.PISTOL, "");
            Round r = old(ids[i]);
            // under water along x, nothing in its way: gone where it stops, about its stop distance on
            basin();
            double x0 = MIN + 3.5;
            EntityThrowableNT b = r.make(new Vec3(x0, surface - 3, CZ), new Vec3(1, 0, 0));
            BLASTS.clear();
            int ticks = 0;
            double lastX = x0;
            for (; ticks < TICKS && !b.isDead; ticks++) {
                tick(b);
                if (!b.isDead) lastX = b.posX;
            }
            double s = stop(wc), went = Math.max(lastX, b.posX) - x0;
            rowFlag("p: under water: gone where it stops, no blast, no sinking", ammo, wc, "gone, 0 blasts, ≈" + fmt(s),
                    "gone=" + b.isDead + " blasts " + BLASTS.size() + " went " + fmt(went) + " ticks " + ticks,
                    b.isDead && BLASTS.isEmpty() && Math.abs(went - s) <= Math.max(0.35, 0.15 * s) && ticks < c.maxAge,
                    "y " + fmt(b.posY) + " stopped=" + WaterBullet.stopped(b));
            // into water at 30° from the air: no blast on the surface, in, gone short of the floor
            basin();
            double a = Math.toRadians(30);
            Vec3 dir = new Vec3(Math.cos(a), -Math.sin(a), 0), aim = new Vec3(MIN + 14, surface, CZ);
            b = r.make(new Vec3(aim.x - dir.x * 4, aim.y - dir.y * 4, aim.z - dir.z * 4), dir);
            BLASTS.clear();
            boolean in = false;
            for (int t = 0; t < TICKS && !b.isDead; t++) {
                tick(b);
                if (!b.isDead && b.posY < surface - 0.05) in = true;
            }
            rowFlag("p: into water at 30°: in, no blast on the surface, gone above the floor", ammo, wc, "in, 0 blasts, gone, y > " + (FLOOR + 1),
                    "in=" + in + " blasts " + BLASTS.size() + " gone=" + b.isDead + " y " + fmt(b.posY),
                    in && BLASTS.isEmpty() && b.isDead && b.posY > FLOOR + 1.05, "");
        }
        oldHit(BulletConfigSyncingUtil.WORM_BOLT, "worm_bolt (old)", false);
    }

    // NTM's random ricochet (the bolt's HBRC, 2 in 100; the rocket, meteor and orb have none) made sure, HBRC 100: off
    // the water's surface it does not bounce, nor sinking off the floor (stopped): it goes off there
    // (MixinWaterOldBullet); in the air off a block it does (NTM's)
    private static void oldRicochet() {
        int id = BulletConfigSyncingUtil.MASKMAN_BOLT;
        BulletConfiguration c = BulletConfigSyncingUtil.pullConfig(id);
        if (c == null) return;
        String ammo = "maskman_bolt (old, HBRC 100)";
        WaterClass wc = WaterClass.ofOld(c);
        int life = c.maxAge, hbrc = c.HBRC;
        c.maxAge = Math.max(life, 1000);
        c.HBRC = 100;
        try {
            entry(ammo, old(id), wc, 30);
            stopped(ammo, old(id), wc);
            basin();
            Vec3 at = new Vec3(DRY_X, FLOOR + 1, CZ);
            EntityThrowableNT b = old(id).make(new Vec3(at.x, at.y + 1, at.z), new Vec3(0, -1, 0));
            BLASTS.clear();
            ((WaterImpact.Hits) b).gunfeel$impact(hitAt(at));
            rowFlag("a: NTM's ricochet off a block in the air (HBRC 100)", ammo, wc, "bounce, no blast", "blasts " + BLASTS.size() + ", motionY " + fmt(b.motionY) + ", gone=" + b.isDead,
                    BLASTS.isEmpty() && !b.isDead, "doesRicochet " + c.doesRicochet);
        } finally {
            c.maxAge = life;
            c.HBRC = hbrc;
        }
    }

    // the masked man's bolt on a golem's body, in the air and under water: in water NTM's rolled damage × f² where it
    // met the golem (MixinOldBulletBase, WaterTick.fSquared), f as the water's path works it out there. Fired 0.4 short
    // of the golem's box, it goes through (doesPenetrate: no blast on a creature, only its hit) and water stops it past
    // the golem; its roll is the same both times (its own random, seeded). Fired 2.5 short, water stops it inside the
    // golem: sinking, it goes off there once (MixinWaterOldBullet), not hurt the golem every tick
    private static void oldHit(int id, String ammo, boolean sinks) {
        BulletConfiguration c = BulletConfigSyncingUtil.pullConfig(id);
        if (c == null) return;
        WaterClass wc = WaterClass.ofOld(c);
        double[] took = new double[3];
        double f = Double.NaN;
        int blasts = -1;
        boolean gone = false;
        for (int run = 0; run < (sinks ? 3 : 2); run++) {
            boolean wet = run > 0;
            basin();
            Vec3 feet = wet ? new Vec3(CX, surface - 4, CZ) : new Vec3(DRY_X, FLOOR + 1, CZ);
            EntityIronGolem g = golem(feet);
            float before = g.getHealth();
            double shortOf = run == 2 ? 2.5 : 0.4;
            Vec3 from = new Vec3(g.boundingBox.minX - shortOf, feet.y + 1.2, feet.z);
            EntityThrowableNT b = old(id).make(from, new Vec3(1, 0, 0));
            BLASTS.clear();
            for (int i = 0; i < 200 && !b.isDead; i++) tick(b);
            took[run] = before - g.getHealth();
            if (run == 1) {
                // NTM meets a creature's box 0.3 wider
                Vec3 hit = new Vec3(g.boundingBox.minX - 0.3, from.y, from.z);
                WaterPath.Result r = WaterPath.trace(level, from, hit, 1.0, wc, 0, WaterTick.seed(b.getEntityId(), 0), WaterPath.Settings.current().fuzed(), b);
                f = r.fAt(hit);
            }
            if (run == 2) {
                blasts = BLASTS.size();
                gone = b.isDead;
            }
            level.removePlayerEntityDangerously(g);
        }
        row("h: old bolt through a golem in water, × f² of the air's", ammo, wc, took[0] * f * f, took[1], 0.02,
                "air=" + fmt(took[0]) + " water=" + fmt(took[1]) + " f at the golem=" + fmt(f));
        rowFlag("h: old bolt on a golem in the air (NTM's roll)", ammo, wc, fmt(c.dmgMin) + ".." + fmt(c.dmgMax), fmt(took[0]),
                took[0] >= c.dmgMin - 1e-3 && took[0] <= c.dmgMax + 1e-3, "");
        if (sinks) rowFlag("h: old bolt stopped inside a golem: goes off once", ammo, wc, "1 blast, gone, less than the air's", blasts + " blasts, gone=" + gone
                + ", took " + fmt(took[2]), blasts == 1 && gone && took[2] > 0 && took[2] < took[0], "");
    }

    // the meteor's fire (incendiary 3: fire round the block it hits and NTM's fire in its blast): none in water, NTM's
    // in the air
    private static void oldFire(String ammo, Round r, WaterClass wc) {
        int[] fires = new int[2], blasts = new int[2];
        for (int wet = 0; wet < 2; wet++) {
            basin();
            Vec3 at = wet == 1 ? new Vec3(CX, FLOOR + 1, CZ) : new Vec3(DRY_X, FLOOR + 1, CZ);
            EntityThrowableNT b = r.make(new Vec3(at.x, at.y + 1, at.z), new Vec3(0, -1, 0));
            BLASTS.clear();
            ((WaterImpact.Hits) b).gunfeel$impact(hitAt(at));
            blasts[wet] = BLASTS.size();
            int n = 0;
            for (int x = (int) at.x - 8; x <= at.x + 8; x++) for (int y = FLOOR; y <= FLOOR + DEPTH + 4; y++) for (int z = (int) at.z - 8; z <= at.z + 8; z++)
                if (level.getBlock(x, y, z) == Blocks.fire) n++;
            fires[wet] = n;
        }
        rowFlag("f: fire in water: blast, no fire", ammo, wc, "blast 1, fire 0", "blast " + blasts[1] + ", fire " + fires[1], blasts[1] == 1 && fires[1] == 0,
                "in the air: blast " + blasts[0] + ", fire " + fires[0]);
        rowFlag("f: fire in the air (NTM's)", ammo, wc, "fire ≥ 1", "fire " + fires[0], fires[0] >= 1, "");
    }

    private static void put(Entity b) {
        level.spawnEntityInWorld(b); // NTM's setDead tells the bullet's trackers
        SPAWNED.add(b);
    }

    // one tick by hand, then into the chunk it is in now, as the world's tick does (World.updateEntityWithOptionalForce)
    private static void tick(Entity b) {
        b.onUpdate();
        rechunk(b);
    }

    private static void rechunk(Entity e) {
        if (e.isDead) return;
        int cx = MathHelper.floor_double(e.posX / 16.0), cy = MathHelper.floor_double(e.posY / 16.0), cz = MathHelper.floor_double(e.posZ / 16.0);
        if (e.addedToChunk && e.chunkCoordX == cx && e.chunkCoordY == cy && e.chunkCoordZ == cz) return;
        if (e.addedToChunk && level.getChunkProvider().chunkExists(e.chunkCoordX, e.chunkCoordZ))
            level.getChunkFromChunkCoords(e.chunkCoordX, e.chunkCoordZ).removeEntityAtIndex(e, e.chunkCoordY);
        if (level.getChunkProvider().chunkExists(cx, cz)) {
            e.addedToChunk = true;
            level.getChunkFromChunkCoords(cx, cz).addEntity(e);
        } else {
            e.addedToChunk = false;
        }
    }

    private static Vec3 pos(Entity e) {
        return new Vec3(e.posX, e.posY, e.posZ);
    }

    // a block hit at a point, from above
    private static MovingObjectPosition hitAt(Vec3 at) {
        return new MovingObjectPosition(MathHelper.floor_double(at.x), MathHelper.floor_double(at.y - 0.5), MathHelper.floor_double(at.z), 1,
                net.minecraft.util.Vec3.createVectorHelper(at.x, at.y, at.z));
    }

    // the stop distance in still water (density 1)
    private static double stop(WaterClass wc) {
        return WaterPath.stopDepth(wc, null, WaterPath.Settings.current());
    }

    // a tick that ended right on the surface (a rocket at 30°: 0 + 0.4 + 0.8 + 1.2 + 1.6 = 4 blocks in 5 ticks): the
    // next one starts on it and comes in from the air, so it goes off there, not sinks to the floor
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
            Vec3 on = new Vec3(MIN + 14, surface, CZ);
            EntityBulletBaseMK4 b = bullet(c, on, new Vec3(Math.cos(r), -Math.sin(r), 0));
            double[] blast = fly(b, TICKS);
            rowFlag("a: from right on the surface at 30°: goes off there", ammo, WaterClass.ofConfig(c), "blast y " + fmt(surface) + "..+0.3",
                    blast == null ? "none" : fmt(blast[1]), blast != null && blast[1] >= surface - 0.05 && blast[1] <= surface + 0.35,
                    "blast=" + str(blast) + " stopped=" + WaterBullet.stopped(b));
        }
    }

    // ticks it till it is gone; the first blast, or null
    private static double[] fly(Entity b, int ticks) {
        BLASTS.clear();
        for (int i = 0; i < ticks && !b.isDead; i++) tick(b);
        return BLASTS.isEmpty() ? null : BLASTS.get(0);
    }

    // into the water from the air at an angle: steeper than the fuze's 10° it goes off on the surface, shallower it
    // skips and goes off at its next touch. The angle is the one it meets the water at (a grenade's falls steeper).
    private static void entry(String ammo, Round c, WaterClass wc, int deg) {
        basin();
        double r = Math.toRadians(deg), run = deg >= 20 ? 4 : 10;
        Vec3 dir = new Vec3(Math.cos(r), -Math.sin(r), 0);
        Vec3 aim = new Vec3(MIN + 14, surface, CZ);
        // after a skip its next touch: a wall further on
        if (deg < 20) for (int y = FLOOR + 1; y <= FLOOR + DEPTH + 8; y++) for (int z = (int) CZ - 4; z <= CZ + 4; z++)
            level.setBlock(MIN + 30, y, z, Blocks.stone, 0, 2);
        EntityThrowableNT b = c.make(new Vec3(aim.x - dir.x * run, aim.y - dir.y * run, aim.z - dir.z * run), dir);
        BLASTS.clear();
        double angle = Double.NaN, firstTouchX = Double.NaN;
        int skips = 0;
        for (int i = 0; i < TICKS && !b.isDead; i++) {
            double a = Math.toDegrees(Math.atan2(-b.motionY, Math.hypot(b.motionX, b.motionZ)));
            int before = ((WaterBullet) b).gunfeel$waterSkips();
            double atY = b.posY;
            tick(b);
            int now = ((WaterBullet) b).gunfeel$waterSkips();
            if (now > before && skips == 0) {
                firstTouchX = b.posX;
                angle = a;
            }
            skips = now;
            if (!BLASTS.isEmpty() && Double.isNaN(angle)) angle = a;
            if (!BLASTS.isEmpty() || b.isDead) break;
            if (b.posY < FLOOR - 2 || b.posX > MIN + SIZE + 20) break;
            if (Double.isNaN(angle) && atY > surface && b.posY < surface - 0.01) angle = a;
        }
        double[] blast = BLASTS.isEmpty() ? null : BLASTS.get(0);
        String note = "skips=" + skips + " angle=" + fmt(angle) + " blast=" + str(blast) + " removed=" + b.isDead;
        double fuze = WaterSettings.fuzeSkipAngle();
        if (skips == 0) {
            // on the surface where it came in (at any angle what does not skip: the Daybreaker's flame)
            boolean good = blast != null && blast[1] >= surface - 0.05 && blast[1] <= surface + 0.35 && (!(angle < fuze - 0.5) || !wc.slows());
            rowFlag("a: into water at " + deg + "°: goes off on the surface", ammo, wc, "blast y " + fmt(surface) + "..+0.3",
                    blast == null ? "none" : fmt(blast[1]), good, note);
        } else {
            // skipped where it met the water (shallower than the fuze), went off later, not there
            // b75_inc: its next touch is in the air, where it is NTM's (no blast)
            boolean weak = c.fuze() == WaterClass.Fuze.WEAK;
            boolean good = angle < fuze + 0.5 && (weak ? blast == null && b.isDead : blast != null && Math.abs(blast[0] - firstTouchX) > 0.5);
            rowFlag("a: into water at " + deg + "°: skips, goes off later", ammo, wc, "skip, blast past x " + fmt(firstTouchX),
                    blast == null ? "none" : "x " + fmt(blast[0]) + " y " + fmt(blast[1]), good, note);
        }
    }

    // from 3 under the water along x at a stone wall d blocks off: on the wall if it gets there, else it stops,
    // sinks and goes off on the floor under where it stopped
    private static void wall(String ammo, Round c, WaterClass wc, int d) {
        basin();
        double x0 = MIN + 3.5;
        int wx = (int) Math.floor(x0 + d);
        for (int y = FLOOR + 1; y <= FLOOR + DEPTH; y++) for (int z = (int) CZ - 3; z <= CZ + 3; z++) level.setBlock(wx, y, z, Blocks.stone, 0, 2);
        EntityThrowableNT b = c.make(new Vec3(x0, surface - 3, CZ), new Vec3(1, 0, 0));
        BLASTS.clear();
        double stopX = Double.NaN;
        for (int i = 0; i < TICKS && !b.isDead; i++) {
            boolean was = WaterBullet.stopped(b);
            tick(b);
            if (!was && WaterBullet.stopped(b)) stopX = b.posX;
        }
        double[] blast = BLASTS.isEmpty() ? null : BLASTS.get(0);
        double s = stop(wc), face = wx;
        String note = "blast=" + str(blast) + " stopped=" + WaterBullet.stopped(b) + " removed=" + b.isDead + " stop=" + fmt(s) + " stopX=" + fmt(stopX);
        // straight on it reaches the wall within its stop; one NTM's gravity bends down (0.1 a tick: the masked man's
        // meteor and rocket) stops by the same path in water nearer, short of it
        boolean reaches = c.gravity() >= 0.05 ? Double.isNaN(stopX) : x0 + s > face;
        if (reaches) {
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
    private static void stopped(String ammo, Round c, WaterClass wc) {
        basin();
        double x0 = MIN + 3.5;
        EntityThrowableNT b = c.make(new Vec3(x0, surface - 5, CZ), new Vec3(1, 0, 0));
        BLASTS.clear();
        double fastest = 0, stopX = Double.NaN, path = 0;
        int sinkTicks = 0, ticks = 0;
        // port: the S12s the server marks for it (WaterTick.announce); the world's time goes on with the hand's ticks,
        // as its resend goes by it
        WaterTick.sentCount = new HashMap<Integer, Integer>();
        for (int i = 0; i < 400 && !b.isDead && BLASTS.isEmpty(); i++) {
            double wasX = b.posX, wasY = b.posY, wasZ = b.posZ;
            boolean wasStopped = WaterBullet.stopped(b);
            level.getWorldInfo().incrementTotalWorldTime(level.getTotalWorldTime() + 1);
            tick(b);
            ticks++;
            // the path till water stopped it (bent by NTM's gravity: longer than how far along x)
            if (!wasStopped) path += new Vec3(wasX, wasY, wasZ).distanceTo(pos(b));
            if (wasStopped && BLASTS.isEmpty() && !b.isDead) {
                fastest = Math.max(fastest, wasY - b.posY);
                sinkTicks++;
                if (Math.abs(b.posX - wasX) > 1e-9) fastest = Double.POSITIVE_INFINITY;
            }
            if (!wasStopped && WaterBullet.stopped(b)) stopX = b.posX;
        }
        double[] blast = BLASTS.isEmpty() ? null : BLASTS.get(0);
        String note = "stopX-x0=" + fmt(stopX - x0) + " path=" + fmt(path) + " sinkTicks=" + sinkTicks + " blast=" + str(blast);
        row("c: stopped, path in water", ammo, wc, stop(wc), Double.isNaN(stopX) ? Double.NaN : path, 0.15, note);
        rowFlag("c: stopped, sinks ≤ 0.05/tick", ammo, wc, "≤ 0.0500", fmt(fastest), !Double.isNaN(stopX) && fastest <= 0.05 + 1e-9 && sinkTicks > 0, note);
        rowFlag("c: stopped, goes off on the floor", ammo, wc, "y " + (FLOOR + 1), blast == null ? "none" : fmt(blast[1]),
                blast != null && blast[1] < FLOOR + 1.4, note);
        // the motion sent to the players: as it starts to fly in water, as it sinks, and once each RESEND ticks after.
        // port: NTM's bullets only; the client draws an old one by its position (NTM's own renderer)
        Integer n = WaterTick.sentCount.get(b.getEntityId());
        WaterTick.sentCount = null;
        if (!(b instanceof EntityBulletBaseMK4)) return;
        int sent = n == null ? 0 : n;
        rowFlag("c: stopped, S12 sent", ammo, wc, "2.." + (2 + sinkTicks / 20) + " in " + ticks + " ticks", sent + String.format(java.util.Locale.ROOT,
                " (%.3f a tick)", ticks == 0 ? 0.0 : sent / (double) ticks), sent >= 2 && sent <= 2 + sinkTicks / 20 + 1 && sent <= ticks, note);
    }

    // a golem standing there, still, its in-water state as the world's tick sets it
    private static EntityIronGolem golem(Vec3 feet) {
        EntityIronGolem g = new EntityIronGolem(level);
        g.setPosition(feet.x, feet.y, feet.z);
        level.spawnEntityInWorld(g);
        g.handleWaterMovement();
        g.motionX = g.motionY = g.motionZ = 0;
        g.setPosition(feet.x, feet.y, feet.z);
        return g;
    }

    // the health a golem 2 blocks off (box to centre) takes from the blast set off at `at`
    private static double golemTook(Round c, Vec3 at, Vec3 golemFeet, double[] blastOut) {
        clear();
        EntityIronGolem g = golem(golemFeet);
        float before = g.getHealth();
        EntityThrowableNT b = c.make(at, new Vec3(0, -1, 0));
        BLASTS.clear();
        ((WaterImpact.Hits) b).gunfeel$impact(hitAt(at));
        double took = before - g.getHealth();
        // the golem's feet to the centre (EntityProcessorStandard counts its distance so)
        if (!BLASTS.isEmpty()) blastOut[6] = g.getDistance(BLASTS.get(0)[0], BLASTS.get(0)[1], BLASTS.get(0)[2]);
        if (!BLASTS.isEmpty()) {
            double[] k = BLASTS.get(0);
            System.arraycopy(k, 0, blastOut, 0, 4);
            // the box's distance to the centre, as NTM counts it
            AxisAlignedBB box = g.boundingBox;
            double dx = Math.max(0, Math.max(box.minX - k[0], k[0] - box.maxX)), dy = Math.max(0, Math.max(box.minY - k[1], k[1] - box.maxY)),
                    dz = Math.max(0, Math.max(box.minZ - k[2], k[2] - box.maxZ));
            blastOut[4] = Math.sqrt(dx * dx + dy * dy + dz * dz);
            blastOut[5] = WaterBlast.inWater(g) ? 1 : 0;
        }
        level.removePlayerEntityDangerously(g);
        return took;
    }

    // a golem 2 blocks off: in the air, in the water by a blast in it, and over the water by a blast just under the
    // surface. CrossSmooth: fixed × (1 - d / range), range 2 × size, in water × waterBlastRadius; over it half.
    private static void damage(String ammo, Round c, WaterClass wc) {
        if (c.fuze() == WaterClass.Fuze.WEAK) return; // no blast in the air: weak()
        basin();
        double half = 0.7; // the golem's half width
        double[] air = new double[7], wet = new double[7], over = new double[7];
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
        if (c.standard()) {
            standardDamage(ammo, wc, size, mult, tAir, tWet, tOver, air[6], wet[6], over[6], note);
            return;
        }
        if (size > 0 && 2 * size <= air[4]) {
            // a blast too small to reach the golem 2 blocks off (the masked man's bolt): CrossSmooth's range is 2 × size
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

    // port: an old bullet's blast (EntityProcessorStandard): damage (int) ((k² + k) / 2 × 8 × range + 1), k = 1 - d / range,
    // range 2 × size, d from the golem's feet. In water the range × waterBlastRadius and the damage by the blast's own
    // range; over it by its own range, half (WaterBlast.damageStandard)
    private static void standardDamage(String ammo, WaterClass wc, double size, float mult, double tAir, double tWet, double tOver, double dAir,
            double dWet, double dOver, String note) {
        double range = 2 * size;
        note += " | feet d air=" + fmt(dAir) + " wet=" + fmt(dWet) + " over=" + fmt(dOver);
        if (size <= 0 || dAir >= range) {
            lines.add("#	" + ammo + "	d: the blast does not reach 2 blocks	" + note);
            return;
        }
        row("d: golem in the air (NTM's)", ammo, wc, standard(1 - dAir / range, range), tAir, 0.01, note);
        row("d: golem in water", ammo, wc, dWet < range * mult ? standard(1 - dWet / (range * mult), range) : 0, tWet, 0.01, note);
        row("d: golem over water", ammo, wc, dOver < range ? WaterBlast.ABOVE * standard(1 - dOver / range, range) : 0, tOver, 0.01, note);
    }

    private static double standard(double k, double range) {
        return (int) ((k * k + k) / 2.0 * 8.0 * range + 1.0);
    }

    // b75_inc under water: a small blast, about a quarter of b75_exp's there
    private static void weak(Map<String, BulletConfig> all) {
        BulletConfig inc = byAmmo(all, "ammo_standard_b75_inc"), exp = byAmmo(all, "ammo_standard_b75_exp");
        if (inc == null || exp == null) {
            rowFlag("e: b75_inc", "ammo_standard_b75_inc", WaterClass.HEAVY, "configs", "missing", false, "");
            return;
        }
        double half = 0.7;
        double[] a = new double[7], b = new double[7];
        basin();
        double tInc = golemTook(mk4(inc), new Vec3(CX, surface - 5, CZ), new Vec3(CX + 2 + half, surface - 5 - 1.35, CZ), a);
        basin();
        double tExp = golemTook(mk4(exp), new Vec3(CX, surface - 5, CZ), new Vec3(CX + 2 + half, surface - 5 - 1.35, CZ), b);
        float mult = Math.max(1F, WaterSettings.blastRadius());
        // CrossSmooth both: damage × 0.25, the range 2.5 against 5
        double want = 0.25 * (1 - a[4] / (2 * 2.5 * mult)) / (1 - b[4] / (2 * 5 * mult));
        row("e: b75_inc under water, × b75_exp's", "ammo_standard_b75_inc", WaterClass.ofConfig(inc), want, tExp > 0 ? tInc / tExp : Double.NaN, 0.05,
                "inc=" + fmt(tInc) + " size=" + fmt(a[3]) + " exp=" + fmt(tExp) + " size=" + fmt(b[3]));
        // and in the air it is NTM's: no blast
        basin();
        EntityBulletBaseMK4 k = bullet(inc, new Vec3(DRY_X, FLOOR + 3, CZ), new Vec3(0, -1, 0));
        BLASTS.clear();
        ((WaterImpact.Hits) k).gunfeel$impact(hitAt(new Vec3(DRY_X, FLOOR + 1, CZ)));
        rowFlag("e: b75_inc in the air: no blast", "ammo_standard_b75_inc", WaterClass.ofConfig(inc), "0 blasts", BLASTS.size() + " blasts", BLASTS.isEmpty(), "");
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
                EntityBulletBaseMK4 b = bullet(c, new Vec3(at.x, at.y + 1, at.z), new Vec3(0, -1, 0));
                BLASTS.clear();
                ((WaterImpact.Hits) b).gunfeel$impact(hitAt(at));
                blasts[wet] = BLASTS.size();
                int n = 0;
                for (Object e : level.getEntitiesWithinAABB(EntityFireLingering.class,
                        AxisAlignedBB.getBoundingBox(at.x - 8, at.y - 8, at.z - 8, at.x + 8, at.y + 8, at.z + 8)))
                    if (!((Entity) e).isDead) n++;
                fires[wet] = n;
            }
            rowFlag("f: fire in water: blast, no fire", name, WaterClass.ofConfig(c), "blast 1, fire 0", "blast " + blasts[1] + ", fire " + fires[1],
                    blasts[1] == 1 && fires[1] == 0, "in the air: blast " + blasts[0] + ", fire " + fires[0]);
            rowFlag("f: fire in the air (NTM's)", name, WaterClass.ofConfig(c), "fire ≥ 1", "fire " + fires[0], fires[0] >= 1, "");
            // NTM's guard in the first 3 ticks: on a golem (not its shooter) in water the grenade goes off, the rocket
            // spares it
            basin();
            EntityIronGolem g = golem(new Vec3(CX, surface - 4, CZ));
            EntityBulletBaseMK4 b = bullet(c, new Vec3(CX - 1.5, surface - 3, CZ), new Vec3(1, 0, 0));
            b.ticksExisted = 1;
            BLASTS.clear();
            ((WaterImpact.Hits) b).gunfeel$impact(new MovingObjectPosition(g));
            boolean rocket = name.startsWith("ammo_standard_rocket_");
            rowFlag("f: fire in water on a mob at tick 1 (NTM's guard)", name, WaterClass.ofConfig(c), rocket ? "0 blasts" : "1 blast",
                    BLASTS.size() + " blasts", BLASTS.size() == (rocket ? 0 : 1), "");
            level.removePlayerEntityDangerously(g);
        }
        // the Daybreaker's flame: NTM's blast, then its fire (XFactoryFlamer.spawnFire) only out of the water
        for (String name : new String[] {"flame_daybreaker_diesel", "flame_daybreaker_napalm", "flame_daybreaker_balefire"}) {
            BulletConfig c = null;
            for (BulletConfig k : new ArrayList<BulletConfig>(BulletConfig.configs)) if (c == null && k != null && name.equals(WaterClass.Fuze.field(k))) c = k;
            if (c == null) {
                ok = false;
                lines.add("FAIL\tno config " + name);
                continue;
            }
            int[] fires = new int[2], blasts = new int[2];
            for (int wet = 0; wet < 2; wet++) {
                basin();
                Vec3 at = wet == 1 ? new Vec3(CX, surface - 3, CZ) : new Vec3(DRY_X, FLOOR + 1, CZ);
                EntityBulletBaseMK4 b = bullet(c, new Vec3(at.x, at.y + 1, at.z), new Vec3(0, -1, 0));
                BLASTS.clear();
                ((WaterImpact.Hits) b).gunfeel$impact(hitAt(at));
                blasts[wet] = BLASTS.size();
                int n = 0;
                for (Object e : level.getEntitiesWithinAABB(EntityFireLingering.class,
                        AxisAlignedBB.getBoundingBox(at.x - 8, at.y - 8, at.z - 8, at.x + 8, at.y + 8, at.z + 8)))
                    if (!((Entity) e).isDead) n++;
                fires[wet] = n;
            }
            rowFlag("f: fire in water: blast, no fire", name, WaterClass.ofConfig(c), "blast 1, fire 0", "blast " + blasts[1] + ", fire " + fires[1],
                    blasts[1] == 1 && fires[1] == 0, "in the air: blast " + blasts[0] + ", fire " + fires[0]);
            rowFlag("f: fire in the air (NTM's)", name, WaterClass.ofConfig(c), "fire ≥ 1", "fire " + fires[0], fires[0] >= 1, "");
        }
    }

    // a phosphorus pellet on a golem in water sets it not alight; in the air NTM's 300 ticks. In water it hits as a
    // plain pellet: the damage × f² where the golem is, as a buckshot pellet's (g12) the same way
    private static void phosphorus() {
        BulletConfig c = null, plain = null;
        // the shotgun's own (the Shredder's shares the ammo)
        for (BulletConfig k : BulletConfig.configs) {
            if (c == null && k != null && ammoName(k).equals("ammo_standard_g12_phosphorus")) c = k;
            if (plain == null && k != null && ammoName(k).equals("ammo_standard_g12")) plain = k;
        }
        if (c == null || plain == null) {
            lines.add("#\tno config ammo_standard_g12_phosphorus or ammo_standard_g12");
            return;
        }
        int[] got = new int[2];
        double[] took = new double[3];
        for (int run = 0; run < 3; run++) {
            boolean wet = run != 0;
            basin();
            Vec3 feet = wet ? new Vec3(CX, surface - 4, CZ) : new Vec3(DRY_X, FLOOR + 1, CZ);
            EntityIronGolem g = golem(feet);
            HbmLivingProps.getData(g).phosphorus = 0;
            float before = g.getHealth();
            EntityBulletBaseMK4 b = bullet(run == 2 ? plain : c, new Vec3(feet.x - 1.2, feet.y + 1.2, feet.z), new Vec3(1, 0, 0));
            fly(b, 10);
            took[run] = before - g.getHealth();
            if (run < 2) got[run] = HbmLivingProps.getData(g).phosphorus;
            level.removePlayerEntityDangerously(g);
        }
        rowFlag("g: g12_phosphorus on a golem in water: not alight", "ammo_standard_g12_phosphorus", WaterClass.ofConfig(c), "phosphorus 0",
                "phosphorus " + got[1], got[1] == 0, "in the air: " + got[0]);
        rowFlag("g: g12_phosphorus in the air (NTM's)", "ammo_standard_g12_phosphorus", WaterClass.ofConfig(c), "phosphorus 300", "phosphorus " + got[0],
                got[0] >= 300, "");
        row("g: g12_phosphorus on a golem in water, damage × g12's", "ammo_standard_g12_phosphorus", WaterClass.ofConfig(c), 1.0,
                took[2] > 0 ? took[1] / took[2] : Double.NaN, 0.01,
                "phosphorus " + fmt(took[1]) + ", g12 " + fmt(took[2]) + ", in the air " + fmt(took[0]) + " (× f² "
                        + fmt(took[0] > 0 ? took[1] / took[0] : Double.NaN) + ")");
    }

    // the 5.56 mm incendiary rounds: clones of the plain ones with the plain ammo and NTM's INCENDIARY lambda (300
    // ticks of phosphorus); in water not alight
    private static void r556Incendiary() {
        BulletConfig c = null;
        for (BulletConfig k : BulletConfig.configs)
            if (c == null && k != null && k.onImpact != null && ammoName(k).equals("ammo_standard_r556_fmj") && WaterClass.incendiaryConfig(k)) c = k;
        if (c == null) {
            ok = false;
            lines.add("FAIL\tno incendiary config of ammo_standard_r556_fmj");
            return;
        }
        int[] got = new int[2];
        for (int wet = 0; wet < 2; wet++) {
            basin();
            Vec3 feet = wet == 1 ? new Vec3(CX, surface - 4, CZ) : new Vec3(DRY_X, FLOOR + 1, CZ);
            EntityIronGolem g = golem(feet);
            HbmLivingProps.getData(g).phosphorus = 0;
            EntityBulletBaseMK4 b = bullet(c, new Vec3(feet.x - 1.2, feet.y + 1.2, feet.z), new Vec3(1, 0, 0));
            fly(b, 10);
            got[wet] = HbmLivingProps.getData(g).phosphorus;
            level.removePlayerEntityDangerously(g);
        }
        rowFlag("g: r556_inc on a golem in water: not alight", "r556_inc_fmj", WaterClass.ofConfig(c), "phosphorus 0", "phosphorus " + got[1],
                got[1] == 0, "in the air: " + got[0]);
        rowFlag("g: r556_inc in the air (NTM's)", "r556_inc_fmj", WaterClass.ofConfig(c), "phosphorus 300", "phosphorus " + got[0], got[0] >= 300, "");
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
        for (int i = 0; i < 40 && !WaterBullet.stopped(h) && !h.isDead; i++) tick(h);
        tick(h);
        boolean sinking = WaterBullet.stopped(h) && !h.isDead;
        Vec3 at = pos(h);
        EntityBulletBaseMK4 p = bullet(plain, new Vec3(at.x - 1.0, at.y, at.z), new Vec3(1, 0, 0));
        BLASTS.clear();
        for (int i = 0; i < 6 && !p.isDead && BLASTS.isEmpty(); i++) tick(p);
        double[] blast = BLASTS.isEmpty() ? null : BLASTS.get(0);
        rowFlag("h: a sinking r762_he hit by a 9 mm: goes off", "ammo_standard_r762_he", WaterClass.ofConfig(he), "blast at " + str3(at),
                str(blast), sinking && blast != null && new Vec3(blast[0], blast[1], blast[2]).distanceTo(at) < 1.0,
                "sinking=" + sinking + " heRemoved=" + h.isDead);
    }

    // an r762_he going off on the surface over a sinking one: NTM moves a bullet the whole tick's way after its impact,
    // also one dead there, so its path to touch others ends where it hit the water, not under it. The one sinking under
    // the line it came down along stays.
    private static void deadPath(Map<String, BulletConfig> all) {
        BulletConfig he = byAmmo(all, "ammo_standard_r762_he");
        if (he == null) {
            lines.add("#\tno config for the dead path");
            return;
        }
        basin();
        EntityBulletBaseMK4 h = bullet(he, new Vec3(MIN + 3.5, surface - 1.5, CZ), new Vec3(1, 0, 0));
        for (int i = 0; i < 40 && !WaterBullet.stopped(h) && !h.isDead; i++) tick(h);
        tick(h);
        boolean sinking = WaterBullet.stopped(h) && !h.isDead;
        Vec3 s = pos(h);
        // 60° down onto the surface right over it, from a block above: the tick ends past it, under the water
        double r = Math.toRadians(60), depth = surface - s.y;
        Vec3 dir = new Vec3(Math.cos(r), -Math.sin(r), 0);
        Vec3 aim = new Vec3(s.x - depth / Math.tan(r), surface, s.z);
        EntityBulletBaseMK4 p = bullet(he, aim.subtract(dir.scale(1.0)), dir);
        // another shot: the stand makes both in one game tick, with no shooter, which WaterImpact.sameShot leaves alone
        ((WaterBullet) p).gunfeel$shot(((WaterBullet) p).gunfeel$shot() + 1);
        BLASTS.clear();
        tick(p);
        Vec3 after = pos(p);
        int blasts = BLASTS.size();
        rowFlag("h: one going off on the surface over a sinking one: that one stays", "ammo_standard_r762_he", WaterClass.ofConfig(he),
                "1 blast on the surface, the sinking one there", blasts + " blasts, sinking one " + (h.isDead ? "gone" : "there"),
                sinking && blasts == 1 && Math.abs(BLASTS.get(0)[1] - surface) < 0.35 && !h.isDead && p.isDead && after.y < s.y - 0.5,
                "sinking at " + str3(s) + ", the other moved on to " + str3(after) + ", blasts at y " + heights());
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
            WaterClass wc = WaterClass.ofConfig(c);
            float spread = c.spread + 0.02F;
            int n = Math.max(2, c.projectilesMax);
            lines.add("#\t" + ammo + "\tpellets " + n + " spread " + fmt(spread) + " fuze " + WaterClass.Fuze.ofConfig(c));
            for (int k = 0; k < 3; k++) {
                basin();
                String name = k == 0 ? "air, at the muzzle" : k == 1 ? "in water, at the muzzle" : "in water, on the floor";
                // on the floor: the eyes half a block over it (the stand's feet in the floor), so the pellets water
                // stops lie on the floor together a few ticks later
                Vec3 feet = k == 0 ? new Vec3(DRY_X - 5, FLOOR + 1, CZ) : k == 1 ? new Vec3(MIN + 3.5, FLOOR + 1, CZ)
                        : new Vec3(MIN + 3.5, FLOOR + 1.5 - eyes(), CZ);
                EntityZombie g = shooter(feet, 0F);
                int s0 = WaterImpact.searches, t0 = WaterImpact.touches, m0 = WaterImpact.sameShots;
                BLASTS.clear();
                List<EntityBulletBaseMK4> shot = shoot(g, c, n, spread);
                int ticks = ticks(shot, k == 0 ? 6 : TICKS, false);
                int searches = WaterImpact.searches - s0, touches = WaterImpact.touches - t0, blasts = BLASTS.size();
                // in water the pellets of the shot did meet (pairs left alone), so 0 touches is the rule, not a miss
                int same = WaterImpact.sameShots - m0;
                // the air: the owner's case (pellets going off at the muzzle); in water a pellet goes off on the floor,
                // or NTM's life of it ends (no blast) while it sinks
                boolean pass = touches == 0 && (k == 0 ? searches == 0 && blasts == 0 : same > 0 && blasts <= n && (k == 1 || blasts > 0));
                rowFlag("h: one shot, " + name + ": no touch", ammo, wc, k == 0 ? "0 searches, 0 touches, 0 blasts" : k == 1 ? "0 touches"
                        : "0 touches, blasts on the floor",
                        searches + " searches, " + same + " pairs of the shot, " + touches + " touches, " + blasts + " blasts", pass,
                        "ticks " + ticks + ", sameShot " + WaterImpact.sameShot(shot.get(0), shot.get(n - 1)) + ", blasts at y " + heights());
            }
            // another shot into the sinking pellets of the first, both without spread so they meet
            basin();
            EntityZombie g = shooter(new Vec3(MIN + 3.5, FLOOR + 1, CZ), 0F);
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
        }
    }

    // NTM's life of a round (BulletConfig.expires): NTM removes a round older than that without a blast
    // (EntityBulletBaseMK4.onUpdate), in water as in the air; it has no timed fuze for bullets. Ticked as the world
    // ticks (ticksExisted up, then the tick), from its first tick: stopped high in the basin, a round with a short life
    // is gone on the way down, one with a long life reaches the floor; stopped near the floor, any goes off there.
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
            WaterClass wc = WaterClass.ofConfig(c);
            for (boolean high : new boolean[] {true, false}) {
                basin();
                EntityBulletBaseMK4 b = bullet(c, new Vec3(MIN + 3.5, high ? surface - 1 : FLOOR + 1.6, CZ), new Vec3(1, 0, 0));
                b.ticksExisted = 0;
                BLASTS.clear();
                double stopY = Double.NaN, lastY = Double.NaN;
                int stopTick = -1, gone = -1;
                for (int i = 0; i < c.expires + 20 && !b.isDead; i++) {
                    b.ticksExisted++;
                    boolean was = WaterBullet.stopped(b);
                    lastY = b.posY;
                    tick(b);
                    if (!was && WaterBullet.stopped(b)) {
                        stopY = b.posY;
                        stopTick = b.ticksExisted;
                    }
                    if (b.isDead) gone = b.ticksExisted;
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
            }
        }
    }

    // HE pellets fired straight down by a shooter standing in water: water stops them within 0.57 of its eyes, inside
    // its box. NTM tests a mob's box from the pellet's path, and a path that starts inside the box meets none of its
    // faces; the pellet in its shooter does not hit it from inside (MixinThrowableClip leaves the shooter out while the
    // pellet is in it). With the feet on the floor the pellets sink and go off on it; with the floor farther than their
    // life takes them (NTM's 30 ticks) they are gone on the way, as any round NTM's life ends.
    private static void downward(Map<String, BulletConfig> all) {
        String ammo = "ammo_standard_g10_explosive";
        BulletConfig c = byAmmo(all, ammo);
        if (c == null) {
            ok = false;
            lines.add("FAIL\tno config " + ammo);
            return;
        }
        WaterClass wc = WaterClass.ofConfig(c);
        int n = Math.max(2, c.projectilesMax);
        for (boolean onFloor : new boolean[] {true, false}) {
            basin();
            Vec3 feet = new Vec3(MIN + 3.5, onFloor ? FLOOR + 1 : FLOOR + 4, CZ);
            EntityZombie g = shooter(feet, 90F);
            AxisAlignedBB box = g.boundingBox.copy();
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
                    tick(b);
                    if (!was && WaterBullet.stopped(b) && inside(box, b.posX, b.posY, b.posZ)) inBox++;
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
                if (bl[1] < FLOOR + 1.4) onFloorBlasts++;
                else if (inside(box, bl[0], bl[1], bl[2])) onShooter++;
            }
            boolean pass = inBox == n && onShooter == 0 && silentEarly == 0
                    && (onFloor ? onFloorBlasts == n : BLASTS.isEmpty() && goneLo == c.expires + 1 && goneHi == c.expires + 1);
            rowFlag("j: shot straight down in water, feet " + (onFloor ? "on the floor" : "3 over the floor"), ammo, wc,
                    n + " stop in the shooter's box, none hits it, " + (onFloor ? n + " blasts on the floor" : "all gone at tick " + (c.expires + 1) + ", no blast"),
                    inBox + " stop in the box, " + onShooter + " on the shooter, " + onFloorBlasts + " on the floor, " + BLASTS.size() + " blasts, gone ticks "
                            + goneLo + ".." + goneHi,
                    pass, "life " + c.expires + ", box y " + fmt(box.minY) + ".." + fmt(box.maxY) + ", silent before the life's end " + silentEarly
                            + ", blasts at y " + heights());
        }
    }

    // 26.2's AABB.contains: min inclusive, max exclusive
    private static boolean inside(AxisAlignedBB b, double x, double y, double z) {
        return x >= b.minX && x < b.maxX && y >= b.minY && y < b.maxY && z >= b.minZ && z < b.maxZ;
    }

    // a shooter: a zombie (no armour stand in 1.7.10), facing +x; a pellet water stops within 0.57 of the eyes (g10)
    // is out of its box when fired level, as out of a player's
    private static EntityZombie shooter(Vec3 feet, float pitch) {
        EntityZombie g = new EntityZombie(level);
        g.setLocationAndAngles(feet.x, feet.y, feet.z, -90F, pitch);
        g.rotationYawHead = -90F;
        level.spawnEntityInWorld(g);
        return g;
    }

    private static double eyes() {
        return new EntityZombie(level).getEyeHeight();
    }

    // the pellets of one shot, made as a gun makes them (EntityBulletBaseMK4 from the shooter, in a loop)
    private static List<EntityBulletBaseMK4> shoot(EntityZombie g, BulletConfig c, int n, float spread) {
        List<EntityBulletBaseMK4> out = new ArrayList<EntityBulletBaseMK4>();
        for (int i = 0; i < n; i++) {
            EntityBulletBaseMK4 b = new EntityBulletBaseMK4(g, c, DAMAGE, spread, 0, 0, 0);
            put(b);
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
                tick(b);
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
