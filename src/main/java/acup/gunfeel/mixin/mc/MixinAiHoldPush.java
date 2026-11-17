package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.ai.AiHold;
import net.minecraft.world.entity.Entity;

// Our soldier holding its place in its squad's bounds is not shoved off it by one of its side going past (AiHold): in
// a push between the two only the other is pushed. The player, others' mobs, anything else pushed: as ever.
@Mixin(Entity.class)
public abstract class MixinAiHoldPush {
    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"))
    private void gunfeel$holdBegin(Entity other, CallbackInfo ci) {
        AiHold.begin((Entity) (Object) this, other);
    }

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("RETURN"))
    private void gunfeel$holdEnd(Entity other, CallbackInfo ci) {
        AiHold.end();
    }

    @Inject(method = "push(DDD)V", at = @At("HEAD"), cancellable = true)
    private void gunfeel$holdNotPushed(double x, double y, double z, CallbackInfo ci) {
        if (AiHold.shielded((Entity) (Object) this)) ci.cancel();
    }
}
