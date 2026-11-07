package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.hbm.entity.projectile.EntityBulletBeamBase;
import com.hbm.entity.projectile.EntityCoin;
import com.hbm.items.weapon.sedna.BulletConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
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
// takes the beam are not touched. In water it loses damage and is gone after a few blocks (BeamWater); a laser goes
// through glass as through air (BeamLasers). With beamRules off (server settings) it is NTM's beam as it is.
public final class BeamServer {
    private BeamServer() {
    }

    public static final String KEY = "beamRules";

    public static boolean on() {
        return ReaimTimes.flag(KEY, true);
    }

    // the beam's way this shot: where it entered each block it pierced and its damage after it, and the water on it
    public static final class Path {
        final Vec3 from;
        final float damage;
        final BeamWater water;
        // where it went out in the water: NTM's stop there, which hits nothing (MixinBeamBlocks), else null
        @Nullable BlockHitResult gone;
        // where it entered each block it pierced, and its damage after it as if there were no water on the way (the
        // water's share is put on where it is asked)
        private final List<double[]> steps = new ArrayList<>();

        Path(Vec3 from, float damage, BeamWater water) {
            this.from = from;
            this.damage = damage;
            this.water = water;
        }

        // the damage the beam has `d` blocks on its way: what it had less what the blocks before took, times what the
        // water before leaves
        float at(double d) {
            float out = damage;
            for (double[] s : steps)
                if (s[0] < d) out = (float) s[1];
            return (float) (out * water.k(d));
        }

        // the damage the beam has at `at` (on its way)
        public float at(Vec3 at) {
            return at(at.distanceTo(from));
        }

