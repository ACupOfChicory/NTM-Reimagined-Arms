package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.tileentity.turret.TileEntityTurretMaxwell;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.BeamServer;
import acup.gunfeel.BeamTurrets;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.DamageSource;

// port: 26.2's MixinBeamMaxwell on NTM 1.12.2 CE.
// a Maxwell's beam gets to its target through blocks by the bullet rules, with what is left of its damage and the
// zone of the place it meets (BeamTurrets.hit); stopped on the way, it hurts no one
@Mixin(value = TileEntityTurretMaxwell.class, remap = false)
public abstract class MixinBeamMaxwell {
    @WrapOperation(method = "updateFiringTick", at = @At(value = "INVOKE",
            target = "Lcom/hbm/util/EntityDamageUtil;attackEntityFromIgnoreIFrame(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/DamageSource;F)Z"))
    private boolean gunfeel$shot(Entity target, DamageSource source, float damage, Operation<Boolean> original) {
        if (!BeamServer.on()) return original.call(target, source, damage);
        float left = BeamTurrets.hit((TileEntity) (Object) this, target, damage);
        return left > 0F && original.call(target, source, left);
    }
}
