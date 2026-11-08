package acup.gunfeel.ai.client;

import java.util.function.BiConsumer;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

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

    /** Stands only (BotStand): the pose played instead of the mob's own; the player off; told of each posed bot. */
    public static volatile @Nullable Function<Mob, AiPose> standPose;
    public static volatile boolean standOff;
    public static volatile @Nullable BiConsumer<BotAnimator.Frame, BotPoser.Parts> standWatch;
    /** Stands only: no breathing, so two poses can be measured against each other to the millimetre. */
    public static volatile boolean standStill;

    public static boolean animated(Mob mob) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        return FBI.equals(id) || DUMMY.equals(id);
    }

    /** After HumanoidMobRenderer.extractRenderState. */
    public static void extract(Mob mob, HumanoidRenderState state, float partial) {
        BotRenderData data = (BotRenderData) state;
        if (standOff || !animated(mob)) {
            data.gunfeel$setBot(null);
            return;
        }
        Function<Mob, AiPose> stand = standPose;
        AiPose pose = stand != null ? stand.apply(mob) : AiPose.of(mob);
        data.gunfeel$setBot(BotAnimator.frame(mob, pose, mob.level().getGameTime(), partial, state.isCrouching));
        // the crouch (the AI's, or the mob's own) is posed here by its weight, so it comes and goes smoothly:
        // vanilla's would snap
        state.isCrouching = false;
    }

    /** After HumanoidModel.setupAnim. */
    public static void pose(HumanoidModel<?> model, HumanoidRenderState state) {
        BotAnimator.Frame f = ((BotRenderData) state).gunfeel$bot();
        if (f == null) return;
        BotPoser.Parts parts = new BotPoser.Parts(model.head, model.body, model.rightArm, model.leftArm, model.rightLeg,
                model.leftLeg);
        BotPoser.apply(parts, f, state.swimAmount, standStill ? 1F : state.walkAnimationSpeed);
        BiConsumer<BotAnimator.Frame, BotPoser.Parts> watch = standWatch;
        if (watch != null) watch.accept(f, parts);
    }
}
