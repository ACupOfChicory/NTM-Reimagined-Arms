package acup.gunfeel.shock;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** The shock's packets, server to client (ShockServer, ShockClient). */
public final class ShockPayloads {
    private ShockPayloads() {
    }

    /** the player was shocked: for how many ticks */
    public record Shock(int ticks) implements CustomPacketPayload {
        public static final Type<Shock> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "shock"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Shock> CODEC = CustomPacketPayload.codec((p, b) -> b.writeVarInt(p.ticks),
                b -> new Shock(b.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** the player's shock shell hit someone; took: it shocked them (false: they were shocked or immune already) */
    public record Mark(boolean took) implements CustomPacketPayload {
        public static final Type<Mark> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "shock_mark"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Mark> CODEC = CustomPacketPayload.codec((p, b) -> b.writeBoolean(p.took),
                b -> new Mark(b.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
