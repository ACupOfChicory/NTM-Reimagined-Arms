package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import acup.gunfeel.Suppression;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;

// Client: under suppression the sounds go quieter (Suppression.volume): as a sound starts, and for those already
// playing whenever the game works their volume out again (a moving sound each tick, all of them when Suppression asks)
@Mixin(SoundEngine.class)
public abstract class MixinSuppressionMuffle {
    @WrapOperation(method = "play", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/SoundEngine;calculateVolume(FLnet/minecraft/sounds/SoundSource;)F"))
    private float gunfeel$start(SoundEngine self, float volume, SoundSource source, Operation<Float> original,
            @Local(argsOnly = true) SoundInstance instance) {
        return original.call(self, volume, source) * Suppression.volume(instance);
    }

    @Inject(method = "calculateVolume(Lnet/minecraft/client/resources/sounds/SoundInstance;)F", at = @At("RETURN"), cancellable = true)
    private void gunfeel$playing(SoundInstance instance, CallbackInfoReturnable<Float> cir) {
        float k = Suppression.volume(instance);
        if (k < 1F) cir.setReturnValue(cir.getReturnValueF() * k);
    }
}
