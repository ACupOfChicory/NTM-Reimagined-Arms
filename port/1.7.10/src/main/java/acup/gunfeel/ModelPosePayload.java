package acup.gunfeel;

// port: copy of 26.2 src/main/java/acup/gunfeel/ModelPosePayload.java at 0762086; changes marked "port:"
// port: the same fields and the same bytes in the same order (FriendlyByteBuf's VarInt, UUID as two longs, UTF as a
// VarInt length and its UTF-8 bytes); written to a Netty ByteBuf for 1.7.10's FML channel (ModelHitNet) instead of a
// StreamCodec. No benchmark counters (MobBenchMetrics.packet).

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.netty.buffer.ByteBuf;

// Client -> server: the rendered pose of one nearby entity. Never a client's claim that a bullet hit something:
// the server intersects its own bullet segment with these cubes. XYZ is the rendered origin, including interpolation.
public record ModelPosePayload(int entity, UUID uuid, double x, double y, double z, float yaw, List<ModelBox> boxes) {
    public static final int MAX_BOXES = 128;

    public ModelPosePayload {
        boxes = List.copyOf(boxes);
    }

    public void write(ByteBuf b) {
        varInt(b, entity);
        b.writeLong(uuid.getMostSignificantBits());
        b.writeLong(uuid.getLeastSignificantBits());
        b.writeDouble(x);
        b.writeDouble(y);
        b.writeDouble(z);
        b.writeFloat(yaw);
        varInt(b, boxes.size());
        for (ModelBox box : boxes) {
            utf(b, box.part(), ModelBox.MAX_PART_NAME);
            for (double n : box.numbers()) b.writeFloat((float) n);
            b.writeLong(box.mesh());
        }
    }

    public static ModelPosePayload read(ByteBuf b) {
        int entity = varInt(b);
        UUID uuid = new UUID(b.readLong(), b.readLong());
        double x = b.readDouble(), y = b.readDouble(), z = b.readDouble();
        float yaw = b.readFloat();
        int count = varInt(b);
        if (count < 1 || count > MAX_BOXES) throw new IllegalArgumentException("model pose cube count " + count);
        List<ModelBox> boxes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) boxes.add(new ModelBox(utf(b, ModelBox.MAX_PART_NAME), b.readFloat(), b.readFloat(), b.readFloat(),
                b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(),
                b.readFloat(), b.readFloat(), b.readFloat(), b.readLong()));
        return new ModelPosePayload(entity, uuid, x, y, z, yaw, boxes);
    }

    // port: FriendlyByteBuf.writeVarInt / readVarInt / writeUtf / readUtf
    static void varInt(ByteBuf b, int value) {
        while ((value & -128) != 0) {
            b.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        b.writeByte(value);
    }

    static int varInt(ByteBuf b) {
        int value = 0, shift = 0;
        byte in;
        do {
            in = b.readByte();
            value |= (in & 127) << shift;
            shift += 7;
            if (shift > 35) throw new IllegalArgumentException("VarInt too big");
        } while ((in & 128) != 0);
        return value;
    }

    static void utf(ByteBuf b, String s, int max) {
        if (s.length() > max) throw new IllegalArgumentException("String too big (" + s.length() + " > " + max + ")");
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        varInt(b, bytes.length);
        b.writeBytes(bytes);
    }

    static String utf(ByteBuf b, int max) {
        int length = varInt(b);
        if (length < 0 || length > max * 3) throw new IllegalArgumentException("String too long: " + length);
        byte[] bytes = new byte[length];
        b.readBytes(bytes);
        String s = new String(bytes, StandardCharsets.UTF_8);
        if (s.length() > max) throw new IllegalArgumentException("String too long: " + s.length() + " > " + max);
        return s;
    }
}
