package acup.gunfeel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.hbm.entity.projectile.EntityBulletBeamBase;
import com.hbm.entity.projectile.EntityCoin;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.tileentity.machine.storage.TileEntityBatterySocket;

import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

// port: 26.2's BeamServer (X578-X581) for 1.7.10: BlockHitResult -> BlockRay.Hit (a miss is null), BlockPos -> x y z
// (a place as BulletServer.key), Level.clip -> World.func_147447_a, the beam's end is its beamLength (sent once in its
// spawn data, after performHitscan). Not here: the battery socket's discharge, a beam of NTM 1.7.10 (26.2: its own
// EntityDischargeBeam, not one of the beams this is about).
// NTM's beams (EntityBulletBeamBase: the laser guns, the Tesla Cannon, Lacunae, Tau, the N.I.4.N.I., the Aberrator,
// shredder shells, the laser grenade; not the spectral ones, Folly and Tau's charged shot, which NTM sends through
// blocks) on the server go through blocks as a bullet of the same damage would (MixinBeamBlocks): what a bullet flies
// through (a fence's gap, a machine's empty cell) the beam does, what it pierces (glass, thin wood) the beam pierces
// with the same loss, what a bullet wears and breaks the beam wears and breaks. It leaves no hole. NTM stops it on the
// first block with a box. A creature behind a pierced block gets the damage the beam has there; blocks behind the
// creature or the coin that takes the beam are not touched. With beamRules off (server settings) it is NTM's beam as
// it is. reach: the same way for the beams with no beam entity (BeamTurrets).
public final class BeamServer {
    private BeamServer() {
    }

    public static final String KEY = "beamRules";

    // NTM's beams go through water (func_147447_a without liquids); false: water stops them where they meet it, as a
    // block with nothing behind it. The one place for that choice
    static final boolean THROUGH_WATER = true;

    public static boolean on() {
        return ReaimTimes.flag(KEY, true);
    }

    // the beam's way this shot: where it entered each block it pierced and its damage after it
    public static final class Path {
        final double fx, fy, fz;
        final float damage;
        private final List<double[]> steps = new ArrayList<>();

        Path(double fx, double fy, double fz, float damage) {
            this.fx = fx;
            this.fy = fy;
            this.fz = fz;
            this.damage = damage;
        }

        double from(double x, double y, double z) {
            double dx = x - fx, dy = y - fy, dz = z - fz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        // the damage the beam has at (x, y, z) on its way: what it had less what the blocks before took
        public float at(double x, double y, double z) {
            double d = from(x, y, z);
            float out = damage;
            for (double[] s : steps)
                if (s[0] < d) out = (float) s[1];
            return out;
        }
    }

    public interface Holder {
        Path gunfeel$path();

        void gunfeel$path(Path path);
    }

    // the beams this is about: not the battery socket's discharge (port: in 26.2 that is not an EntityBulletBeamBase)
    public static boolean covers(EntityBulletBeamBase beam) {
        return beam.config == null || beam.config != TileEntityBatterySocket.discharge;
    }

    // a beam with an impact of its own (the Tesla Cannon's blast, the infrared's fire, the shredder's burst) goes off on
    // the first block that stops it and pierces nothing, as an explosive bullet (BulletServer.clip, MixinBulletServer)
    static boolean plain(BulletConfig config) {
        return config != null && (config.onImpactBeam == BulletConfig.LAMBDA_STANDARD_BEAM_HIT || config.onImpactBeam == BulletConfig.LAMBDA_BEAM_HIT);
    }

    // the first block that stops the beam on its way from `from` to `to`, else null (NTM's func_147447_a in
    // performHitscan). Blocks pierced on the way are paid for at once and worn; their places are kept in the beam's Path.
    // The way ends at the first creature or coin NTM will give the beam to (its own search after this one, the same
    // boxes): what lies behind it is not worn, broken or paid for
    public static MovingObjectPosition clip(EntityBulletBeamBase beam, World level, Vec3 from, Vec3 to) {
        Path path = new Path(from.xCoord, from.yCoord, from.zCoord, beam.damage);
        ((Holder) beam).gunfeel$path(path);
        BlockRay.Hit water = THROUGH_WATER ? null : water(level, from, to);
        Vec3 end = water == null ? to : Vec3.createVectorHelper(water.ax, water.ay, water.az);
        double length = from.distanceTo(end), met = met(beam, level, from, to);
        if (met < length) {
            double l = from.distanceTo(to);
            end = Vec3.createVectorHelper(from.xCoord + (to.xCoord - from.xCoord) * met / l, from.yCoord + (to.yCoord - from.yCoord) * met / l,
                    from.zCoord + (to.zCoord - from.zCoord) * met / l);
        }
        BlockRay.Hit hit = blocks(beam, level, from, end, path);
        // water stops it where nothing came before (NTM looks no further than a block it got back)
        if (hit == null && water != null && !(met < length)) hit = water;
        return hit == null ? null : new MovingObjectPosition(hit.x, hit.y, hit.z, hit.face, Vec3.createVectorHelper(hit.ax, hit.ay, hit.az));
    }

