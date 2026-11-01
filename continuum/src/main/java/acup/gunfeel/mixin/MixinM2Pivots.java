package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.hbm.client.render.weapon.M2WeaponRenderer;

import dev.golem.core.render.gem.Gem;

// port/continuum: NTM NEXT's M2 (ItemRenderM2) tilts on EQUIP about 0,1,-2.25 and slides on RECOIL along z, then turns
// the model 180 degrees about y. Continuum's renderer turns it first, so with NEXT's animation (ContinuumAnimations)
// the gun kicked forward instead of back and tilted the other way about the mirrored point. Same moves in the turned
// frame: the point at z=+2.25, the angle and the slide negated.
@Mixin(value = M2WeaponRenderer.class, remap = false)
public abstract class MixinM2Pivots {
    private static final String SPIN = "Ldev/golem/core/render/gem/Gem$Part;spin(Ldev/golem/core/render/gem/Gem$Axes;FFFLdev/golem/core/render/gem/Gem$FloatOf;)Ldev/golem/core/render/gem/Gem$Part;";
    private static final String SLIDE = "Ldev/golem/core/render/gem/Gem$Part;slide(Ldev/golem/core/render/gem/Gem$Axes;Ldev/golem/core/render/gem/Gem$FloatOf;)Ldev/golem/core/render/gem/Gem$Part;";

    @ModifyArg(method = "setup", at = @At(value = "INVOKE", target = SPIN, ordinal = 0), index = 3, require = 1)
    private float gunfeel$equipZ(float z) {
        return z == -2.25F ? 2.25F : z;
    }

    @ModifyArg(method = "setup", at = @At(value = "INVOKE", target = SPIN, ordinal = 0), index = 4, require = 1)
    private Gem.FloatOf<Object> gunfeel$equipAngle(Gem.FloatOf<Object> angle) {
        return pose -> -angle.of(pose);
    }

    @ModifyArg(method = "setup", at = @At(value = "INVOKE", target = SLIDE, ordinal = 0), index = 1, require = 1)
    private Gem.FloatOf<Object> gunfeel$recoil(Gem.FloatOf<Object> recoil) {
        return pose -> -recoil.of(pose);
    }
}
