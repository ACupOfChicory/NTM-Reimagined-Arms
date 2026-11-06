package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;

// Dev (the armour stand): the texture each layer on the arm binds, marked in ArmLayers' feedback run. Otherwise nothing.
@Mixin(TextureManager.class)
public abstract class MixinTextureBind {
    @Inject(method = "bindTexture", at = @At("HEAD"))
    private void gunfeel$bound(ResourceLocation resource, CallbackInfo ci) {
        acup.gunfeel.ArmLayers.Feedback.texture(resource);
    }
}
