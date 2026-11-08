package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.ai.client.BotAnimation;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

// An AI mob's pose on top of vanilla's, in the model's parts: the hitbox read from them takes it too. The armour
// layers' models run the same setupAnim on the same state, so the armour follows.
@Mixin(HumanoidModel.class)
public abstract class MixinBotPose {
    @Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
    private void gunfeel$bot(HumanoidRenderState state, CallbackInfo ci) {
        BotAnimation.pose((HumanoidModel<?>) (Object) this, state);
    }
}
