package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.HoleRender;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;

// Bullet holes on glass and other translucent blocks are drawn after the translucent terrain, over the glass; drawn
// with the other translucent features (before it) the glass's own pixels would cover them.
@Mixin(value = SubmitNodeCollection.class, remap = false)
public abstract class MixinSubmitNodeCollection {
    @Inject(method="submitBlockModel",at=@At("HEAD"))
    private void gunfeel$blocks(PoseStack stack,RenderType type,
            java.util.List<net.minecraft.client.renderer.block.dispatch.BlockStateModelPart> parts,
            int[] tints,int light,int overlay,int outline,CallbackInfo ci) {
        acup.gunfeel.NtmMeshHits.blocks(stack,parts);
    }
    @ModifyVariable(method = "submitCustomGeometry", at = @At("HEAD"), argsOnly = true)
    private SubmitNodeCollector.CustomGeometryRenderer gunfeel$mesh(SubmitNodeCollector.CustomGeometryRenderer renderer,
            PoseStack stack,RenderType type,SubmitNodeCollector.CustomGeometryRenderer original) {
        return acup.gunfeel.NtmMeshHits.wrap(renderer,type);
    }

    @Inject(method = "submitModel", at = @At("HEAD"))
    private void gunfeel$model(net.minecraft.client.model.Model<?> model, Object state, PoseStack stack, RenderType type,
            int light, int overlay, int tint, net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,
            int outline, net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay crumble, CallbackInfo ci) {
        acup.gunfeel.NtmMeshHits.model(state, model,stack);
    }

    @Inject(method = "submitCustomGeometry", at = @At("HEAD"), cancellable = true)
    private void gunfeel$afterTerrain(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer renderer, CallbackInfo ci) {
        if (!HoleRender.isLate(renderType)) return;
        ((SubmitNodeCollection) (Object) this).afterTerrain.submit(new CustomFeatureRenderer.Submit(poseStack.last().copy(), renderType, renderer));
        ci.cancel();
    }
}
