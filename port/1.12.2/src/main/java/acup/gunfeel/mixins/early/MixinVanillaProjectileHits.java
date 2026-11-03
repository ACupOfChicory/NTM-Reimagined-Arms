package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.ModelHits;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.entity.projectile.EntityThrowable;

// port: 26.2's MixinProjectileModelQuery (and MixinEntityModelBox, as far as projectiles go): vanilla projectiles meet
// mobs by their models. 26.2 wraps ProjectileUtil, which every vanilla projectile asks; 1.12.2's projectiles ask for
// the mobs near their path and test entity.getEntityBoundingBox().grow(0.3).calculateIntercept each in its own place:
// the arrow in findEntityOnPath, thrown items in onUpdate, the fishing hook in checkCollision, fireballs through
// ProjectileHelper.forwardsRaycast (MixinArrowPath, MixinThrowablePath, MixinFishHookPath, MixinProjectileHelperPath).
// Here: on the server, while the projectile's tick runs, rays are checked against the pose its shooter sees
// (ModelHits); there the mob's box is its model's (ModelAABB) and mobs whose model sticks out of the vanilla box are
// candidates too.
@Mixin({EntityArrow.class, EntityThrowable.class, EntityFireball.class, EntityFishHook.class})
public abstract class MixinVanillaProjectileHits {
    @Unique
    private ModelHits.ShotContext gunfeel$hitsPrevious;
    @Unique
    private boolean gunfeel$hitsOn;

    @Inject(method = "onUpdate", at = @At("HEAD"))
    private void gunfeel$beginHits(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.world.isRemote) return;
        double travel = Math.sqrt(self.motionX * self.motionX + self.motionY * self.motionY + self.motionZ * self.motionZ);
        gunfeel$hitsPrevious = ModelHits.beginProjectile(gunfeel$owner(), travel);
        gunfeel$hitsOn = true;
    }

    @Inject(method = "onUpdate", at = @At("RETURN"))
    private void gunfeel$endHits(CallbackInfo ci) {
        if (!gunfeel$hitsOn) return;
        gunfeel$hitsOn = false;
        ModelHits.endShot(gunfeel$hitsPrevious);
        gunfeel$hitsPrevious = null;
    }

    // who shot it (26.2: Projectile.getOwner)
    @Unique
    private Entity gunfeel$owner() {
        Object self = this;
        if (self instanceof EntityArrow) return ((EntityArrow) self).shootingEntity;
        if (self instanceof EntityThrowable) return ((EntityThrowable) self).getThrower();
        if (self instanceof EntityFireball) return ((EntityFireball) self).shootingEntity;
        if (self instanceof EntityFishHook) return ((EntityFishHook) self).getAngler();
        return null;
    }
}