        // the stop where it went out in the water (no impact there)
        public boolean gone(HitResult hit) {
            return gone != null && hit == gone;
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
    // boxes): what lies behind it is not worn, broken or paid for. Gone in water before anything: a stop there (NTM
    // ends the beam and looks for creatures no further), with no impact (Path.gone)
    public static BlockHitResult clip(EntityBulletBeamBase beam, Level level, Vec3 from, Vec3 to) {
        double length = from.distanceTo(to), met = met(beam, level, from, to);
        // the water up to the creature or coin that takes it: none past it counts
        BeamWater water = BeamWater.along(level, from, met < length ? from.add(to.subtract(from).normalize().scale(met)) : to);
        Path path = new Path(from, beam.damage, water);
        ((Holder) beam).gunfeel$path(path);
        double gone = water.gone();
        double cut = Math.min(length, Math.min(met, gone));
        Vec3 end = cut < length ? from.add(to.subtract(from).normalize().scale(cut)) : to;
        BlockHitResult hit = blocks(beam, level, from, end, path);
        if (hit.getType() != HitResult.Type.MISS) return hit;
        if (gone < length && gone <= met) {
            path.gone = new BlockHitResult(end, hit.getDirection(), BlockPos.containing(end), false);
            return path.gone;
        }
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
        boolean laser = BeamLasers.throughGlass(beam.config);
        if (!plain(beam.config)) {
            // it wears and breaks the block as a bullet would, and still goes off there (an explosive bullet); a laser
            // goes on through glass first
            Vec3 at = from;
            for (int i = 0; i < STEPS; i++) {
                BlockHitResult hit = PassableClip.clip(level, at, to, through(level, beam.getId(), beam.getThrower(), path, laser));
                if (hit.getType() != HitResult.Type.BLOCK) return hit;
                if (laser && BeamLasers.glass(level, hit)) {
                    Vec3 past = past(level, hit, to.subtract(at).normalize());
                    if (past.distanceToSqr(at) >= to.distanceToSqr(at)) return BlockHitResult.miss(to, hit.getDirection(), BlockPos.containing(to));
                    at = past;
                    continue;
                }
                BulletServer.blockHit(beam, beam.getThrower(), path.at(hit.getLocation()), hit);
                return hit;
            }
            return PassableClip.clip(level, at, to);
        }
        return walk(level, beam.getId(), beam.getThrower(), from, to, beam.config, path, true, null, laser);
    }

    // what it wears of the blocks it flies through (bullet rules "through"), with the damage it has there; a laser
    // leaves glass alone
    private static java.util.function.Consumer<BlockHitResult> through(Level level, int id, @Nullable Entity owner, Path path, boolean laser) {
        return hit -> {
            if (laser && BeamLasers.glass(level, hit)) return;
            BulletServer.wearBy(level, id, owner, path.at(hit.getLocation()), hit);
        };
    }

    // just past the block it hit, going along `dir` (unit): out of its shape, else a little on
    private static Vec3 past(Level level, BlockHitResult hit, Vec3 dir) {
        BlockHitResult out = PassableClip.exit(level, hit, dir);
        Vec3 p = out != null ? out.getLocation() : hit.getLocation().add(dir.scale(0.05));
        return p.add(dir.scale(1e-3));
    }

    // a beam's way through blocks from `from` to `to` by the bullet rules, with the damage the path starts with: the
    // block that stops it, else a miss. The blocks it pierces go into the path with the damage left after each.
    // apply: blocks are worn and broken (the shot), else only looked at (who is in sight: a block that would break
    // under it stops it). own: blocks of the shooter itself (a turret's), gone through as air. laser: glass is gone
    // through as air too (BeamLasers). The damage it meets a block with is less what the water before took (BeamWater)
    private static BlockHitResult walk(Level level, int id, @Nullable Entity owner, Vec3 from, Vec3 to, @Nullable BulletConfig config, Path path,
            boolean apply, java.util.function.@Nullable Predicate<BlockPos> own, boolean laser) {
        java.util.function.Consumer<BlockHitResult> through = apply ? through(level, id, owner, path, laser) : hit -> {
        };
        Materials.Penetration penetration = new Materials.Penetration();
        // blocks it already pierced (a fence's post and then its rail): paid for once, as a bullet's
        java.util.Set<BlockPos> paid = new java.util.HashSet<>();
        // as if there were no water on the way (Path's steps)
        float damage = path.damage;
        Vec3 at = from;
        @Nullable BlockHitResult last = null;
        // a bullet goes on next tick, a beam has one go: more blocks on its way than a bullet's tick
        for (int i = 0; i < STEPS; i++) {
            BlockHitResult hit = PassableClip.clip(level, at, to, through);
            if (hit.getType() == HitResult.Type.MISS) return hit;
            last = hit;
            Vec3 d = to.subtract(at);
            double len = d.length();
            if (len < 1e-6) return hit;
            Vec3 dir = d.scale(1.0 / len);
            BlockHitResult out = PassableClip.exit(level, hit, dir);
            BlockPos pos = hit.getBlockPos().immutable();
            boolean once = PassableClip.paidOnce(hit);
            if (own != null && own.test(pos) || out != null && once && paid.contains(pos) || laser && BeamLasers.glass(level, hit)) {
                Vec3 past = out != null ? out.getLocation() : hit.getLocation().add(dir.scale(0.05));
                if (past.distanceToSqr(at) >= len * len) return BlockHitResult.miss(to, hit.getDirection(), BlockPos.containing(to));
                at = past.add(dir.scale(1e-3));
                continue;
            }
            double t = hit.getLocation().distanceTo(path.from), k = path.water.k(t);
            // what it has here, the water's share on it
            float has = (float) (damage * k);
            float left = out == null ? -1F : PassableClip.pierced(level, hit, out, has, config, penetration);
            if (left >= 0F) {
                if (once) paid.add(pos);
                if (apply) BulletServer.wearBy(level, id, owner, has, hit);
                if (TestLog.server && apply) TestLog.s(id, "beam_block", "block", level.getBlockState(hit.getBlockPos()), "pos", hit.getBlockPos(),
                        "at", hit.getLocation(), "result", "pierce", "damage", has, "left", left);
                damage = (float) (left / k);
                path.steps.add(new double[] {t, damage});
                if (out.getLocation().distanceToSqr(at) >= len * len) return BlockHitResult.miss(to, hit.getDirection(), BlockPos.containing(to));
                at = out.getLocation().add(dir.scale(1e-3));
                continue;
            }
            if (!apply) return hit;
            // it does not pierce it: a block whose break rule lets the shot on ("pass") breaks and the beam goes on
            int got = BulletServer.blockHit(level, id, owner, has, hit);
            if (TestLog.server) TestLog.s(id, "beam_block", "block", level.getBlockState(hit.getBlockPos()), "pos", hit.getBlockPos(),
                    "at", hit.getLocation(), "result", got == 2 ? "broke_pass" : got == 1 ? "broke" : "stop", "damage", has);
            if (got != 2) return hit;
            at = hit.getLocation().add(dir.scale(1e-3));
        }
        // more on its way: it stops where it met the last, not flies on through the rest unlooked at (a look only: what it
        // would fly through past the last is not worn); the way clear after them: a miss, through what it flies through
        if (last != null && PassableClip.clip(level, at, to).getType() != HitResult.Type.MISS) return last;
        return PassableClip.clip(level, at, to, through);
    }

    // the most times a beam meets a block on its way (pierced, its shooter's own, broken and gone through; a fence's
    // post and rail are two)
    static final int STEPS = 64;

    // a beam with no entity of its own, straight from `from` to its target's point `to` (a turret's, a Tesla coil's
    // arc): the damage it has there, or -1 when a block stops it on the way or it is gone in water before it gets there
    // (BeamWater). round: its threshold negation and armour piercing (a Tauon's tau round), null: none. apply: the shot
    // (blocks on the way are worn and broken), else only whether the target is in sight
    public static float reach(Level level, @Nullable Entity owner, Vec3 from, Vec3 to, float damage, @Nullable BulletConfig round, boolean apply,
            java.util.function.@Nullable Predicate<BlockPos> own) {
        return reach(level, owner, from, to, damage, round, apply, own, false);
    }

    // laser: the beam goes through glass (a Maxwell's maser, BeamLasers)
    public static float reach(Level level, @Nullable Entity owner, Vec3 from, Vec3 to, float damage, @Nullable BulletConfig round, boolean apply,
            java.util.function.@Nullable Predicate<BlockPos> own, boolean laser) {
        BeamWater water = BeamWater.along(level, from, to);
        double length = from.distanceTo(to), gone = water.gone();
        Path path = new Path(from, damage, water);
        // gone in the water: the blocks up to there are still met (worn, with apply), the target is not reached
        Vec3 end = gone < length ? from.add(to.subtract(from).normalize().scale(gone)) : to;
        BlockHitResult hit = walk(level, -1, owner, from, end, round, path, apply, own, laser && BeamLasers.on());
        return hit.getType() == HitResult.Type.MISS && !(gone < length) ? path.at(to) : -1F;
    }

    // the beam a coin sends on (NTM: x1.25 of this one's damage): x1.25 of what this one has at the coin
    public static void reflected(EntityBulletBeamBase beam, EntityBulletBeamBase next, Vec3 coin) {
        Path path = ((Holder) beam).gunfeel$path();
        if (path != null) next.damage = path.at(coin) * 1.25F;
    }

    // the damage the beam has where it meets `at`, else its own (no way kept: NTM's beam)
    public static float damageAt(EntityBulletBeamBase beam, Vec3 at) {
        Path path = ((Holder) beam).gunfeel$path();
        return path == null ? beam.damage : path.at(at);
    }
}
