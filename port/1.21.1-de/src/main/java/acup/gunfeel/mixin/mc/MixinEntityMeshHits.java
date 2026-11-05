package acup.gunfeel.mixin.mc;

import acup.gunfeel.NtmMeshHits;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

// Port copy (notes/ports/de-1.21.1.md, «Копии»): 26.2 opens a mob's frame around EntityRenderDispatcher.submit. 1.21.1
// draws an entity of the world in LevelRenderer.renderEntity (camera at camX, camY, camZ), at once; the entities of a
// screen (the inventory's player) are drawn elsewhere and are left out, as 26.2 leaves out a GUI camera. The frame is
// opened around the call in renderLevel, not inside renderEntity: Flywheel cancels renderEntity at its HEAD for an
// entity it draws itself, and an end at RETURN would not run then. After the call, F3+B is drawn for such an entity
// (ModelHitDebug.skipped): 1.21.1 draws it inside the skipped render, 26.2 in a debug pass of its own.
@Mixin(LevelRenderer.class)
public abstract class MixinEntityMeshHits {
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderEntity(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"))
    private void gunfeel$frame(LevelRenderer self, Entity entity, double camX, double camY, double camZ, float partial,
            PoseStack stack, MultiBufferSource buffers, Operation<Void> original) {
        NtmMeshHits.begin(entity, camX, camY, camZ, partial);
        try {
            original.call(self, entity, camX, camY, camZ, partial, stack, buffers);
        } finally {
            NtmMeshHits.end();
        }
        acup.gunfeel.ModelHitDebug.skipped(entity, camX, camY, camZ, partial, stack, buffers);
    }
}
