package acup.gunfeel;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// The server to a player just hit by an NTM gun (GunHit): the hit the game told them of right before was a gun's
public record GunHitPayload() implements CustomPacketPayload {
    public static final GunHitPayload INSTANCE = new GunHitPayload();
    public static final Type<GunHitPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "gun_hit"));
    public static final StreamCodec<ByteBuf, GunHitPayload> CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
