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
            var box = new ModelBox("root/body/head", -.25, 1.5, -.25, .5, 0, 0, 0, .5, 0, 0, 0, .5);
            var packet = new ModelPosePayload(42, UUID.randomUUID(), 29_999_999.75, 70, -29_999_999.75, 90, List.of(box));
            ModelPosePayload.CODEC.encode(buf, packet);
            assertEquals(packet, ModelPosePayload.CODEC.decode(buf));
            assertEquals(0, buf.readableBytes());
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
