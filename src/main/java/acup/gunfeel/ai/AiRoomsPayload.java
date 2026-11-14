package acup.gunfeel.ai;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The server to a player watching /armsai debug rooms: the buildings round them as the AI reads them (AiRooms). Marks,
 * each a place as an offset from origin (within a byte each way), a kind and a value; and labels, a place and a short
 * text. No marks and no labels: the view is off.
 */
public record AiRoomsPayload(BlockPos origin, byte[] marks, List<Label> labels) implements CustomPacketPayload {
    public static final Type<AiRoomsPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "ai_rooms"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AiRoomsPayload> CODEC = CustomPacketPayload.codec(AiRoomsPayload::write, AiRoomsPayload::read);
    /** bytes a mark; the most marks and labels one payload carries, the most characters of a label */
    public static final int MARK = 5, LIMIT = 16384, LABELS = 256, TEXT = 48;

    /** a room's cell: the value its number among those shown (its colour) */
    public static final byte ROOM = 0;
    /** a way's cell: bit 0 a door stands in it, bit 1 it leads out from under the roof, bit 2 it goes over a sill (a window climbed through) */
    public static final byte WAY = 1;
    /** a cell beside a way, where a squad stacks up: 0 on the side out of the room (or of its first room), 1 on the other */
    public static final byte BESIDE = 2;
    /** a cell of a stair or a ladder: 0 a step up or down, 1 climbed */
    public static final byte STAIRS = 3;
    /** a cell under a roof that is no building's (a cave, under a tree) */
    public static final byte WILD = 4;
    /** a window: the wall's block at head height; the value the direction out of the room (Dirs), bit 3 glazed */
    public static final byte WINDOW = 5;
    /** a block of a room's shell: the value its kind (AiBlockKinds) */
    public static final byte WALL = 6;

    /**
     * /armsai debug terrain: a coarse cell of the terrain layers, its corner; bits 0-4 how far one sees from it in fours
     * of blocks, bits 5-6 what it is ({@link #FLAT}, {@link #HIGH}, {@link #HOLLOW}, {@link #WET}), bit 7 under the crowns of trees
     */
    public static final byte TERRAIN = 7;
    public static final int FLAT = 0, HIGH = 1, HOLLOW = 2, WET = 3;

    public record Label(BlockPos at, String text, int colour) {
    }

    public int count() {
        return marks.length / MARK;
    }

    private void write(RegistryFriendlyByteBuf b) {
        b.writeBlockPos(origin);
        b.writeVarInt(count());
        b.writeBytes(marks, 0, count() * MARK);
        b.writeVarInt(labels.size());
        for (Label l : labels) {
            b.writeBlockPos(l.at);
            b.writeUtf(l.text.length() > TEXT ? l.text.substring(0, TEXT) : l.text, TEXT);
            b.writeInt(l.colour);
        }
    }

    private static AiRoomsPayload read(RegistryFriendlyByteBuf b) {
        BlockPos origin = b.readBlockPos();
        int n = b.readVarInt();
        if (n < 0 || n > LIMIT) throw new IllegalArgumentException("ai_rooms: " + n + " marks");
        byte[] marks = new byte[n * MARK];
        b.readBytes(marks);
        int k = b.readVarInt();
        if (k < 0 || k > LABELS) throw new IllegalArgumentException("ai_rooms: " + k + " labels");
        List<Label> labels = new ArrayList<>(k);
        for (int i = 0; i < k; i++) labels.add(new Label(b.readBlockPos(), b.readUtf(TEXT), b.readInt()));
        return new AiRoomsPayload(origin, marks, labels);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
