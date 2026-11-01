package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.hbm.client.render.weapon.DoubleBarrelWeaponRenderer;

// port/continuum: the double barrel's lever and shells turn about NTM NEXT's points (ItemRenderDoubleBarrel: lever
// about -0.3125,0.3125,0, shells about 0,0,-1). Continuum's renderer has both points with the opposite sign, so with
// NEXT's animation (ContinuumAnimations) the lever and the shells left their places while they turned.
@Mixin(value = DoubleBarrelWeaponRenderer.class, remap = false)
public abstract class MixinDoubleBarrelPivots {
    // setup's spins: EQUIP, TURN, LIFT, BARREL, BUCKLE, LEVER (5), SHELL_FLIP (6)
    @ModifyArg(method = "setup", at = @At(value = "INVOKE", target = "Ldev/golem/core/render/gem/Gem$Part;spin(Ldev/golem/core/render/gem/Gem$Axes;FFFLdev/golem/core/render/gem/Gem$FloatOf;)Ldev/golem/core/render/gem/Gem$Part;", ordinal = 5), index = 1, require = 1)
    private float gunfeel$leverX(float x) {
        return x == .3125F ? -.3125F : x;
    }

    @ModifyArg(method = "setup", at = @At(value = "INVOKE", target = "Ldev/golem/core/render/gem/Gem$Part;spin(Ldev/golem/core/render/gem/Gem$Axes;FFFLdev/golem/core/render/gem/Gem$FloatOf;)Ldev/golem/core/render/gem/Gem$Part;", ordinal = 5), index = 2, require = 1)
    private float gunfeel$leverY(float y) {
        return y == -.3125F ? .3125F : y;
    }

    @ModifyArg(method = "setup", at = @At(value = "INVOKE", target = "Ldev/golem/core/render/gem/Gem$Part;spin(Ldev/golem/core/render/gem/Gem$Axes;FFFLdev/golem/core/render/gem/Gem$FloatOf;)Ldev/golem/core/render/gem/Gem$Part;", ordinal = 6), index = 3, require = 1)
    private float gunfeel$shellsZ(float z) {
        return z == 1F ? -1F : z;
    }
}
