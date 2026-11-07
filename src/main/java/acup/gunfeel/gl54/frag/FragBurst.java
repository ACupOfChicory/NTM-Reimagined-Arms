package acup.gunfeel.gl54.frag;

import java.util.List;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.hbm.entity.effect.EntityFireLingering;
import com.hbm.items.weapon.sedna.DamageSourceSedna;
import com.hbm.lib.ModDamageTypes;
import com.hbm.util.DamageClass;
import com.hbm.util.EntityDamageUtil;

import acup.gunfeel.BulletServer;
import acup.gunfeel.PassableClip;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

// The server's side of a fragment burst: instant rays from the burst point (FragPattern gives their directions). A ray
// goes through creatures (each creature hit once per ray, damage per hit, by NTM's damage with the grenade's threshold
// negation and armour piercing), through leaves and what bullets fly through (PassableClip), wears the glass and other
// breakable blocks it meets by the bullet rules (BulletServer.blockHit: a fragment is a bullet of its damage) and stops
// on the first block it does not pass. At most 80 rays; after the one pass that finds the creatures near, the loop casts
// no objects of its own (each block look is the game's own clip). The client gets one packet (point, direction, seed,
// kind) and draws the tracers from it (FragFx).
public final class FragBurst {
    private FragBurst() {
    }

    // dev (the stand): told of each burst: {kind, point, nanos, creatures (LivingEntity[]), hits (int[] per creature), rays}
    public static volatile Consumer<Object[]> watch;

    private static final Consumer<BlockHitResult> FLY = h -> {
    };

