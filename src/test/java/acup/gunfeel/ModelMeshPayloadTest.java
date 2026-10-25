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
}
