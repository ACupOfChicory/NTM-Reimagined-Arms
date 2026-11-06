package acup.gunfeel.water;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import acup.gunfeel.phys.Vec3;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

// The blast message (port: 26.2's WaterBlastPayload as a 1.7.10 IMessage) comes out as it went in
class WaterBlastPayloadTest {
    private static WaterEvents.Blast trip(WaterBlastPayload m) {
        ByteBuf b = Unpooled.buffer();
        m.toBytes(b);
        WaterBlastPayload r = new WaterBlastPayload();
        r.fromBytes(b);
        assertEquals(0, b.readableBytes(), "all of it read");
        return r.blast();
    }

    @Test
    void withSurface() {
        WaterEvents.Blast b = trip(new WaterBlastPayload(new Vec3(10.5, 61.25, -3.75), 6F, true, true, new Vec3(10.5, 62.875, -3.75),
                WaterEvents.Liquid.WATER));
        assertEquals(10.5, b.at().x);
        assertEquals(61.25, b.at().y);
        assertEquals(-3.75, b.at().z);
        assertEquals(6F, b.size());
        assertTrue(b.incendiary());
        assertTrue(b.inWater());
        assertNotNull(b.surface());
        assertEquals(new Vec3(10.5, 62.875, -3.75), b.surface());
        assertEquals(WaterEvents.Liquid.WATER, b.liquid());
    }

    @Test
    void noSurfaceInLava() {
        WaterEvents.Blast b = trip(new WaterBlastPayload(new Vec3(0, 5, 0), 2.5F, false, false, null, WaterEvents.Liquid.LAVA));
        assertNull(b.surface());
        assertFalse(b.incendiary());
        assertFalse(b.inWater());
        assertEquals(WaterEvents.Liquid.LAVA, b.liquid());
    }
}
