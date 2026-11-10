package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.items.special.Autogen;

import acup.gunfeel.GunMechanisms;

// The new gun mechanisms (GunMechanisms): their shape added to five materials before NTM registers its gun parts from
// the materials' shapes, so NTM registers them in its own namespace.
@Mixin(value = Autogen.class, remap = false)
public abstract class MixinMechanismAutogen {
    @Inject(method = "register(Ljava/lang/String;)V", at = @At("HEAD"), require = 1)
    private static void gunfeel$mechanisms(String namespace, CallbackInfo ci) {
        if ("hbm".equals(namespace)) GunMechanisms.addShapes();
    }
}
