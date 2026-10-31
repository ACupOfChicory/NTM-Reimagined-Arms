package acup.gunfeel.mixins.early;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.CameraRecoil;
import acup.gunfeel.Config;
import net.minecraft.client.renderer.EntityRenderer;

@Mixin(EntityRenderer.class)
public abstract class MixinEntityRenderer {
    // The camera's roll shake (26.2: ViewportEvent.ComputeCameraAngles, roll + CameraRecoil.roll): a turn about the view
    // axis before the camera's pitch and yaw, where the hurt shake turns it too. Forge 1.7.10 has no camera event.
    @Inject(method = "orientCamera", at = @At("HEAD"))
    private void gunfeel$roll(float partial, CallbackInfo ci) {
        if (Config.enabled && CameraRecoil.roll != 0F) GL11.glRotatef(CameraRecoil.roll, 0F, 0F, 1F);
    }
}
