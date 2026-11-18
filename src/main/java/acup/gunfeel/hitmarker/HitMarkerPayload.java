package acup.gunfeel.hitmarker;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** The server to a player whose shot just hurt someone (HitMarkers): on the head, and whether it killed */
public record HitMarkerPayload(boolean head, boolean kill) implements CustomPacketPayload {
    public static final Type<HitMarkerPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "hit_marker"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HitMarkerPayload> CODEC = CustomPacketPayload.codec(
            (p, b) -> b.writeByte((p.head ? 1 : 0) | (p.kill ? 2 : 0)), b -> {
                int f = b.readByte();
                return new HitMarkerPayload((f & 1) != 0, (f & 2) != 0);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
