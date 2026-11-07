package acup.gunfeel.gl54;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// client -> server: the wheel turned while the held GL-54 is aimed; shifts the burst of the stored range one block (the sign of
// `delta`: up is farther). The server checks the gun is held and aimed (Gl54Range.shift).
public record Gl54OffsetPayload(int delta) implements CustomPacketPayload {
    public static final Type<Gl54OffsetPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "gl54_offset"));
    public static final StreamCodec<ByteBuf, Gl54OffsetPayload> CODEC = ByteBufCodecs.VAR_INT.map(Gl54OffsetPayload::new, Gl54OffsetPayload::delta);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
