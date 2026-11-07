package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.mob.EntityFBI;

import acup.gunfeel.ai.AiBots;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

// NTM's FBI agent picks the nearest player within 128 blocks as its target every tick, seen or not, and walks
// straight at them. One the mob AI drives knows only what it sees and hears: that step is skipped for it.
@Mixin(value = EntityFBI.class, remap = false)
public abstract class MixinAiFbi {
    @Inject(method = "customServerAiStep", at = @At("HEAD"), cancellable = true)
    private void gunfeel$aiBot(ServerLevel level, CallbackInfo ci) {
        if (AiBots.get((Entity) (Object) this) != null) ci.cancel();
    }
}
