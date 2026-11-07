package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.util.DamageResistanceHandler;

import acup.gunfeel.gas.GasServer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

// The GL-54's acid: the threshold and the percentage of the armour NTM adds up (DT and DR) go down by the corrosion K the
// acid has put on the wearer, (1 - K) of them are left. The armour itself is not touched.
@Mixin(value = DamageResistanceHandler.class, remap = false)
public abstract class MixinGasCorrosion {
    @Inject(method = "getDTDR", at = @At("RETURN"), require = 0)
    private static void gunfeel$corrode(LivingEntity entity, DamageSource damage, float amount, float pierceDT, float pierce, CallbackInfoReturnable<float[]> cir) {
        float k = GasServer.corrosion(entity);
        if (k <= 0F) return;
        float[] v = cir.getReturnValue();
        v[0] = acup.gunfeel.gas.GasModel.corrode(v[0], k);
        v[1] = acup.gunfeel.gas.GasModel.corrode(v[1], k);
    }
}