    public static void burst(FragSpec spec, ServerLevel level, @Nullable Entity owner, @Nullable Entity projectile, Vec3 point, Vec3 dir, long seed) {
        long t0 = System.nanoTime();
        int rays = Math.min(spec.rays(), FragSpec.MAX_RAYS);
        double len = dir.length();
        if (len < 1e-9) dir = new Vec3(0, -1, 0);
        // the direction as the packet carries it (floats), so the client's rays are the server's
        else dir = new Vec3((float) (dir.x / len), (float) (dir.y / len), (float) (dir.z / len));
        // the creatures near, once: their boxes as numbers
        AABB area = new AABB(point, point).inflate(FragSpec.RANGE + 0.5);
        List<LivingEntity> near = level.getEntitiesOfClass(LivingEntity.class, area, e -> e.isAlive() && !e.isSpectator());
        int n = near.size();
        double[] box = new double[n * 6];
        for (int i = 0; i < n; i++) {
            AABB b = near.get(i).getBoundingBox();
            box[i * 6] = b.minX;
            box[i * 6 + 1] = b.minY;
            box[i * 6 + 2] = b.minZ;
            box[i * 6 + 3] = b.maxX;
            box[i * 6 + 4] = b.maxY;
            box[i * 6 + 5] = b.maxZ;
        }
        int[] hits = new int[n];
        DamageSource source = new DamageSourceSedna(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE)
                .getOrThrow(ModDamageTypes.forClass(DamageClass.PHYSICAL)), projectile, owner);
        int id = projectile != null ? projectile.getId() : -1;
        double[] d = new double[3];
        for (int r = 0; r < rays; r++) {
            FragPattern.direction(spec, seed, r, dir.x, dir.y, dir.z, d);
            double reach = reach(level, owner, spec, id, point, d);
            for (int i = 0; i < n; i++) {
                if (!crosses(point.x, point.y, point.z, d[0], d[1], d[2], box, i * 6, reach)) continue;
                LivingEntity target = near.get(i);
                if (!target.isAlive()) continue;
                hits[i]++;
                EntityDamageUtil.attackEntityFromNT(target, source, spec.damage(), true, true, 0.0, spec.threshold(), spec.piercing());
                if (spec.igniteTicks() > 0 && target.isAlive()) target.igniteForSeconds(spec.igniteTicks() / 20F);
            }
        }
        if (spec.igniteTicks() > 0) ground(level, owner, point);
        level.playSound(null, point.x, point.y, point.z, SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 1.6F, 1.5F);
        PacketDistributor.sendToPlayersNear(level, null, point.x, point.y, point.z, 128, new FragPayload(spec.kind(), point, dir, seed));
        var watching = watch;
        if (watching != null) watching.accept(new Object[] {spec.kind(), point, System.nanoTime() - t0, near.toArray(new LivingEntity[0]), hits, rays});
    }

    // how far the ray flies: to the first block it does not pass, else the whole range. Blocks it meets are worn by the
    // bullet rules; the ones that break and let a shot on (BulletServer.blockHit = 2) it goes through
    private static double reach(ServerLevel level, @Nullable Entity owner, FragSpec spec, int id, Vec3 point, double[] d) {
        Vec3 from = point;
        for (int step = 0; step < 4; step++) {
            double left = FragSpec.RANGE - from.distanceTo(point);
            if (left <= 1e-3) return FragSpec.RANGE;
            Vec3 to = from.add(d[0] * left, d[1] * left, d[2] * left);
            BlockHitResult hit = PassableClip.clip(level, from, to, FLY);
            if (hit.getType() == HitResult.Type.MISS) return FragSpec.RANGE;
            int got = BulletServer.blockHit(level, id, owner, spec.damage(), hit);
            if (got != 2) return hit.getLocation().distanceTo(point);
            from = hit.getLocation().add(d[0] * 1e-3, d[1] * 1e-3, d[2] * 1e-3);
        }
        return from.distanceTo(point);
    }

    // does the ray (origin, unit direction) cross the box (6 numbers at `o`) within `max`: the slab test
    static boolean crosses(double ox, double oy, double oz, double dx, double dy, double dz, double[] box, int o, double max) {
        double tmin = 0, tmax = max;
        for (int a = 0; a < 3; a++) {
            double origin = a == 0 ? ox : a == 1 ? oy : oz, dir = a == 0 ? dx : a == 1 ? dy : dz;
            double lo = box[o + a], hi = box[o + 3 + a];
            if (Math.abs(dir) < 1e-12) {
                if (origin < lo || origin > hi) return false;
            } else {
                double t1 = (lo - origin) / dir, t2 = (hi - origin) / dir;
                if (t1 > t2) {
                    double s = t1;
                    t1 = t2;
                    t2 = s;
                }
                if (t1 > tmin) tmin = t1;
                if (t2 < tmax) tmax = t2;
                if (tmin > tmax) return false;
            }
        }
        return true;
    }

    // the incendiary's fire on the floor under the burst (when it is within reach): NTM's lingering fire over a patch, and
    // fire in the air cells next to something flammable
    private static void ground(ServerLevel level, @Nullable Entity owner, Vec3 point) {
        BlockHitResult floor = level.clip(new ClipContext(point, point.add(0, -FragFireRules.FLOOR_REACH, 0), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, net.minecraft.world.phys.shapes.CollisionContext.empty()));
        if (floor.getType() != HitResult.Type.BLOCK) return;
        double y = floor.getLocation().y;
        EntityFireLingering fire = new EntityFireLingering(level).setArea(FragFireRules.WIDTH, FragFireRules.HEIGHT)
                .setDuration(FragFireRules.TICKS).setType(0);
        fire.setPos(point.x, y, point.z);
        level.addFreshEntity(fire);
        if (!(owner instanceof ServerPlayer) && !level.getGameRules().get(GameRules.MOB_GRIEFING)) return;
        BlockPos middle = BlockPos.containing(point.x, y + 0.01, point.z);
        int c = FragFireRules.CUBE;
        for (BlockPos pos : BlockPos.betweenClosed(middle.offset(-c, -c, -c), middle.offset(c, c, c))) {
            if (!level.getBlockState(pos).isAir()) continue;
            int flammable = 0;
            for (Direction dir : Direction.values()) {
                BlockPos neighbour = pos.relative(dir);
                BlockState state = level.getBlockState(neighbour);
                if (state.isFlammable(level, neighbour, dir.getOpposite())) flammable++;
            }
            if (FragFireRules.ignites(true, flammable)) level.setBlockAndUpdate(pos.immutable(), Blocks.FIRE.defaultBlockState());
        }
    }
}
