package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.hbm.entity.projectile.EntityBulletBeamBase;
import com.hbm.entity.projectile.EntityCoin;
import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// NTM's beams (EntityBulletBeamBase: the laser guns, the Tesla Cannon, Lacunae, Tau, the N.I.4.N.I., the Aberrator,
// shredder shells, the laser grenade; not the spectral ones, Folly and Tau's charged shot, which NTM sends through
// blocks) on the server go through blocks as a bullet of the same damage would (MixinBeamBlocks): what a bullet flies
// through (a fence's gap, a machine's empty cell) the beam does, what it pierces (glass, thin wood) the beam pierces
// with the same loss, what a bullet wears and breaks the beam wears and breaks. It leaves no hole. NTM stops it on the
// first block with a box. The beam's end (BEAM_LENGTH) is where it stops here, and the client draws it to there. A
// creature behind a pierced block gets the damage the beam has there; blocks behind the creature or the coin that
// takes the beam are not touched. With beamRules off (server settings) it is NTM's beam as it is.
public final class BeamServer {
    private BeamServer() {
    }

    public static final String KEY = "beamRules";

    // NTM's beams go through water (ClipContext.Fluid.NONE); false: water stops them where they meet it, as a block
    // with nothing behind it. The one place for that choice
    static final boolean THROUGH_WATER = true;

    public static boolean on() {
        return ReaimTimes.flag(KEY, true);
    }

    // the beam's way this shot: where it entered each block it pierced and its damage after it
    public static final class Path {
        private final Vec3 from;
        private final float damage;
        private final List<double[]> steps = new ArrayList<>();

        Path(Vec3 from, float damage) {
            this.from = from;
            this.damage = damage;
        }

        // the damage the beam has at `at` (on its way): what it had less what the blocks before took
        public float at(Vec3 at) {
            double d = at.distanceTo(from);
            float out = damage;
            for (double[] s : steps)
                if (s[0] < d) out = (float) s[1];
            return out;
        }
    }

    public interface Holder {
        @Nullable Path gunfeel$path();

        void gunfeel$path(@Nullable Path path);
    }

    // a beam with an impact of its own (the Tesla Cannon's blast, the infrared's fire, the shredder's burst) goes off on
    // the first block that stops it and pierces nothing, as an explosive bullet (BulletServer.clip, MixinBulletServer)
    static boolean plain(@Nullable BulletConfig config) {
        return config != null && (config.onImpactBeam == BulletConfig.LAMBDA_STANDARD_BEAM_HIT || config.onImpactBeam == BulletConfig.LAMBDA_BEAM_HIT);
    }

    // the first block that stops the beam on its way from `from` to `to`, else a miss at `to` (NTM's level.clip in
    // performHitscan). Blocks pierced on the way are paid for at once and worn; their places are kept in the beam's Path.
    // The way ends at the first creature or coin NTM will give the beam to (its own search after this one, the same
    // boxes): what lies behind it is not worn, broken or paid for
    public static BlockHitResult clip(EntityBulletBeamBase beam, Level level, Vec3 from, Vec3 to) {
        Path path = new Path(from, beam.damage);
        ((Holder) beam).gunfeel$path(path);
        BlockHitResult water = THROUGH_WATER ? null : water(level, from, to, beam);
        Vec3 end = water == null ? to : water.getLocation();
        double met = met(beam, level, from, to), length = from.distanceTo(end);
        if (met < length) end = from.add(to.subtract(from).normalize().scale(met));
        BlockHitResult hit = blocks(beam, level, from, end, path);
        if (hit.getType() != HitResult.Type.MISS) return hit;
        // water stops it where nothing came before (NTM looks no further than a block it got back)
        if (water != null && !(met < length)) return water;
        return BlockHitResult.miss(to, hit.getDirection(), BlockPos.containing(to));
    }

    // how far the beam goes to the first creature or coin that takes it (NTM's search in performHitscan: the coin
    // always, a creature when the beam does not go through creatures), else infinity
    private static double met(EntityBulletBeamBase beam, Level level, Vec3 from, Vec3 to) {
        if (!beam.doesImpactEntities()) return Double.POSITIVE_INFINITY;
        Vec3 h = to.subtract(from);
        AABB area = beam.getBoundingBox().expandTowards(h.x, h.y, h.z).inflate(1.0);
        boolean through = beam.doesPenetrate();
        double best = Double.POSITIVE_INFINITY;
        for (Entity e : ModelHits.candidates(level, beam, level.getEntities(beam, area), area, x -> true)) {
            if (!e.isAlive()) continue;
            if (!(e instanceof EntityCoin) && (through || !e.isPickable() || e == beam.thrower)) continue;
            var at = e.getBoundingBox().inflate(0.3).clip(from, to);
            if (at.isPresent()) best = Math.min(best, from.distanceTo(at.get()));
        }
        return best;
    }

