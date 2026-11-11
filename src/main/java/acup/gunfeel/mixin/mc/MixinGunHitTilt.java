package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.GunHitClient;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.state.level.CameraRenderState;

// Client: no tilt of the view for a hit the server said was an NTM gun's (GunHitClient)
@Mixin(Camera.class)
public abstract class MixinGunHitTilt {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void gunfeel$tilt(CameraRenderState state, float partial, CallbackInfo ci) {
        GunHitClient.camera(((Camera) (Object) this).entity(), state);
    }
}
