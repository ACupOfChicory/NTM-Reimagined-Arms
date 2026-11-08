package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.ai.client.BotAnimation;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.Mob;

// An AI mob's animation frame, worked out as its render state is filled (after vanilla's humanoid fields).
@Mixin(HumanoidMobRenderer.class)
public abstract class MixinBotExtract {
    @Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Mob;Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;F)V",
            at = @At("TAIL"))
    private void gunfeel$bot(Mob mob, HumanoidRenderState state, float partial, CallbackInfo ci) {
        BotAnimation.extract(mob, state, partial);
    }
}
