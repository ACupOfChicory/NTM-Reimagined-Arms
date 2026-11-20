package acup.gunfeel.host.render;

/** The recording port (core's tests draw through it) holds to the contract of a version's sink. */
class RecordingRenderSinkTest extends RenderSinkContract {
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
