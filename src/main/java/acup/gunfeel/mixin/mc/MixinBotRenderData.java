package acup.gunfeel.mixin.mc;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import acup.gunfeel.ai.client.BotAnimator;
import acup.gunfeel.ai.client.BotRenderData;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

// Carries an AI mob's animation frame from extractRenderState to setupAnim.
@Mixin(HumanoidRenderState.class)
public abstract class MixinBotRenderData implements BotRenderData {
    @Unique private BotAnimator.@Nullable Frame gunfeel$bot;

    @Override
    public BotAnimator.@Nullable Frame gunfeel$bot() {
        return gunfeel$bot;
    }

    @Override
    public void gunfeel$setBot(BotAnimator.@Nullable Frame frame) {
        gunfeel$bot = frame;
    }
}
