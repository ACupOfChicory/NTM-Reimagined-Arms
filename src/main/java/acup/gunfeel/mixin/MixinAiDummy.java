package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.entity.mob.EntityDummy;

import acup.gunfeel.ai.AiBots;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

// NTM's test dummy is named by its health ("12.3 / 20.0"), for its name tag. On the server, one the mob AI drives is
// called an AI dummy: in the death message of whoever it shoots. Its tag on the client still shows its health.
@Mixin(value = EntityDummy.class, remap = false)
public abstract class MixinAiDummy {
    @Inject(method = "getName", at = @At("HEAD"), cancellable = true)
    private void gunfeel$aiName(CallbackInfoReturnable<Component> cir) {
        if (AiBots.get((Entity) (Object) this) != null) cir.setReturnValue(Component.translatable("ntmgunfeel.armsai.dummy"));
    }
}
