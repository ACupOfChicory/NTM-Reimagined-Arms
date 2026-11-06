package acup.gunfeel.mixin.mc;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;

// the player renderer's layers, run on the arms holding a gun (ArmLayers)
@Mixin(LivingEntityRenderer.class)
public interface LivingEntityRendererAccess {
    @Accessor("layers")
    List<RenderLayer<?, ?>> gunfeel$layers();
}
