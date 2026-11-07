package acup.gunfeel.mixin;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.blockentity.machine.BlockEntityTesla;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import acup.gunfeel.BeamServer;
import acup.gunfeel.BeamTurrets;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

// a Tesla arc (the coil, the Tesla and taint crabs, the Tesla armor: BlockEntityTesla.zap) reaches a creature where
// it gets through the blocks by the bullet rules with its damage, and hurts it with what is left (BeamTurrets)
@Mixin(value = BlockEntityTesla.class, remap = false)
public abstract class MixinBeamTesla {
    @WrapOperation(method = "zap", at = @At(value = "INVOKE",
            target = "Lcom/hbm/blockentity/machine/BlockEntityTesla;isObstructed(Lnet/minecraft/server/level/ServerLevel;DDDDDD)Z"))
    private static boolean gunfeel$sight(ServerLevel level, double x, double y, double z, double ex, double ey, double ez, Operation<Boolean> original,
            @Local LivingEntity e, @Local(ordinal = 1) List<?> found) {
        if (!BeamServer.on()) return original.call(level, x, y, z, ex, ey, ez);
        return BeamTurrets.arcBlocked(level, new Vec3(x, y, z), new Vec3(ex, ey, ez), BeamTurrets.arc(e, found.size()));
    }

    @WrapOperation(method = "zap", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;hurtServer(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
    private static boolean gunfeel$arc(LivingEntity e, ServerLevel level, DamageSource source, float damage, Operation<Boolean> original,
            @Local(argsOnly = true, ordinal = 0) double x, @Local(argsOnly = true, ordinal = 1) double y, @Local(argsOnly = true, ordinal = 2) double z,
            @Local(argsOnly = true) @Nullable Entity from) {
        if (!BeamServer.on()) return original.call(e, level, source, damage);
        float left = BeamTurrets.arcHit(level, from, new Vec3(x, y, z), e, damage);
        return left > 0F && original.call(e, level, source, left);
    }
}
