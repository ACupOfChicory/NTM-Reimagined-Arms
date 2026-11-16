package acup.gunfeel.ai.client;

import org.jspecify.annotations.Nullable;

/** A HumanoidModel's parts as BotPoser takes them, worked out once (MixinBotPose). */
public interface BotPoseModel {
    BotPoseCache.@Nullable Model gunfeel$botParts();

    void gunfeel$setBotParts(BotPoseCache.Model parts);
}
