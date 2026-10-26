package acup.gunfeel.mixin;

import acup.gunfeel.NtmMeshHits;
import com.hbm.render.loader.GroupObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GroupObject.class, remap = false)
public abstract class MixinNtmMeshHits {
    @Inject(method = "emit(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIIFFFFZ)V", at = @At("HEAD"))
    private void gunfeel$group(PoseStack.Pose pose, VertexConsumer buffer, int light, int color, int overlay,
            float us, float vs, float uo, float vo, boolean smoothing, CallbackInfo ci) {
        NtmMeshHits.group((GroupObject) (Object) this, pose,us,vs,uo,vo);
    }
}
