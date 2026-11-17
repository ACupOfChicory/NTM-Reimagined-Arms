package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.handler.HazmatRegistry;
import com.hbm.util.ContaminationUtil;

import acup.gunfeel.ai.AiBots;
import acup.gunfeel.ai.AiRadiation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

// A mob AI soldier's armour keeps radiation out as a player's does (AiRadiation); NTM's own path for players and every
// other mob is left as it is.
@Mixin(value = ContaminationUtil.class, remap = false)
public abstract class MixinAiRadiation {
    @Inject(method = "calculateRadiationMod", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$soldier(LivingEntity entity, CallbackInfoReturnable<Double> cir) {
        if (entity instanceof Player || AiBots.get(entity) == null) return;
        cir.setReturnValue(AiRadiation.mod(HazmatRegistry.getResistance(entity)));
    }
}