    private static BlockHitResult blocks(EntityBulletBeamBase beam, Level level, Vec3 from, Vec3 to, Path path) {
        java.util.function.Consumer<BlockHitResult> through = hit -> BulletServer.wearBy(beam, beam.getThrower(), path.at(hit.getLocation()), hit);
        if (!plain(beam.config)) {
            // it wears and breaks the block as a bullet would, and still goes off there (an explosive bullet)
            BlockHitResult hit = PassableClip.clip(level, from, to, through);
            if (hit.getType() == HitResult.Type.BLOCK) BulletServer.blockHit(beam, beam.getThrower(), beam.damage, hit);
            return hit;
        }
        Materials.Penetration penetration = new Materials.Penetration();
        // blocks it already pierced (a fence's post and then its rail): paid for once, as a bullet's
        java.util.Set<BlockPos> paid = new java.util.HashSet<>();
        float damage = beam.damage;
        Vec3 at = from;
        // a bullet goes on next tick, a beam has one go: more blocks on its way than a bullet's tick
        for (int i = 0; i < 64; i++) {
            BlockHitResult hit = PassableClip.clip(level, at, to, through);
            if (hit.getType() == HitResult.Type.MISS) return hit;
            Vec3 d = to.subtract(at);
            double len = d.length();
            if (len < 1e-6) return hit;
            Vec3 dir = d.scale(1.0 / len);
            BlockHitResult out = PassableClip.exit(level, hit, dir);
            BlockPos pos = hit.getBlockPos().immutable();
            boolean once = PassableClip.paidOnce(hit);
            if (out != null && once && paid.contains(pos)) {
                if (out.getLocation().distanceToSqr(at) >= len * len) return BlockHitResult.miss(to, hit.getDirection(), BlockPos.containing(to));
                at = out.getLocation().add(dir.scale(1e-3));
                continue;
            }
            float left = out == null ? -1F : PassableClip.pierced(level, hit, out, damage, beam.config, penetration);
            if (left >= 0F) {
                if (once) paid.add(pos);
                BulletServer.wearBy(beam, beam.getThrower(), damage, hit);
                if (TestLog.server) TestLog.s(beam.getId(), "beam_block", "block", level.getBlockState(hit.getBlockPos()), "pos", hit.getBlockPos(),
                        "at", hit.getLocation(), "result", "pierce", "damage", damage, "left", left);
                path.steps.add(new double[] {hit.getLocation().distanceTo(from), left});
                damage = left;
                if (out.getLocation().distanceToSqr(at) >= len * len) return BlockHitResult.miss(to, hit.getDirection(), BlockPos.containing(to));
                at = out.getLocation().add(dir.scale(1e-3));
                continue;
            }
            // it does not pierce it: a block whose break rule lets the shot on ("pass") breaks and the beam goes on
            int got = BulletServer.blockHit(beam, beam.getThrower(), damage, hit);
            if (TestLog.server) TestLog.s(beam.getId(), "beam_block", "block", level.getBlockState(hit.getBlockPos()), "pos", hit.getBlockPos(),
                    "at", hit.getLocation(), "result", got == 2 ? "broke_pass" : got == 1 ? "broke" : "stop", "damage", damage);
            if (got != 2) return hit;
            at = hit.getLocation().add(dir.scale(1e-3));
        }
        return BlockHitResult.miss(to, net.minecraft.core.Direction.UP, BlockPos.containing(to));
    }

    // the beam a coin sends on (NTM: x1.25 of this one's damage): x1.25 of what this one has at the coin
    public static void reflected(EntityBulletBeamBase beam, EntityBulletBeamBase next, Vec3 coin) {
        Path path = ((Holder) beam).gunfeel$path();
        if (path != null) next.damage = path.at(coin) * 1.25F;
    }

    // the first water on the way, else null (blocks are not looked at here)
    private static @Nullable BlockHitResult water(Level level, Vec3 from, Vec3 to, EntityBulletBeamBase beam) {
        BlockHitResult w = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.WATER, beam) {
            @Override
            public net.minecraft.world.phys.shapes.VoxelShape getBlockShape(net.minecraft.world.level.block.state.BlockState state,
                    net.minecraft.world.level.BlockGetter getter, BlockPos pos) {
                return net.minecraft.world.phys.shapes.Shapes.empty();
            }
        });
        return w.getType() == HitResult.Type.MISS ? null : w;
    }

    // the damage the beam has where it meets `at`, else its own (no way kept: NTM's beam)
    public static float damageAt(EntityBulletBeamBase beam, Vec3 at) {
        Path path = ((Holder) beam).gunfeel$path();
        return path == null ? beam.damage : path.at(at);
    }
}
