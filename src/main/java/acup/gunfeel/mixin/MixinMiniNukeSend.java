package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.handler.threading.TargetPoint;
import com.hbm.packet.PacketWire;
import com.hbm.packet.threading.ThreadedPayload;

import acup.gunfeel.nuke.MiniNukeCloud;

// Server: NTM's muke packet (the particles of every small nuclear blast) not sent; the cloud of NTM's bombs spawned in
// its place (MiniNukeCloud), where a player would see it. Any other packet goes out untouched. Not applied: NTM's muke
// particles stay.
@Mixin(value = PacketWire.class, remap = false)
public abstract class MixinMiniNukeSend {
    @Inject(method = "sendToAllAround(Lcom/hbm/packet/threading/ThreadedPayload;Lcom/hbm/handler/threading/TargetPoint;)V",
            at = @At("HEAD"), cancellable = true)
    private static void gunfeel$muke(ThreadedPayload payload, TargetPoint tp, CallbackInfo ci) {
        if (!MiniNukeCloud.takes(payload) || tp == null || tp.level() == null || MiniNukeCloud.passing() || !MiniNukeCloud.on()) return;
        MukePayloadAccess m = (MukePayloadAccess) (Object) payload;
        if (MiniNukeCloud.fromMuke(tp.level(), m.gunfeel$x(), m.gunfeel$y(), m.gunfeel$z(), m.gunfeel$tinytot(), m.gunfeel$balefire())) ci.cancel();
    }
}
