package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.minecraft.client.renderer.GameRenderer;

// 26.2 keeps the hand's field of view as Camera.hudFov; 1.21.1 works it out in renderItemInHand and builds the hand's
// projection from it: kept here as it was drawn (port/Mc.handFov, for the tracers' muzzle). Asked again later it could
// differ: NTM's model FOV follows the aiming, which moves while the hand is drawn. If the call changes, require = 0
// leaves it out and the tracers take 70.
@Mixin(GameRenderer.class)
public abstract class MixinHandFov {
    @ModifyArg(method = "renderItemInHand", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;getProjectionMatrix(D)Lorg/joml/Matrix4f;"), require = 0)
    private double gunfeel$handFov(double fov) {
        acup.gunfeel.port.Mc.handProjection(fov);
        return fov;
    }
}
