package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.hbm.packet.toclient.MukePayload;

// NTM's muke packet's fields (package-private), read when MixinMiniNukeSend takes the packet over
@Mixin(value = MukePayload.class, remap = false)
public interface MukePayloadAccess {
    @Accessor("x")
    double gunfeel$x();

    @Accessor("y")
    double gunfeel$y();

    @Accessor("z")
    double gunfeel$z();

    @Accessor("tinytot")
    boolean gunfeel$tinytot();

    @Accessor("balefire")
    boolean gunfeel$balefire();
}
