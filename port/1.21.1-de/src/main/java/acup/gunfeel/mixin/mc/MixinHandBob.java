package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import acup.gunfeel.WalkSprint;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;

// The vanilla walking bob of the hands is left out while an NTM gun with walkSway is held: WalkSprint bobs the gun
// itself (as TaCZ does); the camera bobs as before (renderLevel's own bobView). If the call changes, require = 0
// leaves it out. 1.21.1 bobView is (PoseStack, float).
@Mixin(GameRenderer.class)
public abstract class MixinHandBob {
    @Redirect(method = "renderItemInHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"),
            require = 0)
    private void gunfeel$handBob(GameRenderer self, PoseStack pose, float partial) {
        if (WalkSprint.handBobOff()) return;
        ((GameRendererAccess) self).gunfeel$bobView(pose, partial);
    }
}
