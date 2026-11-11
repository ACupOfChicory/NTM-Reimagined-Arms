package acup.gunfeel;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// The server to the players near a blast (SuppressionServer): where it went off and how big, for Suppression
public record SuppressionBlastPayload(double x, double y, double z, float size) implements CustomPacketPayload {
    public static final Type<SuppressionBlastPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "suppression_blast"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SuppressionBlastPayload> CODEC = CustomPacketPayload.codec(SuppressionBlastPayload::write,
            SuppressionBlastPayload::read);

    private void write(RegistryFriendlyByteBuf b) {
        b.writeDouble(x);
        b.writeDouble(y);
        b.writeDouble(z);
        b.writeFloat(size);
    }

    private static SuppressionBlastPayload read(RegistryFriendlyByteBuf b) {
        return new SuppressionBlastPayload(b.readDouble(), b.readDouble(), b.readDouble(), b.readFloat());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
