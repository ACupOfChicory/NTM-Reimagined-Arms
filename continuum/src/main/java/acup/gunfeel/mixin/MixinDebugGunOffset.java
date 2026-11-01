package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.hbm.client.render.weapon.DebugWeaponRenderer;

// port/continuum: NTM NEXT's debug revolver (ItemRenderDebug.setupFirstPerson) sits 1 forward in first person,
// Continuum's 0.875; the arms on NEXT's animation held it 0.125 off
@Mixin(value = DebugWeaponRenderer.class, remap = false)
public abstract class MixinDebugGunOffset {
    @ModifyArg(method = "setupFirstPerson", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V", ordinal = 0), index = 2, require = 1)
    private double gunfeel$forward(double z) {
        return z == .875 ? 1 : z;
    }
}
