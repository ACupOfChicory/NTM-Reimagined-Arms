package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import acup.gunfeel.NtmMeshHits;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;

// port/continuum: the armor a mob wears (HumanoidArmorLayer and the rest through EquipmentLayerRenderer) is a model
// of its own, and NtmMeshHits takes every model the mob draws with the part names of its root. The model drawn here
// is the layer's: its parts are named as the layer's draw, so the worn hitbox (WornGear) does not grow them again.
// It is the one NeoForge's getGenericArmorModel returns (a mod's armor may give its own), not the one passed in.
@Mixin(EquipmentLayerRenderer.class)
public abstract class MixinEquipmentLayerModel {
    @ModifyExpressionValue(method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/extensions/common/IClientItemExtensions;getGenericArmorModel(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/client/model/Model;)Lnet/minecraft/client/model/Model;"))
    private Model<?> gunfeel$layer(Model<?> model) {
        NtmMeshHits.layerModel(model);
        return model;
    }
}
