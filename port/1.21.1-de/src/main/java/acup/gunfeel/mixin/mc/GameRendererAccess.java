package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;

// 1.21.1 bobView takes the pose and the partial tick (26.2: the camera state and the pose)
@Mixin(GameRenderer.class)
public interface GameRendererAccess {
    @Invoker("bobView")
    void gunfeel$bobView(PoseStack pose, float partial);
}
