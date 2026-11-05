package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import acup.gunfeel.ModelHitClient;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.EntityEquipmentSlot;

// port: the worn armor as the layer draws it (vanilla's, an NTM suit's own model and OBJ parts), taken as a pass of the
// model being drawn, as 26.2 takes its armor layer's draws: root/<part>/armor/<slot>/draws/0. WornGear leaves these
// as drawn. The enchantment glint (renderEnchantedGlint) is the same model again and is not taken.
@Mixin(LayerArmorBase.class)
public abstract class MixinLayerArmor {
    @WrapOperation(method = "renderArmorLayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/model/ModelBase;render(Lnet/minecraft/entity/Entity;FFFFFF)V"))
    private void gunfeel$pass(ModelBase model, Entity entity, float a, float b, float c, float d, float e, float f,
            Operation<Void> original, @Local(argsOnly = true) EntityEquipmentSlot slot) {
        ModelHitClient.pass(model, "/armor/" + slot.getName() + "/draws/0");
        try {
            original.call(model, entity, a, b, c, d, e, f);
        } finally {
            ModelHitClient.passEnd();
        }
    }
}
