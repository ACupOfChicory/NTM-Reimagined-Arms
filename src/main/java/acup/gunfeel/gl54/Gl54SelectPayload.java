package acup.gunfeel.gl54;

import java.util.List;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// client -> server: the radial menu chose magazines with exactly these grenades (in fire order) for the held GL-54;
// GrenadeMagazine.select checks that the inventory has one and loads it with R
public record Gl54SelectPayload(List<String> contents) implements CustomPacketPayload {
    public static final Type<Gl54SelectPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "gl54_select"));
    public static final StreamCodec<ByteBuf, Gl54SelectPayload> CODEC =
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(MagContents.CAPACITY)).map(Gl54SelectPayload::new, Gl54SelectPayload::contents);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
