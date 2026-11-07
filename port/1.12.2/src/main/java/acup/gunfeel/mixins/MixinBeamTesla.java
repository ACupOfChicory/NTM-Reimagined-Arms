package acup.gunfeel.mixins;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.tileentity.machine.TileEntityTesla;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import acup.gunfeel.BeamServer;
import acup.gunfeel.BeamTurrets;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

// port: 26.2's MixinBeamTesla on NTM 1.12.2 CE's TileEntityTesla.zap: the look is Library.isObstructed (blocks with a
// box only), hurtServer -> attackEntityFrom; the creatures in reach are zap's second List (ret first).
// a Tesla arc (the coil, the Tesla and taint crabs, the Tesla armor) reaches a creature where it gets through the blocks
// by the bullet rules with its damage, and hurts it with what is left (BeamTurrets)
@Mixin(value = TileEntityTesla.class, remap = false)
public abstract class MixinBeamTesla {
    @WrapOperation(method = "zap", at = @At(value = "INVOKE",
            target = "Lcom/hbm/lib/Library;isObstructed(Lnet/minecraft/world/World;DDDDDD)Z"))
    private static boolean gunfeel$sight(World world, double x, double y, double z, double ex, double ey, double ez, Operation<Boolean> original,
            @Local EntityLivingBase e, @Local(ordinal = 1) List<?> found) {
        if (world.isRemote || !BeamServer.on()) return original.call(world, x, y, z, ex, ey, ez);
        return BeamTurrets.arcBlocked(world, new Vec3d(x, y, z), new Vec3d(ex, ey, ez), BeamTurrets.arc(e, found.size()));
    }

    @WrapOperation(method = "zap", at = @At(value = "INVOKE", remap = true,
            target = "Lnet/minecraft/entity/EntityLivingBase;attackEntityFrom(Lnet/minecraft/util/DamageSource;F)Z"))
    private static boolean gunfeel$arc(EntityLivingBase e, DamageSource source, float damage, Operation<Boolean> original,
            @Local(argsOnly = true) World world,
            @Local(argsOnly = true, ordinal = 0) double x, @Local(argsOnly = true, ordinal = 1) double y, @Local(argsOnly = true, ordinal = 2) double z,
            @Local(argsOnly = true) Entity from) {
        if (world.isRemote || !BeamServer.on()) return original.call(e, source, damage);
        float left = BeamTurrets.arcHit(world, from, new Vec3d(x, y, z), e, damage);
        return left > 0F && original.call(e, source, left);
    }
}
