package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.SunBurn;
import net.minecraft.entity.monster.EntityZombie;

// port: the sun's fire in EntityZombie.onLivingUpdate, off under the world's rule (SunBurn)
@Mixin(EntityZombie.class)
public abstract class MixinSunBurnZombie {
    @WrapOperation(method = "onLivingUpdate", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/monster/EntityZombie;setFire(I)V"))
    private void gunfeel$sunFire(EntityZombie self, int seconds, Operation<Void> original) {
        if (SunBurn.burns(self.world)) original.call(self, seconds);
    }
}
