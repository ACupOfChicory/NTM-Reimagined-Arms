package acup.gunfeel;

import net.minecraft.client.renderer.rendertype.RenderType;

// port/continuum: the render type of the model submit being drawn. 26.2's ModelFeatureRenderer.Submit carries it,
// 26.1's SubmitNodeStorage.ModelSubmit does not: renderModel gets it apart, and MixinRenderedModelHit sets it here
// around ModelHitClient.rendered (NtmMeshHits.prepared reads submit.renderType() as renderType()). Render thread only.
public final class ModelSubmit261 {
    private static RenderType current;

    private ModelSubmit261() {
    }

    public static RenderType renderType() {
        if (current == null) throw new IllegalStateException("no model submit being drawn");
        return current;
    }

    public static void begin(RenderType type) {
        current = type;
    }

    public static void end() {
        current = null;
    }
}
