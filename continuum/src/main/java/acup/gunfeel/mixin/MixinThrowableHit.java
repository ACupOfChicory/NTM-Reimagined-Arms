package acup.gunfeel.mixin;

import java.util.Optional;
import java.util.function.Predicate;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import acup.gunfeel.PassableClip;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

// port/continuum: main's MixinThrowableHit (on NTM NEXT's EntityThrowableNT) on NTM Continuum's bullet, which is no
// EntityThrowableNT: it moves itself in tick() (blocks by Level.clip, then the entities along the path by
// nearestEntityAlong / entitiesAlong, their candidates by Level.getEntities with a filter) and draws its spread in
// shoot(), called from its constructors.
// Server: point-blank hits. The bullet starts a little in front of the shooter's eyes, and a hitbox that already
// contains the start of the bullet's path is never hit (AABB.clip misses a segment starting inside). On the bullet's
// first tick its path starts at the shooter's eyes (blocks and mobs in between count), and a path starting inside a
// mob's hitbox hits it. Mobs are hit where the shooter saw them (ModelHits), and bullets fly through the blocks tagged
// ntmgunfeel:bullet_passable.
@Mixin(value = EntityBulletBaseMK4.class, remap = false)
public abstract class MixinThrowableHit {
    @Shadow
    public abstract double step();

    // a trace (-Dntmgunfeel.trace): the spread draws from the new bullet's own random, a fresh one each time; seeded
    // from the trace's seed by the order of the shots, so every run fires the same bullets
    @Inject(method = "shoot", at = @At("HEAD"))
    private void gunfeel$traceSpread(double x, double y, double z, float spread, CallbackInfo ci) {
        // Minecraft's own methods, called as Entity's: not part of NTM's API the mod leans on (ntmgunfeel-ntm-api.txt)
        var self = (Entity) (Object) this;
        if (acup.gunfeel.Trace.ON && !self.level().isClientSide()) self.getRandom().setSeed(acup.gunfeel.Rng.spread());
    }

    @WrapMethod(method = "tick")
    private void gunfeel$view(Operation<Void> original) {
        var self = (EntityBulletBaseMK4) (Object) this;
        var previous = acup.gunfeel.ModelHits.beginProjectile(self.getThrower(), self.getDeltaMovement().length() * step());
        try {
            original.call();
        } finally {
            acup.gunfeel.ModelHits.endShot(previous);
        }
    }

    @Redirect(method = "candidates", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;"))
    private java.util.List<Entity> gunfeel$modelCandidates(Level level, Entity except, AABB area, Predicate<Entity> filter) {
        return acup.gunfeel.ModelHits.candidates(level, except, level.getEntities(except, area, filter), area, filter);
    }

    // the entity sweep of the first tick starts at the shooter's eyes
    @ModifyArg(method = "tick", at = @At(value = "INVOKE",
            target = "Lcom/hbm/entity/projectile/EntityBulletBaseMK4;nearestEntityAlong(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/EntityHitResult;"),
            index = 0)
    private Vec3 gunfeel$nearestFrom(Vec3 from) {
        return gunfeel$start(from);
    }

    @ModifyArg(method = "tick", at = @At(value = "INVOKE",
            target = "Lcom/hbm/entity/projectile/EntityBulletBaseMK4;entitiesAlong(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Ljava/util/List;"),
            index = 0)
    private Vec3 gunfeel$alongFrom(Vec3 from) {
        return gunfeel$start(from);
    }

    @Redirect(method = "entitiesAlong", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/phys/AABB;clip(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)Ljava/util/Optional;"))
    private Optional<Vec3> gunfeel$clip(AABB box, Vec3 from, Vec3 to) {
        if (box.contains(from)) return Optional.of(from);
        return box.clip(from, to);
    }

    @ModifyArg(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/ClipContext;<init>(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/ClipContext$Block;Lnet/minecraft/world/level/ClipContext$Fluid;Lnet/minecraft/world/entity/Entity;)V"),
            index = 0)
    private Vec3 gunfeel$blockFrom(Vec3 from) {
        return gunfeel$start(from);
    }

    // bullets fly through blocks tagged ntmgunfeel:bullet_passable (PassableClip)
    @Redirect(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult gunfeel$blocks(Level level, ClipContext context) {
        EntityBulletBaseMK4 bullet = (EntityBulletBaseMK4) (Object) this;
        if (level.isClientSide()) return PassableClip.clip(level, context.getFrom(), context.getTo());
        // blocks it pierced (bullet rules "pierce"), kept between ticks: one it stopped inside of is not paid twice
        if (gunfeel$pierced == null) gunfeel$pierced = new java.util.HashSet<>();
        return acup.gunfeel.BulletServer.clip(bullet, level, context.getFrom(), context.getTo(), gunfeel$pierced);
    }

    @Unique
    private java.util.Set<net.minecraft.core.BlockPos> gunfeel$pierced;

    @Unique
    private Vec3 gunfeel$start(Vec3 from) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        // the bullet's age: Continuum's bullet has no ticksInAir, the server counts tickCount up before each tick
        if (self.tickCount != 1) return from;
        LivingEntity shooter = self.getThrower();
        if (shooter == null) return from;
        // only a bullet fired from the shooter's gun; one spawned elsewhere (a submunition) starts where it is
        Vec3 eye = shooter.getEyePosition();
        return eye.distanceToSqr(from) < 9.0 ? eye : from;
    }
}
