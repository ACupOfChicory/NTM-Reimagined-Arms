package acup.gunfeel.host.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
