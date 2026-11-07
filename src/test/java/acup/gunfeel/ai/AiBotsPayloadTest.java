package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

// What the debug view says of the AI mobs, there and back: a long path and a long line of text are cut, not refused.
class AiBotsPayloadTest {
    @Test
    void roundTrip() {
        long[] path = new long[100];
        for (int i = 0; i < path.length; i++) path[i] = net.minecraft.core.BlockPos.asLong(i, 64, -i);
        var sent = new AiBotsPayload(List.of(
                new AiBotsPayload.Bot(7, "cover 4/24", 1.5, 65.6, -3.5, net.minecraft.core.BlockPos.asLong(2, 64, 3), Long.MIN_VALUE, path),
                new AiBotsPayload.Bot(9, "x".repeat(AiBotsPayload.TEXT + 40), Double.NaN, Double.NaN, Double.NaN, Long.MIN_VALUE, Long.MIN_VALUE, new long[0])));
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        AiBotsPayload.CODEC.encode(buf, sent);
        AiBotsPayload back = AiBotsPayload.CODEC.decode(buf);
        assertEquals(2, back.bots().size());
        AiBotsPayload.Bot a = back.bots().get(0), b = back.bots().get(1);
        assertEquals(7, a.entity());
        assertEquals("cover 4/24", a.text());
        assertEquals(65.6, a.wy(), 1e-9);
        assertEquals(sent.bots().get(0).spot(), a.spot());
        assertEquals(Long.MIN_VALUE, a.peek());
        assertEquals(AiBotsPayload.PATH, a.path().length);
        assertEquals(path[AiBotsPayload.PATH - 1], a.path()[AiBotsPayload.PATH - 1]);
        assertEquals(AiBotsPayload.TEXT, b.text().length());
        assertTrue(Double.isNaN(b.wx()));
        assertEquals(0, b.path().length);
    }
}
