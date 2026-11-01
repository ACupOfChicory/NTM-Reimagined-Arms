package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.hbm.client.render.weapon.SexyWeaponRenderer;

// port/continuum: Sexy and Heretic lower the gun for the reload about z = -6, as NTM NEXT's ItemRenderSexy does
// (translate 0,0,-6; LOWER; translate 0,0,6). Continuum's renderer turns it about z = +6, so with NEXT's animation
// (ContinuumAnimations) the gun rose 1.16 blocks above 26.2's for the whole reload, the right hand with it.
@Mixin(value = SexyWeaponRenderer.class, remap = false)
public abstract class MixinSexyLower {
    // setup's second spin: EQUIP is the first, LOWER the second
    @ModifyArg(method = "setup", at = @At(value = "INVOKE", target = "Ldev/golem/core/render/gem/Gem$Part;spin(Ldev/golem/core/render/gem/Gem$Axes;FFFLdev/golem/core/render/gem/Gem$FloatOf;)Ldev/golem/core/render/gem/Gem$Part;", ordinal = 1), index = 3)
    private float gunfeel$lowerPivot(float z) {
        return z == 6F ? -6F : z;
    }
}
