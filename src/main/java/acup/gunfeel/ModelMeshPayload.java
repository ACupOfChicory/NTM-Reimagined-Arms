package acup.gunfeel;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

// Геометрия передаётся один раз перед первой позой, затем сеть несёт только ID и матрицы частей.
public record ModelMeshPayload(long id, float[] triangles) implements CustomPacketPayload {
    public static final Type<ModelMeshPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("ntmgunfeel", "model_mesh"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ModelMeshPayload> CODEC = CustomPacketPayload.codec(ModelMeshPayload::write, ModelMeshPayload::read);
    public ModelMeshPayload { triangles = triangles.clone(); }
    private void write(RegistryFriendlyByteBuf b) {
        b.writeLong(id); b.writeVarInt(triangles.length / 9);
        for (float x : triangles) b.writeFloat(x);
    }
    private static ModelMeshPayload read(RegistryFriendlyByteBuf b) {
        long id = b.readLong(); int count = b.readVarInt();
        if (count < 1 || count > ModelMesh.MAX_TRIANGLES) throw new IllegalArgumentException("Model mesh triangle count " + count);
        float[] triangles = new float[count * 9];
        for (int i = 0; i < triangles.length; i++) triangles[i] = b.readFloat();
        return new ModelMeshPayload(id, triangles);
    }
    public void receive() {
        if (!ModelHits.enabled() || ModelMesh.get(id) != null) return;
        ModelMesh mesh = new ModelMesh(triangles);
        if (mesh.id() != id) throw new IllegalArgumentException("Model mesh hash mismatch");
        ModelMesh.register(mesh);
    }
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
