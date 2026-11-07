package acup.gunfeel;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.hbm.blocks.ModBlocks;
import com.hbm.entity.projectile.EntityBullet;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.entity.projectile.EntityBulletBaseNT;
import com.hbm.handler.BulletConfigSyncingUtil;
import com.hbm.handler.BulletConfiguration;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.XFactory762mm;
import com.hbm.items.weapon.sedna.factory.XFactory9mm;
import com.hbm.items.weapon.sedna.factory.XFactoryTurret;

import acup.gunfeel.phys.Vec3;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayerFactory;

// Dev (-Pscenario=npc, ShootScenario's flat world): 26.2's NpcShotSmoke (X547) on NTM 1.7.10, after the 1.12.2 port's
// NpcShotsScenario. Every shooter's bullet made as that shooter makes it (a turret: MK4 without a thrower, as
// TileEntityTurretBaseNT.spawnBullet; a mob: MK4 thrown by a mob, as the taint crab; the hunter chopper and the cyber
// crab: NTM's old EntityBullet; the masked man, BOT Prime, the UFO: EntityBulletBaseNT) flies real server ticks:
// - at a zombie, in the body, the head and a limb, and at the body behind glass and in front of glass; the points are
//   where the zombie's model (its pose, sent by this client) is first met along +z, sorted by ModelHits' own head and
//   limb zones. The same round from a player (FakePlayer) beside it: the numbers must be equal. The old bullets have no
//   player's round: the head must take what the body takes from 1.75 times the damage, a limb from half.
// - down lanes of glass, iron bars (on the post and beside it) and NTM's concrete, concrete behind: the client counts
//   the holes on each block, the server what is left of them.
// X5863's chopper bullet carries no damage (NTM's constructor takes 35..45 and never sets it): here it gets 40, as
// 26.2's, so the head and limbs show. EntityBulletBaseNT only with its configs that do not blow up: a blast's damage
// is NTM's ExplosionNT, not the hit's, and would differ between the head and the body.
// run/client/npc.txt gets the verdicts, "NPC OK" or "NPC FAIL (n)", npc-shots.tsv and npc-lanes.tsv the numbers.
final class NpcShotsScenario {
    private NpcShotsScenario() {
    }

    private static final int PLACE = 40, MEASURE = 120, FIRE = 150, AFTER = 200, CHECK = 250, DONE = 270;
    private static final double STEP = 0.04;

    private enum Kind { TURRET, MOB, PLAYER, LEGACY, OLD }

    private static final class Shooter {
        final String name;
        final BulletConfig config;
        final float base;
        final Kind kind;
        final boolean critical;
        final int old; // EntityBulletBaseNT's config (BulletConfigSyncingUtil), -1 for the others

        Shooter(String name, BulletConfig config, float base, Kind kind, boolean critical) {
            this(name, config, base, kind, critical, -1);
        }

        Shooter(String name, BulletConfig config, float base, Kind kind, boolean critical, int old) {
            this.name = name;
            this.config = config;
            this.base = base;
            this.kind = kind;
            this.critical = critical;
            this.old = old;
        }

        float damage() {
            return config == null ? base : base * config.damageMult;
        }

        // blocks a tick: EntityHunterChopper:172 3 x 1.5, EntityCyberCrab:107 1.6; EntityBulletBaseNT its config's
        double speed() {
            if (kind == Kind.OLD) return BulletConfigSyncingUtil.pullConfig(old).velocity;
            return critical ? 1.6 : 4.5;
        }
    }

    // pairs (NPC, player) first, then the old bullets alone; from LANED on at the zombie only
    private static final int PAIRS = 4, LANED = 10;
    private static List<Shooter> shooters;

