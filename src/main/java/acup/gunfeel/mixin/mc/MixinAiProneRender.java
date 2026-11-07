package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;

// A lying humanoid mob (an AI mob in the crawling pose) is laid down as the game lays a crawling player
// (AvatarRenderer.setupRotations): the body tips forward with the swim amount, and the model's own crawl moves the
// arms and legs. A player's renderer does this itself and is left alone.
@Mixin(LivingEntityRenderer.class)
public abstract class MixinAiProneRender {
    @Inject(method = "setupRotations", at = @At("TAIL"))
    private void gunfeel$layDown(LivingEntityRenderState state, PoseStack pose, float bodyRot, float scale, CallbackInfo ci) {
        if (state instanceof AvatarRenderState || !(state instanceof HumanoidRenderState h) || h.pose != Pose.SWIMMING || h.swimAmount <= 0F) return;
        float lie = h.isInWater ? -90F - h.xRot : -90F;
        pose.mulPose(Axis.XP.rotationDegrees(Mth.lerp(h.swimAmount, 0F, lie)));
        if (h.isVisuallySwimming) pose.translate(0F, -1F, 0.3F);
    }
}
