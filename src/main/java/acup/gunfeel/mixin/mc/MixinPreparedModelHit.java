package acup.gunfeel.mixin.mc;

import acup.gunfeel.ModelHitClient;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelFeatureRenderer.class)
public abstract class MixinPreparedModelHit {
    @Inject(method = "prepareModel", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
            target = "Lnet/minecraft/client/model/Model;setupAnim(Ljava/lang/Object;)V"))
    private void gunfeel$posed(ModelFeatureRenderer.Submit<?> submit, CallbackInfo ci) {
        ModelHitClient.rendered(submit);
    }
}