    private static List<Shooter> shooters() {
        if (shooters == null) shooters = Arrays.asList(
                new Shooter("Sentry p9_fmj", XFactory9mm.p9_fmj, 5, Kind.TURRET, false), new Shooter("игрок p9_fmj", XFactory9mm.p9_fmj, 5, Kind.PLAYER, false),
                new Shooter("Jeremy shell_ap", XFactoryTurret.shell_ap, 50, Kind.TURRET, false), new Shooter("игрок shell_ap", XFactoryTurret.shell_ap, 50, Kind.PLAYER, false),
                new Shooter("заражённый краб r762_fmj", XFactory762mm.r762_fmj, 10, Kind.MOB, false), new Shooter("игрок r762_fmj", XFactory762mm.r762_fmj, 10, Kind.PLAYER, false),
                new Shooter("моб с пушкой p9_fmj", XFactory9mm.p9_fmj, 5, Kind.MOB, false), new Shooter("игрок p9_fmj (моб)", XFactory9mm.p9_fmj, 5, Kind.PLAYER, false),
                // the chopper's damage as 26.2's (X5863: 0, see above); EntityCyberCrab:110 3
                new Shooter("вертолёт EntityBullet", null, 40, Kind.LEGACY, false), new Shooter("кибер-краб EntityBullet", null, 3, Kind.LEGACY, true),
                // EntityAIMaskmanLasergun:72, EntityBOTPrimeBase:69/75, EntityUFO:292
                new Shooter("маскмен трассер MASKMAN_TRACER", null, 18, Kind.OLD, false, BulletConfigSyncingUtil.MASKMAN_TRACER),
                new Shooter("BOT Prime WORM_BOLT", null, 20, Kind.OLD, false, BulletConfigSyncingUtil.WORM_BOLT),
                new Shooter("НЛО / BOT Prime WORM_LASER", null, 45, Kind.OLD, false, BulletConfigSyncingUtil.WORM_LASER));
        return shooters;
    }

    private static final class Lane {
        final int shooter;
        final String row;
        final int[] front, back;
        final Vec3 from;

        Lane(int shooter, String row, int[] front, int[] back, Vec3 from) {
            this.shooter = shooter;
            this.row = row;
            this.front = front;
            this.back = back;
            this.from = from;
        }
    }

    private static final String[] ROWS = {"стекло", "решётка в стойку", "решётка в щель", "бетон"};
    private static final List<Lane> LANES = new ArrayList<Lane>();
    private static final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
    private static final Map<String, String> AFTER_BLOCKS = Collections.synchronizedMap(new HashMap<String, String>());
    private static volatile int failed;
    // shooters measured at the zombie (measure)
    private static volatile int measured;
    private static EntityZombie zombie;
    private static int[] at; // the player's block

    static void step(Minecraft mc, int t) {
        if (t == PLACE) ShootScenario.onServer(NpcShotsScenario::place);
        if (t > PLACE && t < MEASURE + 5) aim(mc);
        if (t == MEASURE) ShootScenario.onServer(NpcShotsScenario::measure);
        if (t == FIRE) ShootScenario.onServer(NpcShotsScenario::fire);
        if (t == AFTER) ShootScenario.onServer(NpcShotsScenario::after);
        if (t == CHECK) check();
        if (t == DONE) {
            GunFeel.LOGGER.info("Scenario npc: {}", failed == 0 ? "NPC OK" : "NPC FAIL (" + failed + ")");
            mc.shutdown();
        }
    }

    private static EntityPlayerMP player() {
        return (EntityPlayerMP) MinecraftServer.getServer().getConfigurationManager().playerEntityList.get(0);
    }

    private static void expect(String what, boolean ok) {
        if (!ok) failed++;
        lines.add((ok ? "OK   " : "FAIL ") + what);
    }

