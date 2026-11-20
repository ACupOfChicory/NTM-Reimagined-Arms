package acup.gunfeel.host.render;

/** The recording port (core's tests draw through it) holds to the contract of a version's sink. */
class RecordingRenderSinkTest extends RenderSinkContract {
    @org.junit.jupiter.api.Test
    void abortedParamsAreNotRecorded() {
        RecordingRenderPort p = new RecordingRenderPort();
        int upload = p.params("upload"), params = p.params("map");
        p.upload(upload, java.nio.ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), 4);
        p.map(params, 48).vec4(1, 2, 3, 4).vec4(5, 6, 7, 8).vec4(9, 10, 11, 12).done();
        byte[] uploaded = p.uploaded.clone(), mapped = p.mapped.clone();
        int recorded = p.log.size();
        ParamsWriter w = p.map(params, 48);
        w.vec4(13, 14, 15, 16).vec4(17, 18, 19, 20);
        w.abort();
        org.junit.jupiter.api.Assertions.assertArrayEquals(uploaded, p.uploaded);
        org.junit.jupiter.api.Assertions.assertArrayEquals(mapped, p.mapped);
        org.junit.jupiter.api.Assertions.assertEquals(recorded, p.log.size());
    }

    @Override
    protected void trace(RenderPort port, boolean enabled) {
        ((RecordingRenderPort) port).record = enabled;
    }
    @Override
    protected RenderPort port() {
        return new RecordingRenderPort();
    }

    @Override
    protected void at(RenderPort port, int intent, java.util.function.Consumer<RenderSink> body) {
        RecordingRenderPort r = (RecordingRenderPort) port;
        body.accept(r);
        r.flush();
    }

    @Override
    protected byte[] uploaded(RenderPort port, int params, int length) {
        return ((RecordingRenderPort) port).uploaded;
    }

    @Override
    protected int[] meshesOfTwoTopologies(RenderPort port) {
        RecordingRenderPort r = (RecordingRenderPort) port;
        return new int[] {r.meshOf(0), r.meshOf(1)};
    }

    @Override
    protected int instancedMaterial(RenderPort port) {
        return port.material(acup.gunfeel.CasingBatches.MATERIAL);
    }
}
