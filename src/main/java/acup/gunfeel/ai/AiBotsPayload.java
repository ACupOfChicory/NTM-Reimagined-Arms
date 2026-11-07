package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The server to a player watching /armsai debug: what the AI mobs round them have in mind. Per mob: its entity id, a
 * few lines of text ('\n' between: what it does, whom it knows of, what it decided and why), where it watches (NaN:
 * nowhere), its cover cell and peek (Long.MIN_VALUE: none) and the path it walks (BlockPos longs). No mobs: none to show.
 */
public record AiBotsPayload(List<Bot> bots) implements CustomPacketPayload {
    public static final Type<AiBotsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "ai_bots"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AiBotsPayload> CODEC = CustomPacketPayload.codec(AiBotsPayload::write,
            AiBotsPayload::read);
    /** the most mobs, path cells a mob and characters of its text one payload carries */
    public static final int LIMIT = 256, PATH = 64, TEXT = 256;

    public record Bot(int entity, String text, double wx, double wy, double wz, long spot, long peek, long[] path) {
    }

    private void write(RegistryFriendlyByteBuf b) {
        b.writeVarInt(bots.size());
        for (Bot m : bots) {
            b.writeVarInt(m.entity);
            b.writeUtf(m.text.length() > TEXT ? m.text.substring(0, TEXT) : m.text, TEXT);
            b.writeDouble(m.wx);
            b.writeDouble(m.wy);
            b.writeDouble(m.wz);
            b.writeLong(m.spot);
            b.writeLong(m.peek);
            int n = Math.min(PATH, m.path.length);
            b.writeVarInt(n);
            for (int i = 0; i < n; i++) b.writeLong(m.path[i]);
        }
    }

    private static AiBotsPayload read(RegistryFriendlyByteBuf b) {
        int n = b.readVarInt();
        if (n < 0 || n > LIMIT) throw new IllegalArgumentException("ai_bots: " + n + " mobs");
        List<Bot> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int id = b.readVarInt();
            String text = b.readUtf(TEXT);
            double x = b.readDouble(), y = b.readDouble(), z = b.readDouble();
            long spot = b.readLong(), peek = b.readLong();
            int p = b.readVarInt();
            if (p < 0 || p > PATH) throw new IllegalArgumentException("ai_bots: a path of " + p);
            long[] path = new long[p];
            for (int k = 0; k < p; k++) path[k] = b.readLong();
            out.add(new Bot(id, text, x, y, z, spot, peek, path));
        }
        return new AiBotsPayload(out);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
