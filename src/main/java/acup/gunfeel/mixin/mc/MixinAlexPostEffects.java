package acup.gunfeel.mixin.mc;

import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets="com.github.alexmodguy.alexscaves.citadel.client.shader.PostEffectRegistry", remap=false)
public abstract class MixinAlexPostEffects {
    @Redirect(method="blitEffect", at=@At(value="INVOKE",
            target="Lcom/mojang/blaze3d/systems/RenderPass;draw(IIII)V"))
    private static void gunfeel$uniforms(RenderPass pass,int count,int instances,int first,int firstInstance) {
        RenderSystem.bindDefaultUniforms(pass);
        pass.draw(count,instances,first,firstInstance);
    }
}
