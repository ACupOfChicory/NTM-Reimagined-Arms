package acup.gunfeel.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBeamBase;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.BeamServer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// NTM's beam through blocks by the bullet rules (BeamServer): its one look at the blocks in performHitscan, and the
// damage each hit on its way gets (less what the blocks before it took)
@Mixin(value = EntityBulletBeamBase.class, remap = false)
public abstract class MixinBeamBlocks implements BeamServer.Holder {
    @Unique private BeamServer.@Nullable Path gunfeel$path;

    @Override
    public BeamServer.@Nullable Path gunfeel$path() {
        return gunfeel$path;
    }

    @Override
    public void gunfeel$path(BeamServer.@Nullable Path path) {
        gunfeel$path = path;
    }

    @Inject(method = "performHitscan", at = @At("HEAD"))
    private void gunfeel$begin(CallbackInfo ci) {
        gunfeel$path = null;
    }

    @WrapOperation(method = "performHitscan", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult gunfeel$blocks(Level level, ClipContext context, Operation<BlockHitResult> original) {
        if (level.isClientSide() || !BeamServer.on()) return original.call(level, context);
        return BeamServer.clip((EntityBulletBeamBase) (Object) this, level, context.getFrom(), context.getTo());
    }

    // the beam a coin sends on gets x1.25 of what this one has at the coin, not of its whole damage (NTM sets it where
    // the coin is: the one setPos in performHitscan)
    @WrapOperation(method = "performHitscan", at = @At(value = "INVOKE",
            target = "Lcom/hbm/entity/projectile/EntityBulletBeamBase;setPos(Lnet/minecraft/world/phys/Vec3;)V"))
    private void gunfeel$coin(EntityBulletBeamBase next, Vec3 coin, Operation<Void> original) {
        original.call(next, coin);
        if (gunfeel$path != null && next != (Object) this) BeamServer.reflected((EntityBulletBeamBase) (Object) this, next, coin);
    }

    // each hit with the damage the beam has there; its own damage back after
    @WrapOperation(method = "performHitscan", at = @At(value = "INVOKE",
            target = "Lcom/hbm/entity/projectile/EntityBulletBeamBase;onImpact(Lnet/minecraft/world/phys/HitResult;)V"))
    private void gunfeel$impact(EntityBulletBeamBase self, HitResult mop, Operation<Void> original) {
        if (gunfeel$path == null) {
            original.call(self, mop);
            return;
        }
        float own = self.damage;
        self.damage = BeamServer.damageAt(self, mop.getLocation());
        try {
            original.call(self, mop);
        } finally {
            self.damage = own;
        }
    }
}
