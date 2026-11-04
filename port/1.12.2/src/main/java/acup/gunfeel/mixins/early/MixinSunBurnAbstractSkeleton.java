package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.SunBurn;
import net.minecraft.entity.monster.AbstractSkeleton;

// port: the sun's fire in AbstractSkeleton.onLivingUpdate, off under the world's rule (SunBurn)
@Mixin(AbstractSkeleton.class)
public abstract class MixinSunBurnAbstractSkeleton {
    @WrapOperation(method = "onLivingUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/monster/AbstractSkeleton;setFire(I)V"))
    private void gunfeel$sunFire(AbstractSkeleton self, int seconds, Operation<Void> original) {
        if (SunBurn.burns(self.world)) original.call(self, seconds);
    }
}
