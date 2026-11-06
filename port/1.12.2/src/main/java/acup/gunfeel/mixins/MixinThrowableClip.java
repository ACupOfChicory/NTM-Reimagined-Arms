package acup.gunfeel.mixins;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.entity.projectile.EntityThrowableNT;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.BlockRay;
import acup.gunfeel.ModelAABB;
import acup.gunfeel.ModelHits;
import acup.gunfeel.BulletServer;
import acup.gunfeel.PassableClip;
import acup.gunfeel.water.WaterTick;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

// 26.2: MixinThrowableHit (0762086), the bullets' part: their path through blocks and point-blank hits. (Its other
// part, the mobs' hitboxes from their models, is the hitboxes' port; both wrap the same calls with WrapOperation, so
// they chain.) NTM's EntityThrowableNT.onUpdate asks for blocks, then for mobs; NTM's bullet ticks there only on the
// server (the client's EntityThrowableInterp moves it by the server's updates).
// port: NTM CE asks Library.rayTraceBlocks for blocks (1.7.10: World.func_147447_a), and for mobs
// Library.rayTraceEntities (its own walk over the chunks' entity lists, the nearest box hit) or, for a bullet that
// pierces mobs, every mob near (getEntitiesWithinAABBExcludingEntity, calculateIntercept) as 1.7.10 did.
//  - Blocks: a bullet's path goes through the mod's own walk (BlockRay, PassableClip) and piercing (BulletServer.clip)
//    instead: 26.2's cells and collision boxes, no 200-cell limit.
//  - Point-blank: NTM's bullet starts a little in front of the shooter's eyes, and a hitbox that already contains the
//    start of the path is never hit (calculateIntercept misses a segment starting inside). On the bullet's first tick
//    its path starts at the shooter's eyes (blocks and mobs in between count), and a path starting inside a hitbox hits it.
//  - Mobs: rayTraceEntities is answered here for NTM's bullets with the mobs' model boxes (ModelAABB), the same
//    filters as NTM's (not the bullet, spectators, the filter it is given, mobs that cannot be hit).
//  - A trace (-Dntmgunfeel.trace): NTM's spread draws from the new bullet's own random; seeded from the trace's seed by
//    the order of the shots, so every run fires the same bullets.
@Mixin(value = EntityThrowableNT.class, remap = false)
public abstract class MixinThrowableClip extends Entity {
    private MixinThrowableClip(World world) {
        super(world);
    }

    // blocks the bullet pierced (bullet rules "pierce"), kept between ticks: one it stopped inside of is not paid twice
    @Unique
    private Set<Long> gunfeel$pierced;

    @Shadow
    protected abstract double motionMult();

    // the mobs' hitboxes from their models (26.2: MixinThrowableHit's other part): while the bullet's tick runs on the
    // server, rays are checked against the pose its shooter sees (ModelHits), NTM's entity.getEntityBoundingBox() in the hit loop is
    // the model's box (ModelAABB: its calculateIntercept clips against the model) and the mobs whose model sticks out of
    // the vanilla box are candidates too
    @Unique
    private ModelHits.ShotContext gunfeel$hitsPrevious;
    @Unique
    private boolean gunfeel$hitsOn;

    // the bullet has left its shooter's box (NTM's, 0.3 wider) since it was fired. A bullet water stopped before that
    // (a shot down while standing in water) is still there when NTM's guard (selfDamageDelay) is over; while it
    // sinks in its shooter, the shooter is no target: NTM's own test never hits a box from inside, and the
    // point-blank rule below is for the mobs in front. It goes off on the bottom, or NTM's life of it ends.
    @Unique
    private boolean gunfeel$outOfShooter;
    // where the bullet was at the tick's start: the path WaterImpact.touch looks along
    @Unique
    private Vec3d gunfeel$before;

    @Unique
    private EntityLivingBase gunfeel$inShooter() {
        if (gunfeel$outOfShooter || !((Object) this instanceof EntityBulletBaseMK4) || !acup.gunfeel.water.WaterBullet.stopped(this)) return null;
        return ((EntityThrowableNT) (Object) this).getThrower();
    }

    @Unique
    private static boolean gunfeel$ofShooter(Entity e, EntityLivingBase shooter) {
        return e == shooter || acup.gunfeel.ModelPartEntities.parent(e) == shooter;
    }