    // how far the beam goes to the first creature or coin that takes it (NTM's search in performHitscan: the coin
    // always, a creature when the beam does not go through creatures), else infinity
    @SuppressWarnings("unchecked")
    private static double met(EntityBulletBeamBase beam, World level, Vec3 from, Vec3 to) {
        if (!beam.doesImpactEntities()) return Double.POSITIVE_INFINITY;
        AxisAlignedBB area = beam.boundingBox.addCoord(to.xCoord - from.xCoord, to.yCoord - from.yCoord, to.zCoord - from.zCoord).expand(1.0, 1.0, 1.0);
        List<Entity> list = ModelHits.candidates(level, beam, (List<Entity>) level.getEntitiesWithinAABBExcludingEntity(beam, area), area, e -> true);
        boolean through = beam.doesPenetrate();
        double best = Double.POSITIVE_INFINITY;
        for (Entity e : list) {
            boolean coin = e instanceof EntityCoin;
            if (coin ? e.isDead : through || !e.canBeCollidedWith() || e == beam.thrower || !e.isEntityAlive()) continue;
            MovingObjectPosition at = ModelAABB.of(e).expand(0.3, 0.3, 0.3).calculateIntercept(from, to);
            if (at != null && at.hitVec != null) best = Math.min(best, from.distanceTo(at.hitVec));
        }
        return best;
    }

    private static BlockRay.Hit blocks(EntityBulletBeamBase beam, World level, Vec3 from, Vec3 to, Path path) {
        if (!plain(beam.config)) {
            // it wears and breaks the block as a bullet would, and still goes off there (an explosive bullet)
            BlockRay.Hit hit = PassableClip.clip(level, from, to, h -> BulletServer.wearBy(level, beam.getEntityId(), beam.getThrower(), beam.damage, h));
            if (hit != null) BulletServer.blockHit(level, beam.getEntityId(), beam.getThrower(), beam.damage, hit.x, hit.y, hit.z, hit.ax, hit.ay, hit.az);
            return hit;
        }
        return walk(level, beam.getEntityId(), beam.getThrower(), from, to, beam.config, path, true, null);
    }

    // a block of the shooter itself (a turret's), gone through as air
    public interface Own {
        boolean test(int x, int y, int z);
    }

