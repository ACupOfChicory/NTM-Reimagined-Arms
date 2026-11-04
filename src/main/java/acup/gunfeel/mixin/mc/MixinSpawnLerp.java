package acup.gunfeel.mixin.mc;

import acup.gunfeel.SpawnLerp;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A Flywheel entity visual (CrankShaft's Vanillate) made for an entity not yet ticked: its previous position to its
 * place first (SpawnLerp). Without Flywheel the class is not there and nothing is applied; if the constructor changes,
 * require = 0 leaves it out.
 */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.lib.visual.AbstractEntityVisual", remap = false)
public abstract class MixinSpawnLerp {
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void gunfeel$settle(CallbackInfo ci) {
        SpawnLerp.settle(((VanillateEntityAccess) (Object) this).gunfeel$entity());
    }
}
