package acup.gunfeel.mixin;

import acup.gunfeel.NtmMeshHits;
import acup.gunfeel.ObjGroup;
import com.hbm.client.model.WavefrontObj;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// port/continuum: MixinNtmMeshHits on NTM Continuum. Its OBJ mobs (glyphids, crabs, the mask man...) draw each named
// part through WavefrontObj.drawGroup inside a submitted custom geometry; NtmMeshHits takes the part's mesh and the
// pose it is drawn with (NTM NEXT: GroupObject.emit). Every renderPart/renderAll/renderExcept ends here.
@Mixin(value = WavefrontObj.class, remap = false)
public abstract class MixinNtmMeshHits {
    @Shadow @Final private float[][] positions;
    @Shadow @Final private float[][] uvs;

    @Inject(method = "drawGroup(Lcom/hbm/client/model/WavefrontObj$Group;Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIIZFF)V",
            at = @At("HEAD"))
    private void gunfeel$group(WavefrontObj.Group group, PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay,
            int color, boolean doubleSided, float uOffset, float vOffset, CallbackInfo ci) {
        if (!ObjGroup.capturing(buffer)) return;
        NtmMeshHits.group(ObjGroup.of(group, group.name, ((ObjGroupAccess) (Object) group).gunfeel$faces(), positions, uvs),
                pose, buffer, 1F, 1F, uOffset, vOffset);
    }
}
