package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.hbm.handler.PlayerShield;

import acup.gunfeel.BalanceKit;

// The balance's shield (BalanceKit): it comes back balance.shield.delay ticks after the last hit (NTM: 60), then
// balance.shield.rate·t a tick (NTM: 0.005).
@Mixin(value = PlayerShield.class, remap = false)
public abstract class MixinBalanceShieldTick {
    @ModifyConstant(method = "tick", constant = @Constant(intValue = 60), require = 2)
    private static int gunfeel$delay(int ntm) {
        return BalanceKit.shieldOn() ? BalanceKit.shield().delay() : ntm;
    }

    @ModifyConstant(method = "tick", constant = @Constant(floatValue = 0.005F), require = 1)
    private static float gunfeel$rate(float ntm) {
        return BalanceKit.shieldOn() ? BalanceKit.shield().rate() : ntm;
    }
}
