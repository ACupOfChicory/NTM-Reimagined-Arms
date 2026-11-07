package acup.gunfeel.gl54;

import java.util.List;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// server -> client: the layouts an ammo press remembers and its selection index, to a player opening the press's window and
// to the players with it open whenever they change (PressSync)
public record PressLayoutsPayload(BlockPos pos, List<List<String>> layouts, int selected) implements CustomPacketPayload {
    public static final Type<PressLayoutsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "press_layouts"));
    public static final StreamCodec<ByteBuf, PressLayoutsPayload> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, PressLayoutsPayload::pos,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(MagContents.CAPACITY)).apply(ByteBufCodecs.list(PressLayouts.MAX)), PressLayoutsPayload::layouts,
            ByteBufCodecs.INT, PressLayoutsPayload::selected,
            PressLayoutsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
