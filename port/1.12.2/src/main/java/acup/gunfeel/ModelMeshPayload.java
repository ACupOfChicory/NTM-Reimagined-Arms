package acup.gunfeel;

// port: copy of 26.2 src/main/java/acup/gunfeel/ModelMeshPayload.java at 0762086; changes marked "port:"
// port: the same bytes on a Netty ByteBuf (ModelHitNet) instead of a StreamCodec; no benchmark counters.

import io.netty.buffer.ByteBuf;

// Геометрия передаётся один раз перед первой позой, затем сеть несёт только ID и матрицы частей.
public record ModelMeshPayload(long id, float[] triangles) {
    public ModelMeshPayload { triangles = triangles.clone(); }
    public void write(ByteBuf b) {
        b.writeLong(id); ModelPosePayload.varInt(b, triangles.length / 9);
        for (float x : triangles) b.writeFloat(x);
    }
    public static ModelMeshPayload read(ByteBuf b) {
        long id = b.readLong(); int count = ModelPosePayload.varInt(b);
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
}
