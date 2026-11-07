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
        int before=MobBenchMetrics.ENABLED?b.writerIndex():0;
        b.writeLong(id); b.writeVarInt(triangles.length / 9);
        for (float x : triangles) b.writeFloat(x);
        if(MobBenchMetrics.ENABLED) MobBenchMetrics.packet(b.writerIndex()-before);
    }
    private static ModelMeshPayload read(RegistryFriendlyByteBuf b) {
        long id = b.readLong(); int count = b.readVarInt();
        if (count < 1 || count > ModelMesh.MAX_TRIANGLES) throw new IllegalArgumentException("Model mesh triangle count " + count);
        float[] triangles = new float[count * 9];
        for (int i = 0; i < triangles.length; i++) triangles[i] = b.readFloat();
        return new ModelMeshPayload(id, triangles);
    }
    // The server's cache of meshes is shared and never emptied while it runs (a pose names its meshes by id, and a client
    // sends each mesh once a connection). What one player may add to it, and what all players may add together, is
    // bounded, so a client that sends made-up meshes cannot fill the memory or the cache for everyone. An honest client
    // stays far below; a mesh over the bound is dropped and the poses that use it are turned away (ModelBox.valid), so
    // those entities keep their plain box.
    // A failed mesh (its id not its hash, a coordinate out of the box) counts against the player's meshes too. All
    // players together leave a quarter of the cache (4096) to the server's own and a LAN host's meshes.
    static final int PLAYER_MESHES = 1024, PLAYER_TRIANGLES = 1 << 17, SERVER_MESHES = 3072, SERVER_TRIANGLES = 1 << 20;
    private static final java.util.Map<java.util.UUID, int[]> ADDED = new java.util.HashMap<>();
    private static int serverMeshes, serverTriangles;

    // one more mesh of `count` triangles from a player who added `meshes` of `triangles`, all players `serverMeshes`
    // of `server`
    static boolean fits(int meshes, int triangles, int serverMeshes, int server, int count) {
        return meshes < PLAYER_MESHES && triangles + count <= PLAYER_TRIANGLES && serverMeshes < SERVER_MESHES
                && server + count <= SERVER_TRIANGLES;
    }

    // a mesh the server made itself (the bullet check)
    public void receive() { receive(null); }

    // from: the player who sent it; null for the server's own. Never throws: a handler's exception only fills the log.
    public boolean receive(java.util.UUID from) {
        if (!ModelHits.enabled() || ModelMesh.get(id) != null) return true;
        int count = triangles.length / 9;
        synchronized (ADDED) {
            int[] added = from == null ? null : ADDED.computeIfAbsent(from, k -> new int[2]);
            if (from != null && !fits(added[0], added[1], serverMeshes, serverTriangles, count)) {
                ModelHitLog.event("server", "mesh_over_budget", null, () -> "player=" + from + " meshes=" + added[0]
                        + " triangles=" + added[1] + " server_triangles=" + serverTriangles + " new=" + count);
                return false;
            }
            // the id is the hash of the triangles: checked before the tree and the edges are built
            if (ModelMesh.hash(triangles) != id) {
                if (added != null) added[0]++;
                ModelHitLog.event("server", "mesh_rejected", null, () -> "player=" + from + " hash mismatch");
                return false;
            }
            try {
                if (ModelMesh.register(new ModelMesh(triangles)).id() != id) return false;
            } catch (RuntimeException ex) {
                // a coordinate out of the unit box, or the cache full
                if (added != null) added[0]++;
                ModelHitLog.event("server", "mesh_rejected", null, () -> "player=" + from + " " + ex.getMessage());
                return false;
            }
            if (added != null) { added[0]++; added[1] += count; }
            if (added != null) { serverMeshes++; serverTriangles += count; }
            return true;
        }
    }
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
