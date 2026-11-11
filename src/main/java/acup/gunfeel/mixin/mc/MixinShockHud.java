package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import acup.gunfeel.shock.client.ShockHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.neoforged.neoforge.client.gui.GuiLayerManager;

// Client: the HUD's layers glitched while shocked (ShockHud)
@Mixin(Hud.class)
public abstract class MixinShockHud {
    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/client/gui/GuiLayerManager;render(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V"))
    private void gunfeel$shock(GuiLayerManager layers, GuiGraphicsExtractor graphics, DeltaTracker delta) {
        ShockHud.extract(layers, graphics, delta);
    }
}
