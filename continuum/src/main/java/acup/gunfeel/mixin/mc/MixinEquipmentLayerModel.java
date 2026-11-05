package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.NtmMeshHits;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.EquipmentAsset;

// port/continuum: the armor a mob wears (HumanoidArmorLayer and the rest through EquipmentLayerRenderer) is a model
// of its own, and NtmMeshHits takes every model the mob draws with the part names of its root. The models drawn here
// are the layer's: their parts are named as the layer's draw, so the worn hitbox (WornGear) does not grow them again.
@Mixin(EquipmentLayerRenderer.class)
public abstract class MixinEquipmentLayerModel {
    @Inject(method = "renderLayers(Lnet/minecraft/client/resources/model/EquipmentClientInfo$LayerType;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/world/item/ItemStack;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/resources/Identifier;II)V",
            at = @At("HEAD"))
    private <S> void gunfeel$layer(EquipmentClientInfo.LayerType type, ResourceKey<EquipmentAsset> asset, Model<? super S> model,
            S state, ItemStack stack, PoseStack pose, SubmitNodeCollector collector, int light, Identifier texture, int outline,
            int color, CallbackInfo ci) {
        NtmMeshHits.layerModel(model);
    }
}
