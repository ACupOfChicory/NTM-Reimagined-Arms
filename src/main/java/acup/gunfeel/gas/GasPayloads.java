package acup.gunfeel.gas;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** The gases' packets to the player they are working on: the doses the HUD draws, and each cough. */
public final class GasPayloads {
    private GasPayloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String name) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", name));
    }

    /** The player's doses (by {@link GasKind#ordinal()}) and the acid's K, with whether sprinting is blocked. */
    public record State(float[] dose, float k, boolean noSprint) implements CustomPacketPayload {
        public static final Type<State> TYPE = typeOf("gas_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = CustomPacketPayload.codec(State::write, State::read);

        private void write(RegistryFriendlyByteBuf b) {
            for (int i = 0; i < GasState.N; i++) b.writeFloat(i < dose.length ? dose[i] : 0F);
            b.writeFloat(k);
            b.writeBoolean(noSprint);
        }

        private static State read(RegistryFriendlyByteBuf b) {
            float[] d = new float[GasState.N];
            for (int i = 0; i < d.length; i++) d[i] = b.readFloat();
            return new State(d, b.readFloat(), b.readBoolean());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A cough: the camera shakes for that many ticks and, when asked, aiming is shut meanwhile. */
    public record Cough(int ticks, boolean blockAim) implements CustomPacketPayload {
        public static final Type<Cough> TYPE = typeOf("gas_cough");
        public static final StreamCodec<RegistryFriendlyByteBuf, Cough> CODEC = CustomPacketPayload.codec(
                (p, b) -> {
                    b.writeVarInt(p.ticks);
                    b.writeBoolean(p.blockAim);
                }, b -> new Cough(b.readVarInt(), b.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
