package acup.gunfeel.mixin;

import java.util.Optional;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.entity.projectile.EntityThrowableNT;

import acup.gunfeel.PassableClip;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// Server: point-blank hits. NTM's bullet starts a little in front of the shooter's eyes, and a hitbox that
// already contains the start of the bullet's path is never hit (AABB.clip misses a segment starting inside).
// On the bullet's first tick its path starts at the shooter's eyes (blocks and mobs in between count), and a
// path starting inside a mob's hitbox hits it.
@Mixin(value = EntityThrowableNT.class, remap = false)
public abstract class MixinThrowableHit {
    @org.spongepowered.asm.mixin.Shadow protected abstract double motionMult();
    // a trace (-Dntmgunfeel.trace): NTM's spread draws from the new bullet's own random, a fresh one each time; seeded
    // from the trace's seed by the order of the shots, so every run fires the same bullets
    @org.spongepowered.asm.mixin.injection.Inject(method = "setThrowableHeading", at = @At("HEAD"))
    private void gunfeel$traceSpread(double x, double y, double z, float velocity, float inaccuracy,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        // Minecraft's own methods, called as Entity's: not part of NTM's API the mod leans on (ntmgunfeel-ntm-api.txt)
        var self = (net.minecraft.world.entity.Entity) (Object) this;
        if (acup.gunfeel.Trace.ON && !self.level().isClientSide()) self.getRandom().setSeed(acup.gunfeel.Rng.spread());
    }
    // the bullet has left its shooter's box (NTM's, 0.3 wider) since it was fired. A bullet water stopped before that
    // (a shot down while standing in water) is still there when NTM's guard (selfDamageDelay) is over; while it
    // sinks in its shooter, the shooter is no target: NTM's own test never hits a box from inside, and the
    // point-blank rule below is for the mobs in front. It goes off on the bottom, or NTM's life of it ends.
    @Unique private boolean gunfeel$outOfShooter;

    @Unique
    private @org.jspecify.annotations.Nullable LivingEntity gunfeel$inShooter() {
        if (gunfeel$outOfShooter || !((Object) this instanceof EntityBulletBaseMK4 b) || !acup.gunfeel.water.WaterBullet.stopped(b)) return null;
        return ((EntityThrowableNT) (Object) this).getThrower();
    }

    @Unique
    private static boolean gunfeel$ofShooter(net.minecraft.world.entity.Entity e, LivingEntity shooter) {
        return e == shooter || acup.gunfeel.ModelPartEntities.parent(e) == shooter;
    }

    @WrapMethod(method="tick")
    private void gunfeel$view(Operation<Void> original) {
        var self=(EntityThrowableNT)(Object)this;
        if(!gunfeel$outOfShooter){
            LivingEntity shooter=self.getThrower();
            if(shooter==null || !shooter.getBoundingBox().inflate(0.3).contains(self.position())) gunfeel$outOfShooter=true;
        }
        var previous=acup.gunfeel.ModelHits.beginProjectile(self.getThrower(),self.getDeltaMovement().length()*motionMult());
        Vec3 before=self.position();
        try{
            original.call();
            // water: the bullet's new f, a stop or a skip, the tick's events (WaterTick); an explosive touching another
            // bullet (WaterImpact)
            if(self instanceof EntityBulletBaseMK4 bullet){
                acup.gunfeel.water.WaterTick.end(bullet);
                acup.gunfeel.water.WaterImpact.touch(bullet,before);
            }
        }finally{acup.gunfeel.ModelHits.endShot(previous);}
    }

    // NTM's 4 vanilla bubbles a tick in water, which the water effects can turn off for their own trail
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private void gunfeel$ntmBubbles(Level level, net.minecraft.core.particles.ParticleOptions particle, double x, double y, double z, double vx, double vy, double vz) {
        if (!acup.gunfeel.water.WaterEvents.ntmBubbles && (Object) this instanceof EntityBulletBaseMK4) return;
        level.addParticle(particle, x, y, z, vx, vy, vz);
    }
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private java.util.List<net.minecraft.world.entity.Entity> gunfeel$modelCandidates(Level level, net.minecraft.world.entity.Entity except, AABB area) {
        var list = level.getEntities(except, area);
        LivingEntity shooter = gunfeel$inShooter();
        if (shooter == null) return acup.gunfeel.ModelHits.candidates(level, except, list, area, e -> true);
        list = new java.util.ArrayList<>(list);
        list.removeIf(e -> gunfeel$ofShooter(e, shooter));
        return acup.gunfeel.ModelHits.candidates(level, except, list, area, e -> !gunfeel$ofShooter(e, shooter));
    }

    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/AABB;clip(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Ljava/util/Optional;"))
    private Optional<Vec3> gunfeel$clip(AABB box, Vec3 from, Vec3 to) {
        if (!((Object) this instanceof EntityBulletBaseMK4)) return box.clip(from, to);
        // not past where water stopped or turned the bullet
        to = acup.gunfeel.water.WaterTick.mobsTo((EntityBulletBaseMK4) (Object) this, to);
        Vec3 start = gunfeel$start(from);
        if (box.contains(start)) return Optional.of(start);
        return box.clip(start, to);
    }

    @ModifyArg(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/ClipContext;<init>(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/ClipContext$Block;Lnet/minecraft/world/level/ClipContext$Fluid;Lnet/minecraft/world/entity/Entity;)V"),
            index = 0)
    private Vec3 gunfeel$blockFrom(Vec3 from) {
        return (Object) this instanceof EntityBulletBaseMK4 ? gunfeel$start(from) : from;
    }

    // bullets fly through blocks tagged ntmgunfeel:bullet_passable (PassableClip)
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult gunfeel$blocks(Level level, ClipContext context) {
        if (!((Object) this instanceof EntityBulletBaseMK4)) return level.clip(context);
        EntityBulletBaseMK4 bullet = (EntityBulletBaseMK4) (Object) this;
        // water first: blocks past where it stops or skips the bullet are not met (WaterTick)
        Vec3 to = acup.gunfeel.water.WaterTick.path(bullet, level, context.getFrom(), context.getTo());
        BlockHitResult hit;
        // a mob's laser shot goes through glass (BeamLasers)
        if (level.isClientSide()) hit = acup.gunfeel.BeamLasers.clip(level, bullet.config, context.getFrom(), to);
        else {
            // blocks it pierced (bullet rules "pierce"), kept between ticks: one it stopped inside of is not paid twice
            if (gunfeel$pierced == null) gunfeel$pierced = new java.util.HashSet<>();
            hit = acup.gunfeel.BulletServer.clip(bullet, level, context.getFrom(), to, gunfeel$pierced);
        }
        return acup.gunfeel.water.WaterTick.blocks(bullet, hit);
    }

    @Unique
    private java.util.Set<net.minecraft.core.BlockPos> gunfeel$pierced;

    @Unique
    private Vec3 gunfeel$start(Vec3 from) {
        EntityThrowableNT self = (EntityThrowableNT) (Object) this;
        if (self.ticksInAir != 1) return from;
        LivingEntity shooter = self.getThrower();
        if (shooter == null) return from;
        // only a bullet fired from the shooter's gun; one spawned elsewhere (Shredder's plasma drops at the beam's
        // end) starts where it is. An AI mob leaning round a corner: its leaned eye (from its upright one the path
        // runs into the corner it leans past, and the bullet dies in it)
        Vec3 eye = acup.gunfeel.ai.AiLean.eye(shooter);
        return eye.distanceToSqr(from) < 9.0 ? eye : from;
    }
}
