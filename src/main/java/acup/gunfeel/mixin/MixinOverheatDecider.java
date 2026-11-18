package acup.gunfeel.mixin;

import java.util.function.BooleanSupplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.GunStateDecider;

import acup.gunfeel.heat.OverheatServer;
import net.minecraft.world.item.ItemStack;

// The barrel's heat: a stoppage the last shot set (OverheatServer.shot) is NTM's own jam, played where its decider would
// fire again after that shot's cooldown; anything else as NTM has it
@Mixin(value = GunStateDecider.class, remap = false)
public abstract class MixinOverheatDecider {
    @Inject(method = "deciderAutoRefire", at = @At("HEAD"), cancellable = true, require = 1)
    private static void gunfeel$heatJam(ItemStack stack, ItemGunBaseNT.LambdaContext ctx, ItemGunBaseNT.GunState lastState, int recIndex, int gunIndex,
            BooleanSupplier refireCondition, CallbackInfo ci) {
        if (lastState == ItemGunBaseNT.GunState.COOLDOWN && OverheatServer.jam(stack, ctx, recIndex, gunIndex)) ci.cancel();
    }
}
