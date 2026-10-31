package acup.gunfeel.mixins.early;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.ModelAABB;
import acup.gunfeel.ModelHits;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.entity.projectile.EntityFishHook;
import net.minecraft.entity.projectile.EntityThrowable;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

// port: 26.2's MixinProjectileModelQuery (and MixinEntityModelBox, as far as projectiles go): vanilla projectiles meet
// mobs by their models. 26.2 wraps ProjectileUtil, which every vanilla projectile asks; 1.7.10 has no such helper,
// each projectile's onUpdate asks World.getEntitiesWithinAABBExcludingEntity and then entity.boundingBox.expand(f)
// .calculateIntercept itself (arrow, thrown items, fireballs, the fishing hook). On the server, while the tick runs,
// rays are checked against the pose its shooter sees (ModelHits), the mob's box is its model's (ModelAABB) and mobs
// whose model sticks out of the vanilla box are candidates too.
@Mixin({EntityArrow.class, EntityThrowable.class, EntityFireball.class, EntityFishHook.class})
public abstract class MixinVanillaProjectileHits {
    @Unique
    private ModelHits.ShotContext gunfeel$hitsPrevious;
    @Unique
    private boolean gunfeel$hitsOn;

    @Inject(method = "onUpdate", at = @At("HEAD"))
    private void gunfeel$beginHits(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self.worldObj.isRemote) return;
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
        if (self instanceof EntityFishHook) return ((EntityFishHook) self).field_146042_b;
        return null;
    }

    // a mob's box as the projectile tests it (its own box is read as this class's field, not Entity's)
    @WrapOperation(method = "onUpdate", at = @At(value = "FIELD", opcode = org.objectweb.asm.Opcodes.GETFIELD,
            target = "Lnet/minecraft/entity/Entity;boundingBox:Lnet/minecraft/util/AxisAlignedBB;"))
    private AxisAlignedBB gunfeel$modelBox(Entity entity, Operation<AxisAlignedBB> original) {
        if (entity == (Object) this || entity.worldObj.isRemote) return original.call(entity);
        return ModelAABB.of(entity);
    }

    @WrapOperation(method = "onUpdate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/World;getEntitiesWithinAABBExcludingEntity(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/AxisAlignedBB;)Ljava/util/List;"))
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List gunfeel$candidates(World world, Entity except, AxisAlignedBB area, Operation<List> original) {
        List list = original.call(world, except, area);
        if (world.isRemote) return list;
        return ModelHits.candidates(world, except, (List<Entity>) list, area, e -> e.canBeCollidedWith());
    }
}