    private static String f(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static void set(World w, int[] p, Block b, int meta) {
        w.setBlock(p[0], p[1], p[2], b, meta, 3);
    }

    // the zombie 5 blocks ahead (+z), facing the player, standing still, at night (it would burn); the lanes to the east
    private static void place() {
        EntityPlayerMP p = player();
        World w = p.worldObj;
        w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
        w.getGameRules().setOrCreateGameRule("mobGriefing", "true");
        w.setWorldTime(18000);
        at = new int[] {(int) Math.floor(p.posX), (int) Math.floor(p.posY), (int) Math.floor(p.posZ)};
        zombie = new EntityZombie(w);
        // port: 1.7.10 has no setNoAI: no tasks and no speed keep it where it is and as it is turned
        zombie.tasks.taskEntries.clear();
        zombie.targetTasks.taskEntries.clear();
        zombie.getEntityAttribute(SharedMonsterAttributes.movementSpeed).setBaseValue(0);
        zombie.getEntityAttribute(SharedMonsterAttributes.maxHealth).setBaseValue(1000);
        zombie.setHealth(1000);
        zombie.setLocationAndAngles(at[0] + .5, at[1], at[2] + 5.5, 180F, 0F);
        zombie.rotationYawHead = zombie.prevRotationYawHead = zombie.renderYawOffset = zombie.prevRenderYawOffset = 180F;
        w.spawnEntityInWorld(zombie);
        lanes(w);
    }

    // looks at the zombie's middle, so the client draws it and sends its pose
    private static void aim(Minecraft mc) {
        if (at == null || mc.thePlayer == null) return;
        double dz = at[2] + 5.5 - mc.thePlayer.posZ, dy = at[1] + 1.0 - (mc.thePlayer.boundingBox.minY + mc.thePlayer.getEyeHeight());
        mc.thePlayer.rotationYaw = mc.thePlayer.prevRotationYaw = 0F;
        mc.thePlayer.rotationPitch = mc.thePlayer.prevRotationPitch = (float) -Math.toDegrees(Math.atan2(dy, dz));
    }

    // the first model point along +z through (x, y), or null
    private static Vec3 point(double x, double y) {
        AxisAlignedBB box = zombie.boundingBox;
        java.util.Optional<Vec3> hit = ModelHits.clip(zombie, new Vec3(x, y, box.minZ - 3), new Vec3(x, y, box.maxZ + 3), box);
        return hit.isPresent() ? hit.get() : null;
    }

    // 0 body, 1 head, 2 limb, -1 missed
    private static int zone(Vec3 p) {
        if (p == null) return -1;
        if (ModelHits.head(zombie, p).head()) return 1;
        return ModelHits.limb(zombie, p) ? 2 : 0;
    }

    // per zone: a point whose four neighbours on the grid are in the same zone, the nearest to the zone's middle
    private static Vec3[] points() {
        AxisAlignedBB box = zombie.boundingBox;
        int nx = (int) Math.ceil((box.maxX - box.minX) / STEP) + 1, ny = (int) Math.ceil((box.maxY - box.minY) / STEP) + 1;
        int[][] zones = new int[nx][ny];
        Vec3[][] pts = new Vec3[nx][ny];
        double[][] mid = new double[3][3];
        for (int i = 0; i < nx; i++) for (int j = 0; j < ny; j++) {
            pts[i][j] = point(box.minX + i * STEP, box.minY + j * STEP);
            int z = zones[i][j] = zone(pts[i][j]);
            if (z >= 0) {
                mid[z][0] += pts[i][j].x;
                mid[z][1] += pts[i][j].y;
                mid[z][2]++;
            }
        }
        Vec3[] out = new Vec3[3];
        double[] best = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        for (int i = 1; i + 1 < nx; i++) for (int j = 1; j + 1 < ny; j++) {
            int z = zones[i][j];
            if (z < 0 || zones[i - 1][j] != z || zones[i + 1][j] != z || zones[i][j - 1] != z || zones[i][j + 1] != z) continue;
            double mx = mid[z][0] / mid[z][2], my = mid[z][1] / mid[z][2];
            double d = (pts[i][j].x - mx) * (pts[i][j].x - mx) + (pts[i][j].y - my) * (pts[i][j].y - my);
            if (d < best[z]) {
                best[z] = d;
                out[z] = pts[i][j];
            }
        }
        lines.add(String.format(Locale.ROOT, "zones: body %d, head %d, limb %d points; head source %s", (int) mid[0][2], (int) mid[1][2], (int) mid[2][2],
                out[1] == null ? "-" : ModelHits.head(zombie, out[1]).source()));
        return out;
    }

    private static Entity bullet(World w, Shooter s, Vec3 from, Vec3 way, float damage, EntityLivingBase mob, EntityPlayerMP player) {
        if (s.kind == Kind.OLD) {
            // as the masked man and the worm make it (thrown by a mob), its way a unit (NTM flies motion x velocity a
            // tick), its damage fixed
            EntityBulletBaseNT b = new EntityBulletBaseNT(w, s.old);
            b.setThrower(mob);
            b.overrideDamage = damage;
            b.setPosition(from.x, from.y, from.z);
            b.motionX = way.x;
            b.motionY = way.y;
            b.motionZ = way.z;
            return b;
        }
        if (s.kind == Kind.LEGACY) {
            EntityBullet b = new EntityBullet(w, from.x, from.y, from.z);
            b.damage = damage;
            b.shootingEntity = mob;
            if (s.critical) {
                b.setIsCritical(true);
                b.setTau(true);
            } else {
                b.setChopper(true);
                b.setIsCritical(false);
            }
            double speed = s.speed();
            b.motionX = way.x * speed;
            b.motionY = way.y * speed;
            b.motionZ = way.z * speed;
            return b;
        }
        // as the turret's spawnBullet: new EntityBulletBaseMK4(world, cfg, base, spread, yaw, pitch) at the muzzle
        EntityBulletBaseMK4 b = new EntityBulletBaseMK4(w, s.config, s.base, 0F, 0F, 0F);
        if (s.kind == Kind.MOB) b.setThrower(mob);
        if (s.kind == Kind.PLAYER) b.setThrower(player);
        b.damage = damage;
        b.setPosition(from.x, from.y, from.z);
        b.setThrowableHeading(way.x, way.y, way.z, 1F, 0F);
        return b;
    }

    private static void fly(Entity bullet, Vec3 from) {
        StringBuilder ticks = bullet instanceof EntityBullet || bullet instanceof EntityBulletBaseNT ? new StringBuilder("legacy ticks:") : null;
        // port: in the world, though its ticks are run here (within one server tick): NTM 1.7.10's MK4 sends its
        // teleport through the entity tracker when it dies (TrackerUtil.sendTeleport), which knows only spawned ones
        bullet.worldObj.spawnEntityInWorld(bullet);
        for (int i = 0; i < 40 && !bullet.isDead && bullet.getDistance(from.x, from.y, from.z) < 40; i++) {
            float was = zombie.getHealth();
            double z0 = bullet.posZ;
            bullet.onUpdate();
            if (ticks != null && zombie.getHealth() != was)
                ticks.append(String.format(Locale.ROOT, " t%d z %.2f->%.2f (zombie %.2f) lost %.2f;", i, z0, bullet.posZ, zombie.posZ, was - zombie.getHealth()));
        }
        if (ticks != null) lines.add(ticks.toString());
        bullet.setDead();
    }

    private static float shot(World w, Shooter s, Vec3 point, float amount, EntityLivingBase mob, EntityPlayerMP player) {
        double hx = zombie.posX, hy = zombie.posY, hz = zombie.posZ;
        zombie.setHealth(1000);
        zombie.hurtResistantTime = 0;
        zombie.hurtTime = 0;
        // an old bullet starts a step and a half before the point: NTM 1.7.10's EntityBullet and EntityBulletBaseNT meet
        // creatures from where they are to where they go, so the second tick reaches the point
        double back = s.kind == Kind.LEGACY || s.kind == Kind.OLD ? Math.max(1.5, 1.5 * s.speed()) : 3;
        Vec3 from = point.add(0, 0, -back);
        fly(bullet(w, s, from, new Vec3(0, 0, 1), amount, mob, player), from);
        float lost = 1000 - zombie.getHealth();
        zombie.setPosition(hx, hy, hz);
        zombie.motionX = zombie.motionY = zombie.motionZ = 0;
        return lost;
    }

    private static void measure() {
        World w = zombie.worldObj;
        int[] glass = null, after = null;
        try {
            expect("NPC: the server has the zombie's pose", ModelHits.hasPose(zombie));
            Vec3[] p = points();
            Vec3 body = p[0], head = p[1], limb = p[2];
            expect("NPC: points of the body, head, limb: " + body + ", " + head + ", " + limb, body != null && head != null && limb != null);
            if (body == null || head == null || limb == null) return;
            glass = new int[] {(int) Math.floor(body.x), (int) Math.floor(body.y), (int) Math.floor(body.z - 2.5)};
            after = new int[] {(int) Math.floor(body.x), (int) Math.floor(body.y), (int) Math.floor(body.z + 1.2)};
            EntityZombie mob = new EntityZombie(w);
            mob.setPosition(at[0] + .5, at[1] + 60, at[2] - 40);
            EntityPlayerMP player = owner(w);
            List<String> rows = new ArrayList<String>();
            rows.add("стрелок\tурон\tтело\tголова\tтело×1,75\tконечность\tтело×0,5\tза стеклом\tстекло разбито\tстекло за мобом цело");
            Map<Integer, float[]> numbers = new HashMap<Integer, float[]>();
            for (int i = 0; i < shooters().size(); i++) {
                Shooter s = shooters().get(i);
                float base = s.damage();
                float b = shot(w, s, body, base, mob, player), h = shot(w, s, head, base, mob, player), h2 = shot(w, s, body, base * 1.75F, mob, player);
                float l = shot(w, s, limb, base, mob, player), l2 = shot(w, s, body, base * .5F, mob, player);
                if (i >= LANED) {
                    // at the zombie only: its blocks stay NTM's
                    BulletConfiguration c = BulletConfigSyncingUtil.pullConfig(s.old);
                    String how = String.format(Locale.ROOT, " (velocity %.2f, gravity %.3f, penetrates %s, explosive %.2f, headshotMult %.2f)", c.velocity, c.gravity,
                            c.doesPenetrate, c.explosive, c.headshotMult);
                    numbers.put(i, new float[] {b, h, h2, l, l2});
                    rows.add(s.name + "\t" + f(base) + "\t" + f(b) + "\t" + f(h) + "\t" + f(h2) + "\t" + f(l) + "\t" + f(l2) + "\t-\t-\t-");
                    expect("NPC " + s.name + how + ": damage " + f(base) + ", body=" + f(b) + ", head=" + f(h) + " (body x1.75=" + f(h2) + "), limb=" + f(l)
                            + " (body x0.5=" + f(l2) + ")", b > 0 && Math.abs(h - h2) < .01 && Math.abs(l - l2) < .01 && h > b && l < b);
                    continue;
                }
                BulletServer.forgetWear();
                set(w, glass, Blocks.glass, 0);
                float behind = shot(w, s, body, base, mob, player);
                boolean broke = w.isAirBlock(glass[0], glass[1], glass[2]);
                set(w, glass, Blocks.air, 0);
                BulletServer.forgetWear();
                set(w, after, Blocks.glass, 0);
                float before = shot(w, s, body, base, mob, player);
                boolean whole = w.getBlock(after[0], after[1], after[2]) == Blocks.glass;
                set(w, after, Blocks.air, 0);
                // through the zombie to the glass behind: an armour-piercing round (doesPenetrate) and the chopper's
                // old bullet (it does not stop in a creature); the crab's critical one flies through blocks unbroken
                boolean through = s.config != null ? s.config.doesPenetrate : !s.critical;
                expect("NPC " + s.name + ": glass behind the zombie whole=" + whole + (through ? " (goes through)" : "") + ", damage=" + f(before),
                        whole != through && Math.abs(before - b) < .01);
                numbers.put(i, new float[] {b, h, h2, l, l2, behind, broke ? 1 : 0});
                rows.add(s.name + "\t" + f(base) + "\t" + f(b) + "\t" + f(h) + "\t" + f(h2) + "\t" + f(l) + "\t" + f(l2) + "\t" + f(behind) + "\t" + broke + "\t" + whole);
                expect("NPC " + s.name + ": damage " + f(base) + ", body=" + f(b) + ", head=" + f(h) + " (body x1.75=" + f(h2) + "), limb=" + f(l) + " (body x0.5="
                        + f(l2) + ")", b > 0 && Math.abs(h - h2) < .01 && Math.abs(l - l2) < .01 && h > b && l < b);
                expect("NPC " + s.name + ": behind glass " + f(behind) + " of " + f(b) + ", glass broken=" + broke, behind > 0);
            }
            // every shooter measured: a verdict on fewer is no verdict
            measured = numbers.size();
            expect("NPC measured: " + measured + " of " + shooters().size(), measured == shooters().size());
            for (int i = 0; i < 2 * PAIRS; i += 2) {
                float[] npc = numbers.get(i), own = numbers.get(i + 1);
                boolean same = true;
                for (int k = 0; k < npc.length; k++) same &= Math.abs(npc[k] - own[k]) < .01;
                expect("NPC " + shooters().get(i).name + " = " + shooters().get(i + 1).name + ": " + Arrays.toString(npc) + " / " + Arrays.toString(own), same);
            }
            write("npc-shots.tsv", rows);
        } catch (Throwable e) {
            expect("NPC: exception " + e, false);
            GunFeel.LOGGER.error("Scenario npc", e);
        } finally {
            if (glass != null) set(w, glass, Blocks.air, 0);
            if (after != null) set(w, after, Blocks.air, 0);
        }
    }

    // a lane a shooter, east of the player, along +x: glass, bars (its post and beside it), NTM's concrete; concrete
    // behind the first three. Put up now, fired at later: the blocks must reach the client before the shots.
    private static void lanes(World w) {
        int x0 = at[0] + 8, y0 = at[1] + 1, z0 = at[2] - LANED;
        for (int x = x0 - 1; x <= x0 + 8; x++) for (int y = y0; y <= y0 + 8; y++) for (int z = z0 - 1; z <= z0 + 2 * LANED + 1; z++)
            w.setBlock(x, y, z, Blocks.air, 0, 3);
        for (int i = 0; i < LANED; i++) {
            int z = z0 + 2 * i;
            for (int r = 0; r < 4; r++) {
                int y = y0 + 2 * r;
                int[] front = {x0 + 3, y, z}, back = r == 3 ? null : new int[] {x0 + 6, y, z};
                if (r == 0) set(w, front, Blocks.glass, 0);
                else if (r < 3) set(w, front, Blocks.iron_bars, 0);
                else set(w, front, ModBlocks.concrete_smooth, 0);
                if (back != null) set(w, back, ModBlocks.concrete_smooth, 0);
                LANES.add(new Lane(i, ROWS[r], front, back, new Vec3(x0 + .5, y + .5, z + (r == 2 ? .8 : .5))));
            }
        }
    }

    // the lanes' bullets go into the world as the game's do: the client hears of them and flies their tracers.
    // The FakePlayer stands where it was made, by the world's spawn, maybe within 3 blocks of a lane's start: then its
    // eyes go to the client with the shot and the tracer is tested from there (MixinBulletServer). Out of the way, as
    // the mob that owns the mobs' bullets
    private static EntityPlayerMP owner(World w) {
        EntityPlayerMP player = FakePlayerFactory.getMinecraft((WorldServer) w);
        player.setPosition(at[0] + .5, at[1] + 60, at[2] - 40);
        return player;
    }

    private static void fire() {
        World w = zombie.worldObj;
        try {
            EntityZombie mob = new EntityZombie(w);
            mob.setPosition(at[0] + .5, at[1] + 60, at[2] - 40);
            EntityPlayerMP player = owner(w);
            BulletServer.forgetWear();
            for (Lane lane : LANES) {
                Shooter s = shooters().get(lane.shooter);
                w.spawnEntityInWorld(bullet(w, s, lane.from, new Vec3(1, 0, 0), s.damage(), mob, player));
            }
        } catch (Throwable e) {
            expect("NPC: lanes not fired: " + e, false);
            GunFeel.LOGGER.error("Scenario npc", e);
        }
    }

    private static String name(World w, int[] p) {
        return p == null ? "-" : Block.blockRegistry.getNameForObject(w.getBlock(p[0], p[1], p[2]));
    }

    private static void after() {
        World w = zombie.worldObj;
        for (Lane lane : LANES) AFTER_BLOCKS.put(lane.shooter + "|" + lane.row, name(w, lane.front) + " / " + name(w, lane.back));
    }

    private static int holes(int[] p) {
        return p == null ? 0 : Tracers.holesOn(p[0], p[1], p[2]);
    }

    private static void check() {
        // the zones measured and all lanes laid: an OK on none is no OK
        expect("NPC zones measured before the lanes: " + measured + " of " + shooters().size(), measured == shooters().size());
        expect("NPC lanes: " + LANES.size() + " of " + LANED * 4, LANES.size() == LANED * 4);
        List<String> rows = new ArrayList<String>();
        rows.add("стрелок\tполоса\tдырок спереди\tдырок на бетоне за\tблоки после (сервер)");
        Map<String, int[]> holes = new HashMap<String, int[]>();
        for (Lane lane : LANES) {
            int front = holes(lane.front), back = holes(lane.back);
            String key = lane.shooter + "|" + lane.row;
            holes.put(key, new int[] {front, back});
            Shooter s = shooters().get(lane.shooter);
            rows.add(s.name + "\t" + lane.row + "\t" + front + "\t" + (lane.back == null ? "-" : back) + "\t" + AFTER_BLOCKS.get(key));
            if (lane.row.equals("бетон")) {
                // the crab's critical bullet flies through blocks in NTM: no hole
                if (s.critical) expect("NPC " + s.name + ": no hole in concrete, as NTM: " + front, front == 0);
                // the Jeremy turret's 240 mm shells too: followed undrawn for their holes (Tracers.UNDRAWN)
                else expect("NPC " + s.name + ": a hole in concrete: " + front, front > 0);
            }
        }
        for (int i = 0; i < 2 * PAIRS; i += 2) for (String row : ROWS) {
            int[] a = holes.get(i + "|" + row), b = holes.get((i + 1) + "|" + row);
            String sa = AFTER_BLOCKS.get(i + "|" + row), sb = AFTER_BLOCKS.get((i + 1) + "|" + row);
            expect("NPC lane «" + row + "»: " + shooters().get(i).name + " " + Arrays.toString(a) + " " + sa + " = " + shooters().get(i + 1).name + " "
                    + Arrays.toString(b) + " " + sb, a != null && b != null && (a[0] > 0) == (b[0] > 0) && (a[1] > 0) == (b[1] > 0) && Objects.equals(sa, sb));
        }
        write("npc-lanes.tsv", rows);
        lines.add(failed == 0 ? "NPC OK" : "NPC FAIL (" + failed + ")");
        write("npc.txt", lines);
        for (String l : new ArrayList<String>(lines)) GunFeel.LOGGER.info("Scenario npc: {}", l);
    }

    private static void write(String file, List<String> rows) {
        try (PrintWriter pw = new PrintWriter(new File(Minecraft.getMinecraft().mcDataDir, file), StandardCharsets.UTF_8.name())) {
            for (String l : new ArrayList<String>(rows)) pw.println(l);
        } catch (java.io.IOException e) {
            GunFeel.LOGGER.error("Scenario npc: " + file, e);
        }
    }
}
