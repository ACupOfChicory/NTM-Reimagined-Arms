package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.model.ModelBiped;
import net.minecraft.entity.Entity;

// port: while ArmLayers draws the chestplate on an arm holding a gun, a ModelBiped it draws takes the pose of the posed
// player model at the end of setRotationAngles (26.2: the armour models copy the parent model's pose; as 1.12.2's).
// Otherwise nothing.
@Mixin(ModelBiped.class)
public abstract class MixinModelBipedPose {
    @Inject(method = "setRotationAngles", at = @At("RETURN"))
    private void gunfeel$armPose(float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch,
            float scale, Entity entity, CallbackInfo ci) {
        acup.gunfeel.ArmLayers.follow((ModelBiped) (Object) this);
    }
}
