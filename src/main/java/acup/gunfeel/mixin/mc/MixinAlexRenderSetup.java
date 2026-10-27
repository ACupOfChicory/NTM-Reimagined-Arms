package acup.gunfeel.mixin.mc;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// В порте Citadel некоторые проходы требуют overlay в шейдере, но забывают
// включить его в RenderSetup. Связываем штатную текстуру, сохраняя сам шейдер.
@Pseudo
@Mixin(targets="com.github.alexmodguy.alexscaves.client.render.ACRenderSetup", remap=false)
public abstract class MixinAlexRenderSetup {
    @Redirect(method="create(Ljava/lang/String;IZZLcom/mojang/blaze3d/pipeline/RenderPipeline;Lcom/github/alexmodguy/alexscaves/client/render/ACRenderSetup$Composite;)Lnet/minecraft/client/renderer/rendertype/RenderType;",
            at=@At(value="INVOKE", target="Lnet/minecraft/client/renderer/rendertype/RenderSetup;builder(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)Lnet/minecraft/client/renderer/rendertype/RenderSetup$RenderSetupBuilder;"))
    private static RenderSetup.RenderSetupBuilder gunfeel$overlay(RenderPipeline pipeline) {
        var builder=RenderSetup.builder(pipeline);
        if(pipeline.getBindGroupLayouts().stream().anyMatch(layout -> layout.getSamplers().contains("Sampler1")))
            builder.useOverlay();
        return builder;
    }
}
