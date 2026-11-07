package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.packet.KeybindPacket;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.ServerKeys;
import net.minecraft.entity.player.EntityPlayer;

// port only (NTM CE's own bug; 26.2 and NTM 1.7.10's KeybindPacket are not this one): the server presses a player's
// NTM key on its own thread, not on the network thread the packet came in on (ServerKeys). Put in only while NTM CE
// does not do so itself (ServerKeys.needed, read from its bytecode). require = 0: an NTM that queues the press itself has
// the call in a lambda, not in onMessage, and the compat check takes the miss as soft, not as a system that is off.
@Mixin(value = KeybindPacket.Handler.class, remap = false)
public abstract class MixinKeysOnServerThread {
    @WrapOperation(require = 0, method = "onMessage(Lcom/hbm/packet/KeybindPacket;Lnet/minecraftforge/fml/common/network/simpleimpl/MessageContext;)Lnet/minecraftforge/fml/common/network/simpleimpl/IMessage;",
            at = @At(value = "INVOKE", target = "Lcom/hbm/handler/HbmKeybindsServer;onPressedServer(Lnet/minecraft/entity/player/EntityPlayer;Lcom/hbm/handler/HbmKeybinds$EnumKeybind;Z)V"))
    private void gunfeel$onServerThread(EntityPlayer player, EnumKeybind key, boolean pressed, Operation<Void> original) {
        ServerKeys.press(player, () -> original.call(player, key, pressed));
    }
}
