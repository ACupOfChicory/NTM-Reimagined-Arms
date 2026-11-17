package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.client.TorexClientFX;
import com.hbm.entity.effect.EntityNukeTorex;

import acup.gunfeel.nuke.MiniNukeCloud;

// Client: a small nuclear blast's cloud (MiniNukeCloud) without a bomb's screen flash, its sound (NTM's nuclear
// explosion at volume 10000; the small blast plays its own) and its shake. The sky's flash stays. Not applied: a
// bomb's flash, sound and shake with each small blast.
@Mixin(value = TorexClientFX.class, remap = false)
public abstract class MixinMiniNukeFx {
    @Inject(method = "hudFlash(Lcom/hbm/entity/effect/EntityNukeTorex;)V", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$flash(EntityNukeTorex torex, CallbackInfo ci) {
        if (MiniNukeCloud.mini(torex)) ci.cancel();
    }

    @Inject(method = "shockwaveArrival(Lcom/hbm/entity/effect/EntityNukeTorex;)V", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$arrival(EntityNukeTorex torex, CallbackInfo ci) {
        if (MiniNukeCloud.mini(torex)) ci.cancel();
    }
}
