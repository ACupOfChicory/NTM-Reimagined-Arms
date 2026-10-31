package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;

import acup.gunfeel.mixin.mc.RenderSetupAccess;
import acup.gunfeel.mixin.mc.RenderTypeAccess;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;

// port/continuum: Minecraft 26.2's PreparedRenderType on 26.1, which has only RenderType.draw(MeshData). The shared
// code draws its own vertex buffers through RenderType.prepare().drawFromBuffer(...); the 26.1 node's copy calls this
// instead (stonecutter.gradle.kts). Both halves do what 26.1's RenderType.draw does around its upload: the layering
// transform on the model-view matrix, the texture transform, the render type's textures and the scissor.
public record Prepared261(RenderPipeline pipeline, OutputTarget outputTarget, GpuBufferSlice dynamicTransforms,
        ScissorState scissorState, List<Texture> textures) {
    public record Texture(String name, GpuTextureView textureView, GpuSampler sampler) {
    }

    public static Prepared261 of(RenderType type) {
        RenderSetup setup = ((RenderTypeAccess) type).gunfeel$state();
        RenderSetupAccess access = (RenderSetupAccess) (Object) setup;
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        // NO_LAYERING has no modifier
        var layering = access.gunfeel$layeringTransform().getModifier();
        if (layering != null) layering.accept(stack);
        GpuBufferSlice transforms;
        try {
            transforms = RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrix(),
                    new Vector4f(1F, 1F, 1F, 1F), new Vector3f(), access.gunfeel$textureTransform().getMatrix());
        } finally {
            stack.popMatrix();
        }
        List<Texture> textures = new ArrayList<>();
        setup.getTextures().forEach((name, t) -> textures.add(new Texture(name, t.textureView(), t.sampler())));
        return new Prepared261(type.pipeline(), type.outputTarget(), transforms, RenderSystem.getScissorStateForRenderTypeDraws(),
                List.copyOf(textures));
    }

    // 26.2's drawFromBuffer(vertices, indices, index type, base vertex, first index, index count)
    public void drawFromBuffer(GpuBuffer vertices, GpuBuffer indices, VertexFormat.IndexType indexType, int baseVertex,
            int firstIndex, int count) {
        var target = outputTarget.getRenderTarget();
        GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride
                : target.getColorTextureView();
        GpuTextureView depth = !target.useDepth ? null : RenderSystem.outputDepthTextureOverride != null
                ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView();
        try (var pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Reimagined Arms " + pipeline.getLocation(),
                color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            if (scissorState.enabled()) pass.enableScissor(scissorState.x(), scissorState.y(), scissorState.width(), scissorState.height());
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            pass.setVertexBuffer(0, vertices);
            for (Texture t : textures) pass.bindTexture(t.name(), t.textureView(), t.sampler());
            pass.setIndexBuffer(indices, indexType);
            pass.drawIndexed(baseVertex, firstIndex, count, 1);
        }
    }
}
