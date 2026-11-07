package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import acup.gunfeel.NtmMeshHits;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;

// The armor a mob wears (HumanoidArmorLayer and the rest through EquipmentLayerRenderer) is a model of its own, and a
// mob drawn without Flywheel (a player, a mannequin) has NtmMeshHits take every model it draws with the part names of
// its root. The model drawn here is the layer's: its parts are named as the layer's draw (NtmMeshHits.ARMOR_SUFFIX),
// so the hitbox keeps the armor as it is drawn and does not take it for the body. It is the one NeoForge's
// getGenericArmorModel returns (a mod's armor may give its own), not the one passed in. Flywheel names its armor
// layer's draws itself.
@Mixin(EquipmentLayerRenderer.class)
public abstract class MixinEquipmentLayerModel {
    @ModifyExpressionValue(method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/client/extensions/common/IClientItemExtensions;getGenericArmorModel(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/client/model/Model;)Lnet/minecraft/client/model/Model;"))
    private Model<?> gunfeel$layer(Model<?> model) {
        NtmMeshHits.layerModel(model);
        return model;
    }
}
