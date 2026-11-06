package acup.gunfeel.mixins.early;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;

// the player renderer's layers, run on the arms holding a gun (ArmLayers; 26.2: LivingEntityRendererAccess)
@Mixin(RenderLivingBase.class)
public interface RenderLivingAccess {
    @Accessor("layerRenderers")
    List<LayerRenderer<?>> gunfeel$layers();
}
