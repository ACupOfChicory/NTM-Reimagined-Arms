package acup.gunfeel.ai.client;

import acup.gunfeel.ai.AiPose;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

/**
 * The two ends of an AI mob's animation: filling its render state (MixinBotExtract) and posing its model
 * (MixinBotPose). NTM's FBI agent and test dummy, the mobs the mob AI drives, known by their ids (no NTM class
 * here: the mixins are vanilla's and load without NTM); any other mob is left as it is.
 */
public final class BotAnimation {
    private BotAnimation() {
    }

    static final Identifier FBI = Identifier.fromNamespaceAndPath("hbm", "entity_fbi"),
            DUMMY = Identifier.fromNamespaceAndPath("hbm", "entity_test_dummy");

    public static boolean animated(Mob mob) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        return FBI.equals(id) || DUMMY.equals(id);
    }

    /** After HumanoidMobRenderer.extractRenderState. */
    public static void extract(Mob mob, HumanoidRenderState state, float partial) {
        BotRenderData data = (BotRenderData) state;
        if (!animated(mob)) {
            data.gunfeel$setBot(null);
            return;
        }
        AiPose pose = AiPose.of(mob);
        data.gunfeel$setBot(BotAnimator.frame(mob, pose, mob.level().getGameTime(), partial));
        // the crouch is posed here, by its weight, so it comes and goes smoothly: vanilla's would snap
        state.isCrouching = false;
    }

    /** After HumanoidModel.setupAnim. */
    public static void pose(HumanoidModel<?> model, HumanoidRenderState state) {
        BotAnimator.Frame f = ((BotRenderData) state).gunfeel$bot();
        if (f == null) return;
        BotPoser.apply(new BotPoser.Parts(model.head, model.body, model.rightArm, model.leftArm, model.rightLeg,
                model.leftLeg), f, state.swimAmount, state.walkAnimationSpeed);
    }
}
