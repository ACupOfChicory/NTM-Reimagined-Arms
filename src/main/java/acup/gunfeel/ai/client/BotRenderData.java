package acup.gunfeel.ai.client;

import org.jspecify.annotations.Nullable;

/** An AI mob's frame on its HumanoidRenderState (MixinBotRenderData), null for every other mob. */
public interface BotRenderData {
    BotAnimator.@Nullable Frame gunfeel$bot();

    void gunfeel$setBot(BotAnimator.@Nullable Frame frame);
}
