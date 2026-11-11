package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;

@Mixin(GameRenderer.class)
public interface GameRendererAccess {
    @Invoker("bobView")
    void gunfeel$bobView(CameraRenderState camera, PoseStack pose);

    @Accessor("featureRenderDispatcher")
    FeatureRenderDispatcher gunfeel$features();
}
