package acup.gunfeel.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The server to a player watching /armsai debug: the tactical map's cells around them, as offsets from origin
 * (each within a byte), with their cover and flags. No cells: the view is off.
 */
public record AiCellsPayload(BlockPos origin, byte[] offsets, int[] cover, short[] flags) implements CustomPacketPayload {
    public static final Type<AiCellsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "ai_cells"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AiCellsPayload> CODEC = CustomPacketPayload.codec(AiCellsPayload::write,
            AiCellsPayload::read);
    /** the most cells one payload carries */
    public static final int LIMIT = 8192;

    public int count() {
        return cover.length;
    }

    private void write(RegistryFriendlyByteBuf b) {
        b.writeBlockPos(origin);
        b.writeVarInt(cover.length);
        b.writeBytes(offsets, 0, cover.length * 3);
        for (int c : cover) b.writeInt(c);
        for (int i = 0; i < cover.length; i++) b.writeShort(flags[i]);
    }

    private static AiCellsPayload read(RegistryFriendlyByteBuf b) {
        BlockPos origin = b.readBlockPos();
        int n = b.readVarInt();
        if (n < 0 || n > LIMIT) throw new IllegalArgumentException("ai_cells: " + n + " cells");
        byte[] offsets = new byte[n * 3];
        b.readBytes(offsets);
        int[] cover = new int[n];
        for (int i = 0; i < n; i++) cover[i] = b.readInt();
        short[] flags = new short[n];
        for (int i = 0; i < n; i++) flags[i] = b.readShort();
        return new AiCellsPayload(origin, offsets, cover, flags);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
