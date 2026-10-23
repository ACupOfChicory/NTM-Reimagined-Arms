package acup.gunfeel.compat;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// server -> client when a player joins: the server's versions of this mod and of NTM. The client says in chat when
// they differ from its own (CompatClient.hello) and shows them in /gunfeel status. Optional like the mod's other
// payloads: a client or server without the mod still connects.
public record HelloPayload(String mod, String hbm) implements CustomPacketPayload {
    public static final Type<HelloPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "hello"));
    public static final StreamCodec<ByteBuf, HelloPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, HelloPayload::mod, ByteBufCodecs.STRING_UTF8, HelloPayload::hbm, HelloPayload::new);

    public static HelloPayload ours() {
        return new HelloPayload(Compat.modVersion(), Compat.hbmVersion());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
