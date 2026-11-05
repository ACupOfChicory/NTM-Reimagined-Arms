package acup.gunfeel.mixin.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Port copy (notes/ports/de-1.21.1.md, «Копии»): 26.2 swaps the gizmos of EntityHitboxDebugRenderer.showHitboxes;
// 1.21.1 draws F3+B in EntityRenderDispatcher.renderHitbox, in this order: the entity's box, a multipart entity's part
// boxes, the eye strip, the passenger strip, the look vector. Where ModelHitDebug draws the model's outline the box
// and the eye strip are left out, and the part boxes where the owner has a pose; the rest stays, as in 26.2.
@Mixin(EntityRenderDispatcher.class)
public abstract class MixinModelHitboxDebug {
    @Unique private static boolean gunfeel$outlined;
    @Unique private static Entity gunfeel$entity;

    @Inject(method = "renderHitbox", at = @At("HEAD"))
    private static void gunfeel$outline(PoseStack pose, VertexConsumer lines, Entity entity, float partial, float r, float g, float b,
            CallbackInfo ci) {
        gunfeel$entity = entity;
        gunfeel$outlined = acup.gunfeel.ModelHitDebug.draw(pose, lines, entity, partial);
    }

    @Redirect(method = "renderHitbox", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLineBox(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/phys/AABB;FFFF)V"))
    private static void gunfeel$box(PoseStack pose, VertexConsumer lines, AABB box, float r, float g, float b, float a) {
        if (!gunfeel$outlined) LevelRenderer.renderLineBox(pose, lines, box, r, g, b, a);
    }

    @Redirect(method = "renderHitbox", at = @At(value = "INVOKE", ordinal = 1,
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLineBox(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/world/phys/AABB;FFFF)V"))
    private static void gunfeel$parts(PoseStack pose, VertexConsumer lines, AABB box, float r, float g, float b, float a) {
        if (gunfeel$entity == null || !acup.gunfeel.ModelHitDebug.parts(gunfeel$entity)) LevelRenderer.renderLineBox(pose, lines, box, r, g, b, a);
    }

    @Redirect(method = "renderHitbox", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLineBox(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;DDDDDDFFFF)V"))
    private static void gunfeel$eyes(PoseStack pose, VertexConsumer lines, double x0, double y0, double z0, double x1, double y1, double z1,
            float r, float g, float b, float a) {
        if (!gunfeel$outlined) LevelRenderer.renderLineBox(pose, lines, x0, y0, z0, x1, y1, z1, r, g, b, a);
    }

    @Inject(method = "renderHitbox", at = @At("RETURN"))
    private static void gunfeel$done(PoseStack pose, VertexConsumer lines, Entity entity, float partial, float r, float g, float b,
            CallbackInfo ci) {
        gunfeel$outlined = false;
        gunfeel$entity = null;
    }
}
