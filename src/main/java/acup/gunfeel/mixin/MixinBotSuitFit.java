package acup.gunfeel.mixin;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.hbm.client.render.ArmorWorldRenderer;
import com.hbm.render.loader.HFRWavefrontObject;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.ModelPoseCapture;
import acup.gunfeel.ai.client.BotModel;
import acup.gunfeel.ai.client.SuitBend;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;

// NTM fits a suit's own mesh (HEV, T-51 and the rest) to the limb under it by the limb's largest box of its own: the
// boot's sole goes to that box's bottom. The FBI agent's and the test dummy's legs and arms are split (BotModel): the
// thigh is the leg's own box, the shin its child, so the legs and boots sat 6 px up, on the knees. The fit measures the
// whole straight limb instead; the mesh is posed by the limb itself.
//
// On such a limb the mesh bends with it (SuitBend): what is below the knee or the elbow goes with the shin or the
// forearm, a band across the cut.
@Mixin(value = ArmorWorldRenderer.class, remap = false)
public abstract class MixinBotSuitFit {
    private static final String LIMB = "limb(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "ILcom/hbm/render/loader/HFRWavefrontObject;Lnet/minecraft/client/model/geom/ModelPart;"
            + "Lcom/hbm/client/render/ArmorWorldRenderer$Bone;Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;"
            + "Lnet/minecraft/resources/Identifier;IFII)V";

    /** the pose a split limb's mesh is entered from (its parent's), on the render thread; null for any other limb */
    @Unique
    private static Matrix4f gunfeel$parent;

    @ModifyVariable(method = "fit", at = @At("HEAD"), argsOnly = true, require = 1)
    private static ModelPart gunfeel$wholeLimb(ModelPart limb) {
        return BotModel.whole(limb, ModelPoseCapture.ACCESS);
    }

    @WrapOperation(method = LIMB, require = 1, at = @At(value = "INVOKE",
            target = "Lcom/hbm/client/render/ArmorWorldRenderer;enterFrame(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/model/geom/ModelPart;Lcom/hbm/client/render/ArmorWorldRenderer$Bone;Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V"))
    private static void gunfeel$enter(PoseStack pose, ModelPart limb, ArmorWorldRenderer.Bone bone, HumanoidRenderState state, Operation<Void> original) {
        gunfeel$parent = BotModel.lower(limb) != null && state.entityType != net.minecraft.world.entity.EntityTypes.ARMOR_STAND
                ? new Matrix4f(pose.last().pose()) : null;
        original.call(pose, limb, bone, state);
    }

    @WrapOperation(method = LIMB, require = 1, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitCustomGeometry(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/rendertype/RenderType;Lnet/minecraft/client/renderer/SubmitNodeCollector$CustomGeometryRenderer;)V"))
    private static void gunfeel$bend(SubmitNodeCollector collector, PoseStack pose, RenderType type, SubmitNodeCollector.CustomGeometryRenderer renderer,
            Operation<Void> original, @Local(argsOnly = true) HFRWavefrontObject mesh, @Local(argsOnly = true) ModelPart limb,
            @Local(argsOnly = true) ArmorWorldRenderer.Bone bone, @Local(argsOnly = true, ordinal = 1) int group,
            @Local(argsOnly = true, ordinal = 2) int light, @Local(argsOnly = true, ordinal = 3) int tint) {
        Matrix4f parent = gunfeel$parent;
        gunfeel$parent = null;
        SuitBend.Bent bent = parent == null || !SuitBend.on ? null : SuitBend.bent(parent, limb, pose.last().pose());
        if (bent == null) {
            original.call(collector, pose, type, renderer);
            return;
        }
        boolean smoothing = mesh.smoothing();
        SuitBend.Cut cut = SuitBend.cut(mesh.groups[group], smoothing, bent.plane());
        original.call(collector, pose, type, (SubmitNodeCollector.CustomGeometryRenderer) (p, buffer) -> {
            SuitBend.draw(cut, p, buffer, light, tint, smoothing, bent.turn(), bent.pivot());
            if (SuitBend.capture != null) SuitBend.record(bone.name(), mesh.groups[group], smoothing, cut, p, bent,
                    net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera().position());
        });
    }
}
