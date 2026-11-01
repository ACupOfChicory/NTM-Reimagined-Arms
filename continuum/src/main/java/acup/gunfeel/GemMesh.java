package acup.gunfeel;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.golem.core.render.gem.Gem;
import dev.golem.core.render.gem.GemBER;
import dev.golem.core.render.gem.Surface;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.OverlayTexture;

// port/continuum: what a machine of NTM Continuum draws, for RenderedMesh. Continuum's machine renderers are Golem
// Core GemBERs: the model is declared once and replayed from meshes kept on the GPU (StaticMeshes) or by an instanced
// visual, so a recorder handed to their submit gets nothing. Here the declared model is walked as the draw walks it
// (Gem.walk: the same visibility tests and pose-stack calls) and each part goes to the recorder as the non-resident
// draw submits it: its geometry drawn into the part's surface's render type. What a GemBER builds each frame outside
// the declaration (beams, flames, fluid levels: submitUndeclared) is left out. It is called from RenderedMesh.Staged's
// submit, on MeshShapes' worker (the state was extracted on the game's thread).
public final class GemMesh {
    private GemMesh() {
    }

    // true: the renderer is a GemBER and its parts went to out; false: submit it as any other renderer
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean submit(Object renderer, Object state, PoseStack poseStack, SubmitNodeCollector out) {
        if (!(renderer instanceof GemBER ber)) return false;
        Gem.walk(ber.gem(), state, poseStack, (Gem.Visitor) (part, geometry, at) -> {
            Surface surface = ((Gem.Part) part).surfaceOf(state);
            if (surface == null) return;
            if (at.last().pose().determinant3x3() < 0F) surface = surface.unculled();
            var mesh = (dev.golem.core.render.gem.Geometry) geometry;
            out.submitCustomGeometry(at, surface.renderType(), (pose, buffer) -> mesh.draw(pose, buffer, 0xF000F0, OverlayTexture.NO_OVERLAY, -1));
        });
        return true;
    }
}
