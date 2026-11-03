package acup.gunfeel.mixins;

// port: 26.2's mixin/MixinHbmAnimationPayload.java (0762086) for NTM 1.7.10: the packet is GunAnimationPacketSedna, its
// client handler GunAnimationPacketSedna.Handler (onMessage, the static handleSedna). Client only. Also tells ArmAnims
// the type of each gun animation NTM starts (26.2's animations carry it: HbmAnimationsSedna.getGunAnimation).

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.packet.toclient.GunAnimationPacketSedna;
import com.hbm.render.anim.sedna.AnimationEnums.GunAnimation;

import acup.gunfeel.ArmAnims;
import acup.gunfeel.InstantSwitch;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

@Mixin(value = GunAnimationPacketSedna.Handler.class, remap = false)
public abstract class MixinHbmAnimationPacket {
    private static final String ON_MESSAGE = "onMessage(Lcom/hbm/packet/toclient/GunAnimationPacketSedna;Lnet/minecraftforge/fml/common/network/simpleimpl/MessageContext;)Lnet/minecraftforge/fml/common/network/simpleimpl/IMessage;";

    // instantSwitch: the server's draw for a gun whose draw the client already started is not started again
    @Inject(method = "handleSedna", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$predicted(EntityPlayer player, ItemStack stack, int slot, GunAnimation type, int receiverIndex, int gunIndex,
            CallbackInfo ci) {
        if (InstantSwitch.serverPacket == Thread.currentThread() && !InstantSwitch.accept(slot, stack.getItem(), type, gunIndex)) ci.cancel();
    }

    // arms: a draw cancelled above never gets here
    @Inject(method = "handleSedna", at = @At("RETURN"))
    private static void gunfeel$type(EntityPlayer player, ItemStack stack, int slot, GunAnimation type, int receiverIndex, int gunIndex,
            CallbackInfo ci) {
        ArmAnims.started(slot, gunIndex, type);
    }

    @Inject(method = ON_MESSAGE, at = @At("HEAD"))
    private void gunfeel$fromServer(GunAnimationPacketSedna m, MessageContext ctx, CallbackInfoReturnable<IMessage> cir) {
        InstantSwitch.serverPacket = Thread.currentThread();
    }

    @Inject(method = ON_MESSAGE, at = @At("RETURN"))
    private void gunfeel$fromServerEnd(GunAnimationPacketSedna m, MessageContext ctx, CallbackInfoReturnable<IMessage> cir) {
        InstantSwitch.serverPacket = null;
    }
}
