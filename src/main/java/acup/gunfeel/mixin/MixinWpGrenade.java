package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.entity.grenade.EntityGrenadeUniversal;
import com.hbm.handler.threading.TargetPoint;
import com.hbm.items.weapon.grenade.ItemGrenadeFilling;
import com.hbm.packet.threading.ThreadedPayload;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import acup.gunfeel.gas.WpSmoke;

// Server: NTM's white phosphorus hand grenade lets this mod's white smoke out in place of its three hazes (the blast,
// the fire on the ground and the blocks set alight as they were). With smoke off, or the spot shut in, the hazes go.
@Mixin(value = ItemGrenadeFilling.class, remap = false)
public abstract class MixinWpGrenade {
    @WrapOperation(method = "explodeWhitePhosphorus",
            at = @At(value = "INVOKE", target = "Lcom/hbm/packet/PacketWire;sendToAllAround(Lcom/hbm/packet/threading/ThreadedPayload;Lcom/hbm/handler/threading/TargetPoint;)V"))
    private static void gunfeel$smoke(ThreadedPayload haze, TargetPoint around, Operation<Void> original,
            @Local(argsOnly = true) EntityGrenadeUniversal grenade) {
        if (!WpSmoke.haze(grenade)) original.call(haze, around);
    }
}
