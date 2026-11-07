package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class ModelMeshPayloadTest {
    @Test void geometryRoundTripPreservesIdentityAndTriangles() {
        var mesh = ModelMeshTest.tetrahedron();
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            ModelMeshPayload.CODEC.encode(buf, new ModelMeshPayload(mesh.id(), mesh.triangles()));
            var packet = ModelMeshPayload.CODEC.decode(buf);
            assertEquals(mesh.id(), packet.id());
            assertArrayEquals(mesh.triangles(), packet.triangles());
            assertEquals(mesh.id(), new ModelMesh(packet.triangles()).id());
            assertEquals(0, buf.readableBytes());
        } finally { buf.release(); }
    }

    @Test void largestBlueprintFitsServerboundLimit() {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            ModelMeshPayload.CODEC.encode(buf, new ModelMeshPayload(1, new float[ModelMesh.MAX_TRIANGLES * 9]));
            assertTrue(buf.readableBytes() < 32_767);
            assertEquals(ModelMesh.MAX_TRIANGLES * 9, ModelMeshPayload.CODEC.decode(buf).triangles().length);
        } finally { buf.release(); }
    }

    @Test void invalidGeometryAndCountAreRejected() {
        for (float invalid : new float[] {Float.NaN, Float.POSITIVE_INFINITY, -1, 2}) {
            float[] data = new float[9]; data[0] = invalid;
            assertThrows(IllegalArgumentException.class, () -> new ModelMesh(data));
        }
        for (int count : new int[] {-1, 0, ModelMesh.MAX_TRIANGLES + 1, Integer.MAX_VALUE}) {
            var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                buf.writeLong(1); buf.writeVarInt(count);
                assertThrows(IllegalArgumentException.class, () -> ModelMeshPayload.CODEC.decode(buf));
            } finally { buf.release(); }
        }
    }

    @Test void meshWhoseIdIsNotItsHashIsDroppedWithoutThrowing() {
        float[] data = {0,0,0, 1,0,0, 0,1,0.25F};
        long id = ModelMesh.hash(data);
        assertFalse(new ModelMeshPayload(id ^ 1, data).receive(java.util.UUID.randomUUID()));
        assertNull(ModelMesh.get(id ^ 1));
        assertNull(ModelMesh.get(id));
        float[] outside = {0,0,0, 1,0,0, 0,1,3};
        assertFalse(new ModelMeshPayload(ModelMesh.hash(outside), outside).receive(java.util.UUID.randomUUID()));
    }

    @Test void onePlayerAndAllTogetherAddOnlySoMuch() {
        assertTrue(ModelMeshPayload.fits(0, 0, 0, 0, ModelMesh.MAX_TRIANGLES));
        assertFalse(ModelMeshPayload.fits(ModelMeshPayload.PLAYER_MESHES, 0, 0, 0, 1));
        assertTrue(ModelMeshPayload.fits(0, ModelMeshPayload.PLAYER_TRIANGLES - 12, 0, 0, 12));
        assertFalse(ModelMeshPayload.fits(0, ModelMeshPayload.PLAYER_TRIANGLES - 11, 0, 0, 12));
        assertFalse(ModelMeshPayload.fits(0, 0, ModelMeshPayload.SERVER_MESHES, 0, 1));
        assertFalse(ModelMeshPayload.fits(0, 0, 0, ModelMeshPayload.SERVER_TRIANGLES - 11, 12));
        // the players together never fill the cache's 4096 meshes: the server's own and a LAN host's still go in
        assertTrue(ModelMeshPayload.SERVER_MESHES < 4096 && ModelMeshPayload.PLAYER_MESHES <= ModelMeshPayload.SERVER_MESHES);
    }
}
