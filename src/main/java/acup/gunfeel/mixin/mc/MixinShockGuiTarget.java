package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.mojang.blaze3d.pipeline.RenderTarget;

import acup.gunfeel.shock.client.ShockHud;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;

// Client: the shock's own GuiRenderer draws the HUD into its texture, not the screen (ShockHud)
@Mixin(GuiRenderer.class)
public abstract class MixinShockGuiTarget {
    @Redirect(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private RenderTarget gunfeel$target(GameRenderer game) {
        return ShockHud.target((GuiRenderer) (Object) this, game.mainRenderTarget());
    }
}