    @Inject(method = "onUpdate", at = @At("HEAD"), remap = true)
    private void gunfeel$beginHits(CallbackInfo ci) {
        if (world.isRemote) return;
        EntityThrowableNT self = (EntityThrowableNT) (Object) this;
        if (!gunfeel$outOfShooter) {
            EntityLivingBase shooter = self.getThrower();
            if (shooter == null || !shooter.getEntityBoundingBox().grow(0.3).contains(new Vec3d(posX, posY, posZ))) gunfeel$outOfShooter = true;
        }
        gunfeel$before = new Vec3d(posX, posY, posZ);
        double travel = Math.sqrt(motionX * motionX + motionY * motionY + motionZ * motionZ) * motionMult();
        gunfeel$hitsPrevious = ModelHits.beginProjectile(self.getThrower(), travel);
        gunfeel$hitsOn = true;
    }

    // water: the bullet's new f, a stop or a skip, the tick's events (WaterTick), after NTM moved it and hit what it
    // hit; an explosive touching another bullet (WaterImpact)
    @Inject(method = "onUpdate", at = @At("RETURN"), remap = true)
    private void gunfeel$waterEnd(CallbackInfo ci) {
        if (world.isRemote || !((Object) this instanceof EntityBulletBaseMK4)) return;
        EntityBulletBaseMK4 bullet = (EntityBulletBaseMK4) (Object) this;
        WaterTick.end(bullet);
        if (gunfeel$before != null) acup.gunfeel.water.WaterImpact.touch(bullet, gunfeel$before);
        gunfeel$before = null;
    }

