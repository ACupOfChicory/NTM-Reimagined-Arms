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

import com.hbm.entity.projectile.EntityBullet;
import com.hbm.entity.projectile.EntityBulletBase;
import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.handler.BulletConfigSyncingUtil;
import com.hbm.handler.BulletConfiguration;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.XFactory762mm;
import com.hbm.items.weapon.sedna.factory.XFactory9mm;
import com.hbm.items.weapon.sedna.factory.XFactoryTurret;

import acup.gunfeel.phys.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayerFactory;

// Dev (-Pscenario=npc, ShootScenario's flat world): 26.2's NpcShotSmoke (X547) on NTM CE. Every shooter's bullet made
// as that shooter makes it (a turret: MK4 without a thrower, as spawnBullet; a mob: MK4 thrown by a mob, as the taint
// crab; the hunter chopper and the cyber crab: NTM's old EntityBullet) flies real server ticks:
// - at a zombie (NTM CE's old bullet hits it twice, see shot), in the body, the head and a limb, and at the body behind glass and in front of glass; the points are
//   where the zombie's model (its pose, sent by this client) is first met along +z, sorted by ModelHits' own head and
//   limb zones. The same round from a player (FakePlayer) beside it: the numbers must be equal. The old bullet has no
//   player's round: its head must take what the body takes from 1.75 times its damage, a limb from half.
// - NTM CE's other old bullet, EntityBulletBase (the masked man, BOT Prime, the UFO; MixinOldBulletBase), at the zombie
//   only, its damage fixed (overrideDamage): the same head and limb rule (none of them has a calibre a player's MK4 round
//   has). The tested NTM CE has no config for the old player guns' rounds (the Super Shotgun's G12_*, the Vortex's
//   R556_STAR) nor the trapped brick's dart (G20_CAUSTIC): checked to be missing, so no player's gun fires this bullet.
// - down lanes of glass, iron bars (on the post and beside it) and concrete, concrete behind: the client counts the
//   holes on each block, the server what is left of them.
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
        final int old; // EntityBulletBase's config (BulletConfigSyncingUtil), -1 for the others

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
                // EntityHunterChopper:201: 35..45, "chopper", 3 blocks a tick; EntityCyberCrab:99: 2, critical, 2.4 a tick
                new Shooter("вертолёт EntityBullet", null, 40, Kind.LEGACY, false), new Shooter("кибер-краб EntityBullet", null, 2, Kind.LEGACY, true),
                // EntityAIMaskmanMinigun:50, EntityAIMaskmanLasergun:53, EntityBOTPrimeBase:89, EntityUFO:341 / BOT Prime:82
                new Shooter("маскмен миниган MASKMAN_BULLET", null, 8, Kind.OLD, false, BulletConfigSyncingUtil.MASKMAN_BULLET),
                new Shooter("маскмен орб MASKMAN_ORB", null, 100, Kind.OLD, false, BulletConfigSyncingUtil.MASKMAN_ORB),
                new Shooter("BOT Prime WORM_BOLT", null, 20, Kind.OLD, false, BulletConfigSyncingUtil.WORM_BOLT),
                new Shooter("НЛО / BOT Prime WORM_LASER", null, 45, Kind.OLD, false, BulletConfigSyncingUtil.WORM_LASER),
                new Shooter("смерть с неба MASKMAN_BOLT", null, 30, Kind.OLD, false, BulletConfigSyncingUtil.MASKMAN_BOLT));
        return shooters;
    }

    private static final class Lane {
        final int shooter;
        final String row;
        final BlockPos front, back;
        final Vec3d from;

        Lane(int shooter, String row, BlockPos front, BlockPos back, Vec3d from) {
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
    private static EntityZombie zombie;
    private static int[] at; // the player's block

    static void step(Minecraft mc, int t) {
        if (t == PLACE) ShootScenario.onServer(NpcShotsScenario::place);
        if (t > PLACE && t < MEASURE + 5) aim(mc);
        if (t == MEASURE) ShootScenario.onServer(NpcShotsScenario::measure);
        if (t == FIRE) ShootScenario.onServer(NpcShotsScenario::fire);
        if (t == AFTER) ShootScenario.onServer(NpcShotsScenario::after);
        if (t == CHECK) check(mc);
        if (t == DONE) {
            GunFeel.LOGGER.info("Scenario npc: {}", failed == 0 ? "NPC OK" : "NPC FAIL (" + failed + ")");
            mc.shutdown();
        }
    }

    private static EntityPlayerMP player() {
        return net.minecraftforge.fml.common.FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().getPlayers().get(0);
    }

    private static void expect(String what, boolean ok) {
        if (!ok) failed++;
        lines.add((ok ? "OK   " : "FAIL ") + what);
    }

    private static String f(float v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // the zombie 5 blocks ahead (+z), facing the player, at night (it would burn); the lanes to the east
    private static void place() {
        EntityPlayerMP p = player();
        World w = p.world;
        w.getGameRules().setOrCreateGameRule("doDaylightCycle", "false");
        w.getGameRules().setOrCreateGameRule("mobGriefing", "true");
        w.setWorldTime(18000);
        at = new int[] {(int) Math.floor(p.posX), (int) Math.floor(p.posY), (int) Math.floor(p.posZ)};
        zombie = new EntityZombie(w);
        zombie.setNoAI(true);
        zombie.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(1000);
        zombie.setHealth(1000);
        zombie.setLocationAndAngles(at[0] + .5, at[1], at[2] + 5.5, 180F, 0F);
        zombie.rotationYawHead = zombie.prevRotationYawHead = zombie.renderYawOffset = zombie.prevRenderYawOffset = 180F;
        w.spawnEntity(zombie);
        lanes(w);
    }

    // looks at the zombie's middle, so the client draws it and sends its pose
    private static void aim(Minecraft mc) {
        if (at == null || mc.player == null) return;
        double dz = at[2] + 5.5 - mc.player.posZ, dy = at[1] + 1.0 - (mc.player.posY + mc.player.getEyeHeight());
        mc.player.rotationYaw = mc.player.prevRotationYaw = 0F;
        mc.player.rotationPitch = mc.player.prevRotationPitch = (float) -Math.toDegrees(Math.atan2(dy, dz));
    }

    // the first model point along +z through (x, y), or null
    private static Vec3d point(double x, double y) {
        AxisAlignedBB box = zombie.getEntityBoundingBox();
        java.util.Optional<Vec3> hit = ModelHits.clip(zombie, new Vec3(x, y, box.minZ - 3), new Vec3(x, y, box.maxZ + 3), box);
        return hit.isPresent() ? ModelHits.mc(hit.get()) : null;
    }

    // 0 body, 1 head, 2 limb, -1 missed
    private static int zone(Vec3d p) {
        if (p == null) return -1;
        Vec3 v = ModelHits.of(p);
        if (ModelHits.head(zombie, v).head()) return 1;
        return ModelHits.limb(zombie, v) ? 2 : 0;
    }

    // per zone: a point whose four neighbours on the grid are in the same zone, the nearest to the zone's middle
    private static Vec3d[] points() {
        AxisAlignedBB box = zombie.getEntityBoundingBox();
        int nx = (int) Math.ceil((box.maxX - box.minX) / STEP) + 1, ny = (int) Math.ceil((box.maxY - box.minY) / STEP) + 1;
        int[][] zones = new int[nx][ny];
        Vec3d[][] pts = new Vec3d[nx][ny];
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
        Vec3d[] out = new Vec3d[3];
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
                out[1] == null ? "-" : ModelHits.head(zombie, ModelHits.of(out[1])).source()));
        return out;
    }

    private static Entity bullet(World w, Shooter s, Vec3d from, Vec3d way, float damage, EntityLivingBase mob, EntityPlayerMP player) {
        if (s.kind == Kind.OLD) {
            // as the mobs and ItemGunBase make it, its way a unit (NTM flies motion x velocity a tick), its damage fixed
            EntityBulletBase b = new EntityBulletBase(w, s.old);
            b.shooter = mob;
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
            double speed = s.critical ? 2.4 : 3.0;
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
        b.shoot(way.x, way.y, way.z, 1F, 0F);
        return b;
    }

    private static void fly(Entity bullet, Vec3d from) {
        StringBuilder ticks = bullet instanceof EntityBullet || bullet instanceof EntityBulletBase ? new StringBuilder("legacy ticks:") : null;
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

    private static float shot(World w, Shooter s, Vec3d at, float amount, EntityLivingBase mob, EntityPlayerMP player) {
        Vec3d home = new Vec3d(zombie.posX, zombie.posY, zombie.posZ);
        zombie.setHealth(1000);
        zombie.hurtResistantTime = 0;
        zombie.hurtTime = 0;
        // the old bullet starts a step and a half before the point: its ticks end clear of the zombie and of the glass in
        // front. NTM CE 2.6.1.0's old bullet meets creatures from where it was a tick before to where it goes (pos -
        // motion to pos + motion), so it hits a creature it flies through in two ticks, the second by its own "damage +
        // lastDamage" branch: twice the damage, NTM's way, the head and limbs in both ("legacy ticks" in npc.txt)
        // EntityBulletBase meets creatures from where it is to where it goes: it starts clear of the zombie's box and
        // reaches the point within its first tick, or several for a slow one
        Vec3d from = at.add(0, 0, s.kind == Kind.LEGACY ? -(s.critical ? 2.4 : 3.0) * 1.5 : s.kind == Kind.OLD ? -Math.max(1.5, .6 * oldConfig(s).velocity) : -3);
        fly(bullet(w, s, from, new Vec3d(0, 0, 1), amount, mob, player), from);
        float lost = 1000 - zombie.getHealth();
        zombie.setPosition(home.x, home.y, home.z);
        zombie.motionX = zombie.motionY = zombie.motionZ = 0;
        return lost;
    }

    private static BulletConfiguration oldConfig(Shooter s) {
        return BulletConfigSyncingUtil.pullConfig(s.old);
    }

    private static void measure() {
        World w = zombie.world;
        BlockPos glass = null, after = null;
        try {
            expect("NPC: the server has the zombie's pose", ModelHits.hasPose(zombie));
            Vec3d[] p = points();
            Vec3d body = p[0], head = p[1], limb = p[2];
            expect("NPC: points of the body, head, limb: " + body + ", " + head + ", " + limb, body != null && head != null && limb != null);
            if (body == null || head == null || limb == null) return;
            glass = new BlockPos(body.x, body.y, body.z - 2.5);
            after = new BlockPos(body.x, body.y, body.z + 1.2);
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
                    BulletConfiguration c = s.kind == Kind.OLD ? oldConfig(s) : null;
                    String how = c == null ? "" : String.format(Locale.ROOT, " (velocity %.2f, gravity %.3f, penetrates %s)", c.velocity, c.gravity, c.doesPenetrate);
                    numbers.put(i, new float[] {b, h, h2, l, l2});
                    rows.add(s.name + "\t" + f(base) + "\t" + f(b) + "\t" + f(h) + "\t" + f(h2) + "\t" + f(l) + "\t" + f(l2) + "\t-\t-\t-");
                    expect("NPC " + s.name + how + ": damage " + f(base) + ", body=" + f(b) + ", head=" + f(h) + " (body x1.75=" + f(h2) + "), limb=" + f(l)
                            + " (body x0.5=" + f(l2) + ")", b > 0 && Math.abs(h - h2) < .01 && Math.abs(l - l2) < .01 && h > b && l < b);
                    continue;
                }
                BulletServer.forgetWear();
                Mc.setBlock(w, glass.getX(), glass.getY(), glass.getZ(), Blocks.GLASS, 0, 3);
                float behind = shot(w, s, body, base, mob, player);
                boolean broke = w.isAirBlock(glass);
                Mc.setBlock(w, glass.getX(), glass.getY(), glass.getZ(), Blocks.AIR, 0, 3);
                BulletServer.forgetWear();
                Mc.setBlock(w, after.getX(), after.getY(), after.getZ(), Blocks.GLASS, 0, 3);
                float before = shot(w, s, body, base, mob, player);
                boolean whole = w.getBlockState(after).getBlock() == Blocks.GLASS;
                Mc.setBlock(w, after.getX(), after.getY(), after.getZ(), Blocks.AIR, 0, 3);
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
            // the old player guns' rounds and the dart: no config in this NTM CE (ItemGunBase.fire returns on it; the
            // Vortex overrides spawnProjectile with a ray and fires no bullet at all)
            int[] none = {BulletConfigSyncingUtil.G12_NORMAL, BulletConfigSyncingUtil.G12_INCENDIARY, BulletConfigSyncingUtil.G12_SHRAPNEL,
                    BulletConfigSyncingUtil.G12_DU, BulletConfigSyncingUtil.G12_AM, BulletConfigSyncingUtil.G12_SLEEK, BulletConfigSyncingUtil.R556_STAR,
                    BulletConfigSyncingUtil.G20_CAUSTIC};
            int found = 0;
            for (int id : none) if (BulletConfigSyncingUtil.pullConfig(id) != null) found++;
            expect("NPC: no config for the Super Shotgun's G12_*, the Vortex's R556_STAR, the trapped brick's G20_CAUSTIC: " + found + " of " + none.length + " found", found == 0);
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
            if (glass != null) Mc.setBlock(w, glass.getX(), glass.getY(), glass.getZ(), Blocks.AIR, 0, 3);
            if (after != null) Mc.setBlock(w, after.getX(), after.getY(), after.getZ(), Blocks.AIR, 0, 3);
        }
    }

    // a lane a shooter, east of the player, along +x: glass, bars (its post and beside it), concrete; concrete behind
    // the first three. Put up now, fired at later: the blocks must reach the client before the shots.
    private static void lanes(World w) {
        int x0 = at[0] + 8, y0 = at[1] + 1, z0 = at[2] - LANED;
        for (int x = x0 - 1; x <= x0 + 8; x++) for (int y = y0; y <= y0 + 8; y++) for (int z = z0 - 1; z <= z0 + 2 * LANED + 1; z++)
            Mc.setBlock(w, x, y, z, Blocks.AIR, 0, 3);
        for (int i = 0; i < LANED; i++) {
            int z = z0 + 2 * i;
            for (int r = 0; r < 4; r++) {
                int y = y0 + 2 * r;
                BlockPos front = new BlockPos(x0 + 3, y, z), back = r == 3 ? null : new BlockPos(x0 + 6, y, z);
                if (r == 0) Mc.setBlock(w, front.getX(), y, z, Blocks.GLASS, 0, 3);
                else if (r < 3) Mc.setBlock(w, front.getX(), y, z, Blocks.IRON_BARS, 0, 3);
                else Mc.setBlock(w, front.getX(), y, z, Blocks.CONCRETE, 7, 3);
                if (back != null) Mc.setBlock(w, back.getX(), y, z, Blocks.CONCRETE, 7, 3);
                LANES.add(new Lane(i, ROWS[r], front, back, new Vec3d(x0 + .5, y + .5, z + (r == 2 ? .8 : .5))));
            }
        }
    }

    // the lanes' bullets go into the world as the game's do: the client hears of them and flies their tracers
    // the FakePlayer stands where it was made, by the world's spawn, maybe within 3 blocks of a lane's start: then its
    // eyes go to the client with the shot and the tracer is tested from there (MixinBulletServer). Out of the way, as
    // the mob that owns the mobs' bullets
    private static EntityPlayerMP owner(World w) {
        EntityPlayerMP player = FakePlayerFactory.getMinecraft((WorldServer) w);
        player.setPosition(at[0] + .5, at[1] + 60, at[2] - 40);
        return player;
    }

    private static void fire() {
        World w = zombie.world;
        try {
            EntityZombie mob = new EntityZombie(w);
            mob.setPosition(at[0] + .5, at[1] + 60, at[2] - 40);
            EntityPlayerMP player = owner(w);
            BulletServer.forgetWear();
            for (Lane lane : LANES) {
                Shooter s = shooters().get(lane.shooter);
                w.spawnEntity(bullet(w, s, lane.from, new Vec3d(1, 0, 0), s.damage(), mob, player));
            }
        } catch (Throwable e) {
            expect("NPC: lanes not fired: " + e, false);
            GunFeel.LOGGER.error("Scenario npc", e);
        }
    }

    private static String name(World w, BlockPos p) {
        return p == null ? "-" : String.valueOf(w.getBlockState(p).getBlock().getRegistryName());
    }

    private static void after() {
        World w = zombie.world;
        for (Lane lane : LANES) AFTER_BLOCKS.put(lane.shooter + "|" + lane.row, name(w, lane.front) + " / " + name(w, lane.back));
    }

    private static int holes(List<Tracers.HoleView> all, BlockPos p) {
        if (p == null) return 0;
        int n = 0;
        for (Tracers.HoleView h : all) if (h.x == p.getX() && h.y == p.getY() && h.z == p.getZ()) n++;
        return n;
    }

    private static void check(Minecraft mc) {
        List<Tracers.HoleView> all = Tracers.holesNow();
        List<String> rows = new ArrayList<String>();
        rows.add("стрелок\tполоса\tдырок спереди\tдырок на бетоне за\tблоки после (сервер)");
        Map<String, int[]> holes = new HashMap<String, int[]>();
        for (Lane lane : LANES) {
            int front = holes(all, lane.front), back = holes(all, lane.back);
            String key = lane.shooter + "|" + lane.row;
            holes.put(key, new int[] {front, back});
            Shooter s = shooters().get(lane.shooter);
            rows.add(s.name + "\t" + lane.row + "\t" + front + "\t" + (lane.back == null ? "-" : back) + "\t" + AFTER_BLOCKS.get(key));
            if (lane.row.equals("бетон")) {
                // the crab's critical bullet flies through blocks in NTM: no hole
                if (s.critical) expect("NPC " + s.name + ": no hole in concrete, as NTM: " + front, front == 0);
                // NTM CE draws its 240 mm shells as grenades (GunFactoryClient: RENDER_GRENADE), no tracer: their path is
                // followed undrawn for the hole (Tracers.UNDRAWN), the player's as the turret's
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
        try (PrintWriter pw = new PrintWriter(new File(Minecraft.getMinecraft().gameDir, file), StandardCharsets.UTF_8.name())) {
            for (String l : new ArrayList<String>(rows)) pw.println(l);
        } catch (java.io.IOException e) {
            GunFeel.LOGGER.error("Scenario npc: " + file, e);
        }
    }
}