    // a beam's way through blocks from `from` to `to` by the bullet rules, with the damage the path starts with: the
    // block that stops it, else null. The blocks it pierces go into the path with the damage left after each. apply:
    // blocks are worn and broken (the shot), else only looked at (who is in sight: a block that would break under it
    // stops it). own: blocks of the shooter itself, gone through as air
    private static BlockRay.Hit walk(World level, int id, Entity owner, Vec3 from, Vec3 to, BulletConfig config, Path path, boolean apply, Own own) {
        Consumer<BlockRay.Hit> through = apply ? hit -> BulletServer.wearBy(level, id, owner, path.at(hit.ax, hit.ay, hit.az), hit) : hit -> {
        };
        Materials.Penetration penetration = new Materials.Penetration();
        // blocks it already pierced (a fence's post and then its rail): paid for once, as a bullet's
        Set<Long> paid = new HashSet<>();
        float damage = path.damage;
        Vec3 at = from;
        // a bullet goes on next tick, a beam has one go: more blocks on its way than a bullet's tick
        for (int i = 0; i < 64; i++) {
            BlockRay.Hit hit = PassableClip.clip(level, at, to, through);
            if (hit == null) return null;
            double dx = to.xCoord - at.xCoord, dy = to.yCoord - at.yCoord, dz = to.zCoord - at.zCoord;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-6) return hit;
            dx /= len;
            dy /= len;
            dz /= len;
            BlockRay.Hit out = PassableClip.exit(level, hit, dx, dy, dz);
            long pos = BulletServer.key(hit.x, hit.y, hit.z);
            boolean once = PassableClip.paidOnce(hit);
            if (own != null && own.test(hit.x, hit.y, hit.z) || out != null && once && paid.contains(pos)) {
                double px = out != null ? out.ax : hit.ax + dx * 0.05, py = out != null ? out.ay : hit.ay + dy * 0.05,
                        pz = out != null ? out.az : hit.az + dz * 0.05;
                if (dist2(px, py, pz, at) >= len * len) return null;
                at = Vec3.createVectorHelper(px + dx * 1e-3, py + dy * 1e-3, pz + dz * 1e-3);
                continue;
            }
            float left = out == null ? -1F : PassableClip.pierced(level, hit, out, damage, config, penetration);
            if (left >= 0F) {
                if (once) paid.add(pos);
                if (apply) BulletServer.wearBy(level, id, owner, damage, hit);
                if (TestLog.server && apply) TestLog.s(id, "beam_block", "block", TestLog.block(level, hit.x, hit.y, hit.z), "pos", TestLog.pos(hit),
                        "at", TestLog.point(hit), "result", "pierce", "damage", damage, "left", left);
                path.steps.add(new double[] {path.from(hit.ax, hit.ay, hit.az), left});
                damage = left;
                if (out.distanceSq(at.xCoord, at.yCoord, at.zCoord) >= len * len) return null;
                at = Vec3.createVectorHelper(out.ax + dx * 1e-3, out.ay + dy * 1e-3, out.az + dz * 1e-3);
                continue;
            }
            if (!apply) return hit;
            // it does not pierce it: a block whose break rule lets the shot on ("pass") breaks and the beam goes on
            String was = TestLog.server ? TestLog.block(level, hit.x, hit.y, hit.z) : null;
            int got = BulletServer.blockHit(level, id, owner, damage, hit.x, hit.y, hit.z, hit.ax, hit.ay, hit.az);
            if (TestLog.server) TestLog.s(id, "beam_block", "block", was, "pos", TestLog.pos(hit), "at", TestLog.point(hit),
                    "result", got == 2 ? "broke_pass" : got == 1 ? "broke" : "stop", "damage", damage);
            if (got != 2) return hit;
            at = Vec3.createVectorHelper(hit.ax + dx * 1e-3, hit.ay + dy * 1e-3, hit.az + dz * 1e-3);
        }
        return null;
    }

    private static double dist2(double x, double y, double z, Vec3 v) {
        double dx = x - v.xCoord, dy = y - v.yCoord, dz = z - v.zCoord;
        return dx * dx + dy * dy + dz * dz;
    }

    // a beam with no entity of its own, straight from `from` to its target's point `to` (a turret's, a Tesla coil's
    // arc): the damage it has there, or -1 when a block (or water, with THROUGH_WATER off) stops it on the way. round:
    // its threshold negation and armour piercing (a Tauon's tau round), null: none. apply: the shot (blocks on the way
    // are worn and broken), else only whether the target is in sight
    public static float reach(World level, Entity owner, Vec3 from, Vec3 to, float damage, BulletConfig round, boolean apply, Own own) {
        if (!THROUGH_WATER && water(level, from, to) != null) return -1F;
        Path path = new Path(from.xCoord, from.yCoord, from.zCoord, damage);
        BlockRay.Hit hit = walk(level, -1, owner, from, to, round, path, apply, own);
        return hit == null ? path.at(to.xCoord, to.yCoord, to.zCoord) : -1F;
    }

    // the beam a coin sends on (NTM: x1.25 of this one's damage): x1.25 of what this one has at the coin
    public static void reflected(EntityBulletBeamBase beam, EntityBulletBeamBase next, double x, double y, double z) {
        Path path = ((Holder) beam).gunfeel$path();
        if (path != null) next.damage = path.at(x, y, z) * 1.25F;
    }

    // the first water on the way, else null (blocks are not looked at here)
    private static BlockRay.Hit water(World level, Vec3 from, Vec3 to) {
        final double[] box = new double[6];
        return BlockRay.walk(from.xCoord, from.yCoord, from.zCoord, to.xCoord, to.yCoord, to.zCoord, (x, y, z) -> {
            if (level.getBlock(x, y, z).getMaterial() != Material.water) return null;
            box[0] = x;
            box[1] = y;
            box[2] = z;
            box[3] = x + 1;
            box[4] = y + 1;
            box[5] = z + 1;
            return BlockRay.clip(box, 1, from.xCoord, from.yCoord, from.zCoord, to.xCoord, to.yCoord, to.zCoord, x, y, z);
        });
    }

    // the damage the beam has where it meets (x, y, z), else its own (no way kept: NTM's beam)
    public static float damageAt(EntityBulletBeamBase beam, double x, double y, double z) {
        Path path = ((Holder) beam).gunfeel$path();
        return path == null ? beam.damage : path.at(x, y, z);
    }
}
