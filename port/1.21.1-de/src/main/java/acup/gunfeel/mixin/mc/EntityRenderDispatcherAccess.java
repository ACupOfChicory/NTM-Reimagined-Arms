package acup.gunfeel.mixin.mc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// F3+B of 1.21.1 (private static), for an entity whose render Flywheel skips (ModelHitDebug.skipped)
@Mixin(EntityRenderDispatcher.class)
public interface EntityRenderDispatcherAccess {
    @Invoker("renderHitbox")
    static void gunfeel$renderHitbox(PoseStack pose, VertexConsumer lines, Entity entity, float partial, float r, float g, float b) {
        throw new AssertionError();
    }
}
