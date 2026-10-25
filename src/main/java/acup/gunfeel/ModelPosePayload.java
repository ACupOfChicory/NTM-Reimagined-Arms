package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// Client -> server: the rendered pose of one nearby entity. Never a client's claim that a bullet hit something:
// the server intersects its own bullet segment with these cubes. Models and their animation only exist on clients.
public record ModelPosePayload(int entity, UUID uuid, double x, double y, double z, float yaw, List<ModelBox> boxes)
        implements CustomPacketPayload {
    public static final int MAX_BOXES = 128;
    public static final Type<ModelPosePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "model_pose"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ModelPosePayload> CODEC = CustomPacketPayload.codec(ModelPosePayload::write, ModelPosePayload::read);

    public ModelPosePayload {
        boxes = List.copyOf(boxes);
    }

    private void write(RegistryFriendlyByteBuf b) {
        b.writeVarInt(entity);
        b.writeUUID(uuid);
        b.writeDouble(x);
        b.writeDouble(y);
        b.writeDouble(z);
        b.writeFloat(yaw);
        b.writeVarInt(boxes.size());
        for (ModelBox box : boxes) {
            b.writeUtf(box.part(), ModelBox.MAX_PART_NAME);
            for (double n : box.numbers()) b.writeFloat((float) n);
            b.writeLong(box.mesh());
        }
    }

    private static ModelPosePayload read(RegistryFriendlyByteBuf b) {
        int entity = b.readVarInt();
        UUID uuid = b.readUUID();
        double x = b.readDouble(), y = b.readDouble(), z = b.readDouble();
        float yaw = b.readFloat();
        int count = b.readVarInt();
        if (count < 1 || count > MAX_BOXES) throw new IllegalArgumentException("model pose cube count " + count);
        List<ModelBox> boxes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) boxes.add(new ModelBox(b.readUtf(ModelBox.MAX_PART_NAME), b.readFloat(), b.readFloat(), b.readFloat(),
                b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(),
                b.readFloat(), b.readFloat(), b.readFloat(), b.readLong()));
        return new ModelPosePayload(entity, uuid, x, y, z, yaw, boxes);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
