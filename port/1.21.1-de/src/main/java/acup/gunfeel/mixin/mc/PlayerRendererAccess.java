package acup.gunfeel.mixin.mc;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

// Port only: 1.21.1's PlayerRenderer keeps the model's visibility (hat, jacket, sleeves, spectator) and arm poses in this
// private method, which render() calls before the layers; 26.2 does it in createRenderState (ArmLayers)
@Mixin(PlayerRenderer.class)
public interface PlayerRendererAccess {
    @Invoker("setModelProperties")
    void gunfeel$setModelProperties(AbstractClientPlayer player);
}
