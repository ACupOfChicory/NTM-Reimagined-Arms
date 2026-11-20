package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import org.joml.Matrix4fc;

import com.mojang.blaze3d.buffers.Std140Builder;

import acup.gunfeel.host.render.ParamsWriter;
import acup.gunfeel.host.render.RecordingRenderPort;
import acup.gunfeel.smoke.client.SmokeAtlasSlots;
import acup.gunfeel.smoke.client.SmokeMarch;

/** 26.2's own MapWriter against Std140Builder, byte for byte, on SmokeMarchBitTest's frames; no GPU device. */
public final class MapWriterProbe {
    private MapWriterProbe() {}

    public static void verify(SmokeMarch.Frame frame, List<SmokeAtlasSlots.Slot> slots, int seed) {
        for (int offset : new int[] {0, 7, 32}) {
            int size = SmokeMarch.INFO_SIZE;
            ByteBuffer want = buffer(size + offset + 19), got = buffer(size + offset + 19);
            want.position(offset);
            got.position(offset);
            Std140Builder builder = Std140Builder.intoBuffer(want);
            ParamsWriter reference = new ParamsWriter() {
                @Override public ParamsWriter vec4(float x, float y, float z, float w) {
                    builder.putVec4(x, y, z, w);
                    return this;
                }
                @Override public ParamsWriter mat4(Matrix4fc m) {
                    builder.putMat4f(m);
                    return this;
                }
                @Override public void done() { assertEquals(offset + size, want.position()); }
                @Override public void abort() { fail("неожиданный abort эталона"); }
            };
            var writer = new RenderPort26.MapWriter();
            int[] closes = {0};
            writer.start(got, size, () -> closes[0]++);
            SmokeMarch.write(port(reference), 0, frame, slots);
            SmokeMarch.write(port(writer), 0, frame, slots);
            assertEquals(1, closes[0]);
            assertEquals(offset + size, got.position());
            byte[] expected = new byte[want.capacity()], actual = new byte[got.capacity()];
            want.clear().get(expected);
            got.clear().get(actual);
            assertArrayEquals(expected, actual, "MapWriter: кадр " + seed + ", начало " + offset);
        }
    }

    private static ByteBuffer buffer(int size) {
        ByteBuffer b = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
        for (int i = 0; i < size; i++) b.put(i, (byte) 0xA5);
        return b;
    }

    private static RecordingRenderPort port(ParamsWriter writer) {
        return new RecordingRenderPort() {
            @Override public ParamsWriter map(int params, int length) {
                assertEquals(SmokeMarch.INFO_SIZE, length);
                return writer;
            }
        };
    }
}
