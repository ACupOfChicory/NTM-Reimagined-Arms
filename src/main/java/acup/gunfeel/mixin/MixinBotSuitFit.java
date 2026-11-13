package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.hbm.client.render.ArmorWorldRenderer;

import acup.gunfeel.ModelPoseCapture;
import acup.gunfeel.ai.client.BotModel;
import net.minecraft.client.model.geom.ModelPart;

// NTM fits a suit's own mesh (HEV, T-51 and the rest) to the limb under it by the limb's largest box of its own: the
// boot's sole goes to that box's bottom. The FBI agent's and the test dummy's legs and arms are split (BotModel): the
// thigh is the leg's own box, the shin its child, so the legs and boots sat 6 px up, on the knees. The fit measures the
// whole straight limb instead; the mesh is still posed by the limb itself.
@Mixin(value = ArmorWorldRenderer.class, remap = false)
public abstract class MixinBotSuitFit {
    @ModifyVariable(method = "fit", at = @At("HEAD"), argsOnly = true, require = 1)
    private static ModelPart gunfeel$wholeLimb(ModelPart limb) {
        return BotModel.whole(limb, ModelPoseCapture.ACCESS);
    }
}