    @Inject(method = "onUpdate", at = @At("RETURN"), remap = true)
    private void gunfeel$endHits(CallbackInfo ci) {
        if (!gunfeel$hitsOn) return;
        gunfeel$hitsOn = false;
        ModelHits.endShot(gunfeel$hitsPrevious);
        gunfeel$hitsPrevious = null;
    }

    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/entity/Entity;getEntityBoundingBox()Lnet/minecraft/util/math/AxisAlignedBB;"))
    private AxisAlignedBB gunfeel$modelBox(Entity entity, Operation<AxisAlignedBB> original) {
        if (entity == (Object) this || world.isRemote) return original.call(entity);
        return ModelAABB.of(entity);
    }

    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/world/World;getEntitiesWithinAABBExcludingEntity(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/AxisAlignedBB;)Ljava/util/List;"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List gunfeel$candidates(World world, Entity except, AxisAlignedBB area, Operation<List> original) {
        List list = original.call(world, except, area);
        if (world.isRemote) return list;
        EntityLivingBase shooter = gunfeel$inShooter();
        if (shooter == null) return ModelHits.candidates(world, except, (List<Entity>) list, area, e -> true);
        List<Entity> mobs = new java.util.ArrayList<Entity>((List<Entity>) list);
        mobs.removeIf(e -> gunfeel$ofShooter(e, shooter));
        return ModelHits.candidates(world, except, mobs, area, e -> !gunfeel$ofShooter(e, shooter));
    }

    @Inject(method = "shoot", at = @At("HEAD"), remap = true)
    private void gunfeel$traceSpread(double x, double y, double z, float velocity, float inaccuracy, CallbackInfo ci) {
        if (acup.gunfeel.Trace.ON && !world.isRemote) rand.setSeed(acup.gunfeel.Rng.spread());
    }

    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = false,
            target = "Lcom/hbm/lib/Library;rayTraceBlocks(Lnet/minecraft/world/World;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;ZZZ)Lnet/minecraft/util/math/RayTraceResult;"))
    private RayTraceResult gunfeel$blocks(World world, Vec3d from, Vec3d to, boolean liquids, boolean noBox, boolean last,
            Operation<RayTraceResult> original) {
        if (!((Object) this instanceof EntityBulletBaseMK4)) return original.call(world, from, to, liquids, noBox, last);
        EntityBulletBaseMK4 bullet = (EntityBulletBaseMK4) (Object) this;
        Vec3d start = gunfeel$start(from);
        // water first: blocks past where it stops or skips the bullet are not met (WaterTick)
        to = WaterTick.path(bullet, world, start, to);
        BlockRay.Hit hit;
        if (world.isRemote) {
            hit = PassableClip.clip(world, start, to);
        } else {
            if (gunfeel$pierced == null) gunfeel$pierced = new HashSet<Long>();
            hit = BulletServer.clip(bullet, world, start, to, gunfeel$pierced);
        }
        // an impact fuze from the air goes off on the water's surface: NTM gets the surface's hit (WaterTick)
        return WaterTick.blocks(bullet, hit == null ? null : acup.gunfeel.Mc.blockHit(hit.x, hit.y, hit.z, hit.face, new Vec3d(hit.ax, hit.ay, hit.az)));
    }

    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/util/math/AxisAlignedBB;calculateIntercept(Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;)Lnet/minecraft/util/math/RayTraceResult;"))
    private RayTraceResult gunfeel$mob(AxisAlignedBB box, Vec3d from, Vec3d to, Operation<RayTraceResult> original) {
        if (!((Object) this instanceof EntityBulletBaseMK4)) return original.call(box, from, to);
        // not past where water stopped or turned the bullet
        to = WaterTick.mobsTo(this, to);
        Vec3d start = gunfeel$start(from);
        // 26.2's AABB.contains: min inclusive, max exclusive
        if (start.x >= box.minX && start.x < box.maxX && start.y >= box.minY && start.y < box.maxY
                && start.z >= box.minZ && start.z < box.maxZ)
            return new RayTraceResult(new Vec3d(start.x, start.y, start.z), net.minecraft.util.EnumFacing.UP);
        return original.call(box, start, to);
    }

    // port: NTM CE's nearest mob on the path for a bullet that does not pierce mobs (Library.rayTraceEntities): for NTM's
    // bullets on the server, the mobs near the path by their model boxes (ModelHits.candidates, ModelAABB), the nearest hit
    @WrapOperation(method = "onUpdate", remap = true, at = @At(value = "INVOKE", remap = false,
            target = "Lcom/hbm/lib/Library;rayTraceEntities(Lnet/minecraft/world/World;Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;DLjava/util/function/Predicate;)Lnet/minecraft/util/math/RayTraceResult;"))
    private RayTraceResult gunfeel$nearest(World world, Entity exclude, Vec3d from, Vec3d to, double inflate,
            java.util.function.Predicate<? super Entity> filter, Operation<RayTraceResult> original) {
        if (!((Object) this instanceof EntityBulletBaseMK4) || world.isRemote) return original.call(world, exclude, from, to, inflate, filter);
        to = WaterTick.mobsTo(this, to);
        Vec3d start = gunfeel$start(from);
        double pad = World.MAX_ENTITY_RADIUS + inflate;
        AxisAlignedBB area = new AxisAlignedBB(Math.min(start.x, to.x), Math.min(start.y, to.y), Math.min(start.z, to.z),
                Math.max(start.x, to.x), Math.max(start.y, to.y), Math.max(start.z, to.z)).grow(pad);
        // a stopped round sinking inside its shooter does not hit the shooter
        EntityLivingBase shooter = gunfeel$inShooter();
        List<Entity> near = ModelHits.candidates(world, exclude, world.getEntitiesWithinAABBExcludingEntity(exclude, area), area,
                e -> shooter == null || !gunfeel$ofShooter(e, shooter));
        if (shooter != null) near.removeIf(e -> gunfeel$ofShooter(e, shooter));
        Entity best = null;
        Vec3d bestAt = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : near) {
            if (e == exclude || e instanceof net.minecraft.entity.player.EntityPlayer && ((net.minecraft.entity.player.EntityPlayer) e).isSpectator()) continue;
            if (filter != null && !filter.test(e)) continue;
            if (e.noClip || !e.canBeCollidedWith()) continue;
            AxisAlignedBB box = ModelAABB.of(e).grow(inflate);
            Vec3d at;
            if (start.x >= box.minX && start.x < box.maxX && start.y >= box.minY && start.y < box.maxY && start.z >= box.minZ && start.z < box.maxZ
                    && (!(box instanceof ModelAABB) || box.contains(start))) {
                at = start;
            } else {
                RayTraceResult r = box.calculateIntercept(start, to);
                if (r == null) continue;
                at = r.hitVec;
            }
            double d = start.squareDistanceTo(at);
            if (d < bestD) {
                bestD = d;
                best = e;
                bestAt = at;
            }
        }
        return best == null ? null : new RayTraceResult(best, bestAt);
    }

    // the path's start: on the bullet's first tick the shooter's eyes, when it was fired from them (a bullet spawned
    // elsewhere, such as the Shredder's plasma drops at the beam's end, starts where it is)
    @Unique
    private Vec3d gunfeel$start(Vec3d from) {
        EntityThrowableNT self = (EntityThrowableNT) (Object) this;
        if (((acup.gunfeel.mixins.ThrowableAccess) self).gunfeel$ticksInAir() != 1) return from;
        EntityLivingBase shooter = self.getThrower();
        if (shooter == null) return from;
        double ex = shooter.posX, ey = shooter.posY + shooter.getEyeHeight(), ez = shooter.posZ;
        double dx = ex - from.x, dy = ey - from.y, dz = ez - from.z;
        return dx * dx + dy * dy + dz * dz < 9.0 ? new Vec3d(ex, ey, ez) : from;
    }
}
