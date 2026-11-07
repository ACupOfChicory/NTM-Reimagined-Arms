package acup.gunfeel;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// client -> server: the player lay down (true) or got up (false) with the prone key (Prone.receive); TaCZ's
// ClientMessagePlayerCrawl. The server checks its own rules before it lets the player lie down; getting up it always lets.
public record ProneCrawlPayload(boolean crawl) implements CustomPacketPayload {
    public static final Type<ProneCrawlPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "prone"));
    public static final StreamCodec<ByteBuf, ProneCrawlPayload> CODEC = ByteBufCodecs.BOOL.map(ProneCrawlPayload::new, ProneCrawlPayload::crawl);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
