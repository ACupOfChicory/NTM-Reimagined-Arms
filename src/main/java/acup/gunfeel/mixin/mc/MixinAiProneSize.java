package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import acup.gunfeel.ai.AiProne;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;

// A lying AI mob (the crawling pose, which only a player takes in the game) gets a crawling player's size: 0.6 across
// and high, eyes at 0.4. The game sizes a mob by its type whatever its pose; the pose syncs, so both sides agree.
@Mixin(LivingEntity.class)
public abstract class MixinAiProneSize {
    @Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true)
    private void gunfeel$lyingSize(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        if (pose == Pose.SWIMMING && (Object) this instanceof Mob) cir.setReturnValue(AiProne.SIZE);
    }
}
