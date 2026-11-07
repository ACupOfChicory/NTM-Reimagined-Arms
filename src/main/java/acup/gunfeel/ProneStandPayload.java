package acup.gunfeel;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// server -> client: the server has the player standing (it did not let it lie down, or got it up), so the client gets
// up too and does not answer (ProneClient.serverStood). TaCZ has no such packet.
public record ProneStandPayload() implements CustomPacketPayload {
    public static final ProneStandPayload INSTANCE = new ProneStandPayload();
    public static final Type<ProneStandPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "prone_stand"));
    public static final StreamCodec<ByteBuf, ProneStandPayload> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
