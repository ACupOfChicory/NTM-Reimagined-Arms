package acup.gunfeel.mixin.mc;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.shock.client.ShockHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.neoforged.neoforge.client.gui.PictureInPictureRendererRegistration;

// Client: the glitched HUD over the frame right before the game's GUI is drawn, and the game's picture-in-picture
// renderers kept for the shock's own GuiRenderer (ShockHud)
@Mixin(GameRenderer.class)
public abstract class MixinShockGuiComposite {
    @Redirect(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/client/ClientHooks;gatherPictureInPictureRenderers(Ljava/util/List;)Ljava/util/List;"))
    private List<PictureInPictureRendererRegistration<?>> gunfeel$pip(List<PictureInPictureRendererRegistration<?>> vanilla) {
        List<PictureInPictureRendererRegistration<?>> all = net.neoforged.neoforge.client.ClientHooks.gatherPictureInPictureRenderers(vanilla);
        ShockHud.pip = all;
        return all;
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
    private void gunfeel$shock(DeltaTracker delta, boolean advance, CallbackInfo ci) {
        ShockHud.beforeGui();
    }
}
