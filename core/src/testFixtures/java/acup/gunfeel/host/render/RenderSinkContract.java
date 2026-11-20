package acup.gunfeel.host.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

/**
 * What every version's {@link RenderSink} must do with params and instanced draws, whatever the GPU: a version's test
 * extends this with its port. The draws of a test run at their place (the sink a drawer is handed).
 */
public abstract class RenderSinkContract {
    /** a port of the version, fresh */
    protected abstract RenderPort port();

    /** the sink a drawer is handed at {@code intent}'s place: {@code body} run there, its draws flushed */
    protected abstract void at(RenderPort port, int intent, java.util.function.Consumer<RenderSink> body);

    /** the bytes the port took at its last upload of {@code params}, read back */
    protected abstract byte[] uploaded(RenderPort port, int params, int length);

    /** two kept meshes of different topologies (their index kinds), or null when the version has one kind only */
    protected abstract int[] meshesOfTwoTopologies(RenderPort port);

    /** an instanced material of the version */
    protected abstract int instancedMaterial(RenderPort port);

    /** Запись трассы выключается только для проверки выделений. */
    protected abstract void trace(RenderPort port, boolean enabled);

    private static void vertex(VertexWriter w) {
        w.decal(0, 0, 0, -1, 0, 0, 0, 0, 1, 0);
    }

    @Test
    void handlesStayStable() {
        RenderPort p = port();
        int a = instancedMaterial(p), b = instancedMaterial(p);
        assertEquals(a, b);
        int buffer = p.buffer(Layouts.DECAL, 1);
        VertexWriter w = p.fill(buffer, 1);
        vertex(w);
        assertEquals(1, w.done());
        w = p.fill(buffer, 1);
        vertex(w);
        assertEquals(1, w.done());
    }

    @Test
    void unknownHandleIsRejected() {
        RenderPort p = port();
        assertThrows(IllegalArgumentException.class, () -> p.fill(Integer.MAX_VALUE, 1));
        assertThrows(IllegalArgumentException.class, () -> p.map(Integer.MAX_VALUE, 16));
    }

    @Test
    void beginAndEndArePaired() {
        RenderPort p = port();
        int b = p.buffer(Layouts.DECAL, 1);
        VertexWriter w = p.fill(b, 1);
        assertThrows(IllegalStateException.class, () -> p.fill(b, 1));
        w.done();
        assertThrows(IllegalStateException.class, w::done);
        assertThrows(IllegalStateException.class, () -> vertex(w));
    }

    @Test
    void outOfRangeFailsAtTheElement() {
        RenderPort p = port();
        int b = p.buffer(Layouts.DECAL, 1);
        VertexWriter w = p.fill(b, 1);
        vertex(w);
        w.done();
        assertThrows(IndexOutOfBoundsException.class, () -> at(p, Intent.AFTER_OPAQUE_SOLID,
                s -> s.drawAt(instancedMaterial(p), b, 1, 1, 0, 0, 0, 1)));
        ParamsWriter params = p.map(p.params("bounds"), 16);
        params.vec4(1, 2, 3, 4);
        assertThrows(IndexOutOfBoundsException.class, () -> params.vec4(5, 6, 7, 8));
        params.done();
    }

    @Test
    void frameWriterExpiresAtFrameEnd() {
        RenderPort p = port();
        VertexWriter[] escaped = new VertexWriter[1];
        int part = p.part(w -> { escaped[0] = w; vertex(w); });
        at(p, Intent.QUEUE, s -> s.queue(instancedMaterial(p), part, 1));
        assertThrows(IllegalStateException.class, () -> vertex(escaped[0]));
    }

    @Test
    void keptBufferLastsUntilFree() {
        RenderPort p = port();
        int material = instancedMaterial(p), b = p.buffer(Layouts.DECAL, 1);
        VertexWriter w = p.fill(b, 1);
        vertex(w);
        w.done();
        for (int i = 0; i < 2; i++)
            at(p, Intent.AFTER_OPAQUE_SOLID, s -> s.drawAt(material, b, 0, 1, 0, 0, 0, 1));
        p.free(b);
        assertThrows(IllegalStateException.class, () -> p.fill(b, 1));
    }

    @Test
    void paramsSizeEqualsSpecification() {
        RenderPort p = port();
        ParamsWriter w = p.map(p.params("size"), 32);
        w.vec4(1, 2, 3, 4);
        assertThrows(IllegalStateException.class, w::done);
    }

    @Test
    void mapTwiceWithoutDoneFails() {
        RenderPort p = port();
        int params = p.params("map");
        ParamsWriter w = p.map(params, 16);
        assertThrows(IllegalStateException.class, () -> p.map(params, 16));
        w.vec4(1, 2, 3, 4).done();
    }

