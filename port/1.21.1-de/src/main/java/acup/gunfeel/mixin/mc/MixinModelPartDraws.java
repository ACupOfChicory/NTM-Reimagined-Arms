package acup.gunfeel.mixin.mc;

import acup.gunfeel.port.ModelPartNode;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Port only (notes/ports/de-1.21.1.md): a model's parts as they are drawn, for NtmMeshHits. A part knows the part that
// holds it and by what name (ModelPartNode), and each visible part's cubes are taken where 1.21.1 draws them, with
// the pose they are drawn at: ModelPart.compile, which render() calls once the part is turned and moved, unless the
// part is skipDraw. Nothing is taken outside an entity's frame (NtmMeshHits.part returns at once).
@Mixin(ModelPart.class)
public abstract class MixinModelPartDraws implements ModelPartNode {
    @Unique private ModelPart gunfeel$parent;
    @Unique private String gunfeel$name;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void gunfeel$children(List<ModelPart.Cube> cubes, Map<String, ModelPart> children, CallbackInfo ci) {
        for (var e : children.entrySet()) ((ModelPartNode) (Object) e.getValue()).gunfeel$hold((ModelPart) (Object) this, e.getKey());
    }

    @Inject(method = "compile", at = @At("HEAD"))
    private void gunfeel$drawn(PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay, int color, CallbackInfo ci) {
        acup.gunfeel.NtmMeshHits.part((ModelPart) (Object) this, pose, consumer);
    }

    @Override public ModelPart gunfeel$parent() { return gunfeel$parent; }
    @Override public String gunfeel$name() { return gunfeel$name; }
    @Override public void gunfeel$hold(ModelPart parent, String name) {
        gunfeel$parent = parent;
        gunfeel$name = name;
    }
}
