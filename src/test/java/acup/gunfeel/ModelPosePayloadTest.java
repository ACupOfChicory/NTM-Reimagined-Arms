package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class ModelPosePayloadTest {
    @Test void largestUnicodePoseFitsServerboundPacketLimit() {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            var box = new ModelBox("界".repeat(ModelBox.MAX_PART_NAME), 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1);
            var packet = new ModelPosePayload(42, UUID.randomUUID(), 0, 0, 0, 0,
                    java.util.Collections.nCopies(ModelPosePayload.MAX_BOXES, box));
            ModelPosePayload.CODEC.encode(buf, packet);
            assertTrue(buf.readableBytes() < 32_767);
            assertEquals(packet, ModelPosePayload.CODEC.decode(buf));
        } finally { buf.release(); }
    }

    @Test void roundTripKeepsIdentityAndRelativeGeometryAtWorldBorder() {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            var box = new ModelBox("root/body/head", -.25, 1.5, -.25, .5, 0, 0, 0, .5, 0, 0, 0, .5).withMesh(123456789L);
            var packet = new ModelPosePayload(42, UUID.randomUUID(), 29_999_999.75, 70, -29_999_999.75, 90, List.of(box));
            ModelPosePayload.CODEC.encode(buf, packet);
            assertEquals(packet, ModelPosePayload.CODEC.decode(buf));
            assertEquals(0, buf.readableBytes());
        } finally { buf.release(); }
    }

    @Test void theClientKeepsThePoseTheServerGets() {
        // a box of doubles off the float grid (the capture subtracts the camera in doubles): the server decodes floats,
        // the client keeps sent(), the very same numbers
        var box = new ModelBox("mesh/Head", -.2500000123, 1.7500000071, -.2499999913, .5000000031, 1e-9, 0, 0, .4999999971, 0, 0, 0, .5).withMesh(7L);
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            ModelPosePayload.CODEC.encode(buf, new ModelPosePayload(1, UUID.randomUUID(), 0, 0, 0, 0, List.of(box)));
            var got = ModelPosePayload.CODEC.decode(buf).boxes().getFirst();
            assertNotEquals(box, got);
            assertEquals(box.sent(), got);
            assertEquals(box.sent(), box.sent().sent());
        } finally { buf.release(); }
    }

    @Test void rejectsInvalidCountsBeforeAllocatingCubes() {
        for (int count : new int[] {-1, 0, 129, Integer.MAX_VALUE}) {
            var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
            try {
                buf.writeVarInt(1);
                buf.writeUUID(UUID.randomUUID());
                buf.writeDouble(0); buf.writeDouble(0); buf.writeDouble(0);
                buf.writeFloat(0);
                buf.writeVarInt(count);
                assertThrows(IllegalArgumentException.class, () -> ModelPosePayload.CODEC.decode(buf));
            } finally { buf.release(); }
        }
    }
}
