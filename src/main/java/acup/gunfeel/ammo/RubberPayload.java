package acup.gunfeel.ammo;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** server to client: a rubber slug hit the player, its controls are mixed up for {@code ticks} game ticks (RubberClient) */
public record RubberPayload(int ticks) implements CustomPacketPayload {
    public static final Type<RubberPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "rubber_confuse"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RubberPayload> CODEC = CustomPacketPayload.codec((p, b) -> b.writeVarInt(p.ticks),
            b -> new RubberPayload(b.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
