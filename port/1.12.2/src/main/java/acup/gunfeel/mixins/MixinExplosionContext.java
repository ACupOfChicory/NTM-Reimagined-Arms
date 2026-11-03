package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.explosion.vanillant.interfaces.IEntityProcessor;

import acup.gunfeel.ExplosiveDamage;

// port: 26.2's MixinExplosionContext (e284339): an explosion learns its bullet when it gets its entity processor, and
// after it went off the bullet's contact share is paid if the blast did not reach the creature it hit
@Mixin(value = ExplosionVNT.class, remap = false)
public abstract class MixinExplosionContext {
    @Shadow
    private IEntityProcessor entityProcessor;

    @Inject(method = "setEntityProcessor", at = @At("HEAD"), require = 1)
    private void gunfeel$bind(IEntityProcessor processor, CallbackInfoReturnable<ExplosionVNT> ci) {
        if (processor instanceof ExplosiveDamage.Processor) ((ExplosiveDamage.Processor) processor).gunfeel$bind((ExplosionVNT) (Object) this);
    }

    @Inject(method = "explode", at = @At("HEAD"), require = 1)
    private void gunfeel$enter(CallbackInfo ci) {
        ExplosiveDamage.enter();
    }

    @Inject(method = "explode", at = @At("RETURN"), require = 1)
    private void gunfeel$finish(CallbackInfo ci) {
        ExplosiveDamage.exit();
        if (entityProcessor instanceof ExplosiveDamage.Processor) ExplosiveDamage.finish(((ExplosiveDamage.Processor) entityProcessor).gunfeel$blast());
    }
}
