package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.blockentity.turret.BlockEntityTurretBaseNT;
import com.hbm.blockentity.turret.BlockEntityTurretTauon;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.BeamServer;
import acup.gunfeel.BeamTurrets;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

// a Tauon's shot gets to its target through blocks by the bullet rules, with what is left of its damage and the zone
// of the place it meets (BeamTurrets.hit); stopped on the way, it hurts no one
@Mixin(value = BlockEntityTurretTauon.class, remap = false)
public abstract class MixinBeamTauon {
    @WrapOperation(method = "updateFiringTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private boolean gunfeel$shot(Entity target, ServerLevel level, DamageSource source, float damage, Operation<Boolean> original) {
        if (!BeamServer.on()) return original.call(target, level, source, damage);
        float left = BeamTurrets.hit((BlockEntityTurretBaseNT) (Object) this, target, damage);
        return left > 0F && BeamTurrets.hurt(target, left, d -> original.call(target, level, source, d));
    }
}