    @Test
    void paramsWriterExpiresAfterDone() {
        RenderPort p = port();
        ParamsWriter w = p.map(p.params("done"), 16);
        w.vec4(1, 2, 3, 4).done();
        assertThrows(IllegalStateException.class, () -> w.vec4(1, 2, 3, 4));
        assertThrows(IllegalStateException.class, () -> w.mat4(new org.joml.Matrix4f()));
        assertThrows(IllegalStateException.class, w::done);
        w.abort();
    }

    @Test
    void failedParamsWriteAbortsAndCanBeMappedAgain() {
        RenderPort p = port();
        int params = p.params("abort");
        ParamsWriter w = p.map(params, 48);
        RuntimeException failure = new RuntimeException("запись прервана");
        java.util.function.Consumer<ParamsWriter> info = b -> {
            b.vec4(1, 2, 3, 4);
            b.vec4(5, 6, 7, 8);
            throw failure;
        };
        assertSame(failure, assertThrows(RuntimeException.class, () -> {
            try {
                info.accept(w);
            } catch (Throwable t) {
                w.abort();
                throw t;
            }
            w.done();
        }));
        assertThrows(IllegalStateException.class, () -> w.vec4(1, 2, 3, 4));
        assertThrows(IllegalStateException.class, () -> w.mat4(new org.joml.Matrix4f()));
        assertThrows(IllegalStateException.class, w::done);
        w.abort();
        ParamsWriter next = p.map(params, 48);
        next.vec4(1, 2, 3, 4).vec4(5, 6, 7, 8).vec4(9, 10, 11, 12).done();
        next.abort();
    }

    @Test
    void uploadAndMapCannotShareParams() {
        RenderPort p = port();
        ByteBuffer data = ByteBuffer.allocateDirect(16);
        int upload = p.params("upload"), map = p.params("map");
        at(p, Intent.AFTER_OPAQUE_SOLID, s -> s.upload(upload, data, 16));
        assertThrows(IllegalStateException.class, () -> p.map(upload, 16));
        p.map(map, 16).vec4(0, 0, 0, 0).done();
        assertThrows(IllegalStateException.class, () -> at(p, Intent.AFTER_OPAQUE_SOLID, s -> s.upload(map, data, 16)));
    }

    @Test
    void thousandFramesAllocateZeroBytesAfterWarmup() {
        RenderPort p = port();
        trace(p, false);
        int params = p.params("allocation"), upload = p.params("upload");
        int buffer = p.buffer(Layouts.DECAL, 1), material = instancedMaterial(p);
        int part = p.part(RenderSinkContract::vertex);
        ByteBuffer bytes = ByteBuffer.allocateDirect(16);
        java.util.function.Consumer<RenderSink> frame = sink -> {
            p.map(params, 16).vec4(1, 2, 3, 4).done();
            VertexWriter w = p.fill(buffer, 1);
            vertex(w);
            w.done();
            sink.upload(upload, bytes, 16);
            sink.drawAt(material, buffer, 0, 1, 0, 0, 0, 1);
            sink.queue(material, part, 1);
        };
        for (int i = 0; i < 20000; i++) at(p, Intent.AFTER_OPAQUE_SOLID, frame);
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        threads.setThreadAllocatedMemoryEnabled(true);
        long id = Thread.currentThread().threadId();
        threads.getThreadAllocatedBytes(id);
        long before = threads.getThreadAllocatedBytes(id);
        for (int i = 0; i < 1000; i++) at(p, Intent.AFTER_OPAQUE_SOLID, frame);
        assertEquals(0, threads.getThreadAllocatedBytes(id) - before);
    }

    @Test
    void uploadTakesZeroToLengthWhateverThePositionAndLimit() {
        RenderPort port = port();
        int params = port.params("contract");
        ByteBuffer data = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 64; i++) data.put(i, (byte) (i * 7 + 1));
        data.position(10).limit(20);
        at(port, Intent.AFTER_OPAQUE_SOLID, sink -> sink.upload(params, data, 48));
        byte[] want = new byte[48];
        for (int i = 0; i < 48; i++) want[i] = (byte) (i * 7 + 1);
        assertArrayEquals(want, uploaded(port, params, 48));
        assertEquals(10, data.position(), "the position given back");
        assertEquals(20, data.limit(), "the limit given back");
    }

    @Test
    void oneFlushOneTopology() {
        RenderPort port = port();
        int[] meshes = meshesOfTwoTopologies(port);
        if (meshes == null) return;
        int material = instancedMaterial(port), params = port.params("contract");
        assertThrows(IllegalArgumentException.class, () -> at(port, Intent.AFTER_OPAQUE_SOLID, sink -> {
            sink.drawInstanced(material, meshes[0], params, 0, 1);
            sink.drawInstanced(material, meshes[1], params, 0, 1);
        }));
    }
}
