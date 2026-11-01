package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.hbm.client.render.weapon.G3ZebraWeaponRenderer;

import dev.golem.core.render.gem.Gem;

// port/continuum: the G3 Zebra's magazine spins (SPEEN, the inspect) about 0,-1.75,-0.5 as on NTM NEXT's ItemRenderG3
// and Continuum's own G3WeaponRenderer. Continuum's Zebra renderer spins it about its origin, so with NEXT's inspect
// the magazine swung away from the left hand.
@Mixin(value = G3ZebraWeaponRenderer.class, remap = false)
public abstract class MixinG3ZebraSpeen {
    // setup's first two angles: SPEEN[2], then SPEEN[1]
    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(method = "setup", at = @At(value = "INVOKE", target = "Ldev/golem/core/render/gem/Gem$Part;angle(Ldev/golem/core/render/gem/Gem$Axes;Ldev/golem/core/render/gem/Gem$FloatOf;)Ldev/golem/core/render/gem/Gem$Part;", ordinal = 0), require = 1)
    private Gem.Part gunfeel$speenFrom(Gem.Part part, Gem.Axes axis, Gem.FloatOf degrees) {
        return part.translate(0F, -1.75F, -.5F).angle(axis, degrees);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Redirect(method = "setup", at = @At(value = "INVOKE", target = "Ldev/golem/core/render/gem/Gem$Part;angle(Ldev/golem/core/render/gem/Gem$Axes;Ldev/golem/core/render/gem/Gem$FloatOf;)Ldev/golem/core/render/gem/Gem$Part;", ordinal = 1), require = 1)
    private Gem.Part gunfeel$speenBack(Gem.Part part, Gem.Axes axis, Gem.FloatOf degrees) {
        return part.angle(axis, degrees).translate(0F, 1.75F, .5F);
    }
}
