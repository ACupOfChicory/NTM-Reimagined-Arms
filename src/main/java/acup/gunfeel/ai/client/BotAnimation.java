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
        BotAnimator.Frame frame = BotAnimator.frame(mob, pose, mob.level().getGameTime(), partial, state.isCrouching);
        data.gunfeel$setBot(frame);
        // its own walk for vanilla's swing as well (HumanoidModel.setupAnim): vanilla's kicks as it is hit
        if (!standStill) {
            state.walkAnimationSpeed = frame.walk();
            state.walkAnimationPos = frame.walkPos();
        }
        // falling dead it plays its own fall (BotPoser): not vanilla's tip onto its side as well
        if (frame.deathSec() >= 0F && BotClips.get(BotClips.DEATH) != null) state.deathTime = 0F;
        // the crouch (the AI's, or the mob's own) is posed here by its weight, so it comes and goes smoothly:
        // vanilla's would snap
        state.isCrouching = false;
    }

    /**
     * After HumanoidModel.translateToHand: a bot's item goes to the end of its forearm, turned with it about the
     * elbow (the item layer's own shift from there is from the arm's pivot, as vanilla's).
     */
    public static void hand(HumanoidModel<?> model, net.minecraft.world.entity.HumanoidArm arm,
            com.mojang.blaze3d.vertex.PoseStack pose) {
        net.minecraft.client.model.geom.ModelPart lower = BotModel.lower(model.getArm(arm));
        if (lower == null) return;
        lower.translateAndRotate(pose);
        pose.translate(-lower.x / 16F, -lower.y / 16F, -lower.z / 16F);
    }

    /**
     * Before HumanoidModel.setupAnim: a bot lying is set to no swim for vanilla's pose, and its swim amount returned
     * to be put back after (the renderer lies it down by it, BotPoser poses it lying); -1 for any other. Vanilla's
     * crawl stroke turns the left arm by rotlerpRad, which wraps the angle it goes through: while the swim amount is
     * between 0 and 1 (lying down or getting up on the move) the arm jumped across. The bot's arms lying are
     * BotPoser's; its legs crawling too; its head vanilla's ({@link #swimBack}). A bot with no gun keeps vanilla's
     * crawl (its arms have nothing of BotPoser's lying).
     */
    public static float noSwim(HumanoidRenderState state) {
        BotAnimator.Frame f = ((BotRenderData) state).gunfeel$bot();
        if (state.swimAmount <= 0F || f == null || f.arms()[acup.gunfeel.ai.AiPose.NO_GUN] >= 0.5F) return -1F;
        float swim = state.swimAmount;
        state.swimAmount = 0F;
        return swim;
    }

    /**
     * After HumanoidModel.setupAnim, a bot set to no swim: its swim amount back, and the one thing of vanilla's swim it
     * keeps, the head tipped toward the ground's forward (HumanoidModel 26.2: rotLerpRad(swim, xRot, -pi/4)).
     */
    public static void swimBack(HumanoidModel<?> model, HumanoidRenderState state, float swim) {
        state.swimAmount = swim;
        model.head.xRot = net.minecraft.util.Mth.rotLerpRad(swim, model.head.xRot, -net.minecraft.util.Mth.PI / 4F);
    }

    /** After HumanoidModel.setupAnim. */
    public static void pose(HumanoidModel<?> model, HumanoidRenderState state) {
        BotAnimator.Frame f = ((BotRenderData) state).gunfeel$bot();
        if (f == null) return;
        BotPoser.Parts parts = new BotPoser.Parts(model.head, model.body, model.rightArm, model.leftArm, model.rightLeg,
                model.leftLeg);
        BotPoser.apply(parts, f, state.swimAmount, standStill ? 1F : state.walkAnimationSpeed,
                standStill ? 0F : state.walkAnimationPos);
        BiConsumer<BotAnimator.Frame, BotPoser.Parts> watch = standWatch;
        if (watch != null) watch.accept(f, parts);
    }
}
