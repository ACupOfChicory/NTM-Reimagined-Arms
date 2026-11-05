package acup.gunfeel.port;

import java.util.function.Supplier;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;

// The bullet holes kept on the graphics card (Tracers: batches and the holes on glass) without 26.2's GpuDevice:
// rewrite.txt turns Tracers' GpuBuffer into this, the device and command encoder into DEVICE, the dynamic transform
// into transform() and PreparedRenderType into Prepared. A buffer is a 1.21.1 VertexBuffer, which takes the mesh
// whole (its upload sizes the buffer itself, so size() never asks for a new one); it is drawn with its render
// type set up and the shader's ModelOffset set (ntmgunfeel:bullet_hole, HoleRender).
public final class HoleBuffer implements AutoCloseable {
    private final VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
    private int indexCount;

    public static final Device DEVICE = new Device();
    public static final Indices INDICES = new Indices();

    public long size() {
        return Long.MAX_VALUE;
    }

    public HoleBuffer slice() {
        return this;
    }

    public HoleBuffer slice(long offset, long length) {
        return this;
    }

    @Override
    public void close() {
        vbo.close();
    }

    public static final class Device {
        public HoleBuffer createBuffer(Supplier<String> label, int usage, long size) {
            return new HoleBuffer();
        }

        public Device createCommandEncoder() {
            return this;
        }

        // the mesh is closed by the upload (closing it again after is harmless)
        public void writeToBuffer(HoleBuffer buffer, MeshData mesh) {
            buffer.indexCount = mesh.drawState().indexCount();
            buffer.vbo.bind();
            buffer.vbo.upload(mesh);
            VertexBuffer.unbind();
        }
    }

    // 26.2's sequential index buffer: VertexBuffer binds its own
    public static final class Indices {
        public Object getBuffer(int count) {
            return null;
        }

        public Object type() {
            return null;
        }
    }

    public record Transform(Matrix4f modelView, Vector3f offset) {
    }

    public static Transform transform(Matrix4fc modelView, Vector4f color, Vector3f offset, Matrix4f texture) {
        return new Transform(new Matrix4f(modelView), new Vector3f(offset));
    }

    public static Prepared prepared(RenderType type, Transform transform) {
        return new Prepared(type, transform);
    }

    // no transform: the model view the level is drawn with now, no offset
    public record Prepared(RenderType type, Transform transform) {
        public void drawFromBuffer(HoleBuffer buffer, Object indices, Object indexType, int firstIndex, int baseVertex, int count) {
            if (count <= 0 || buffer.indexCount <= 0) return;
            type.setupRenderState();
            try {
                ShaderInstance shader = RenderSystem.getShader();
                if (shader == null) return;
                Uniform offset = shader.getUniform("ModelOffset");
                if (offset != null) {
                    if (transform != null) offset.set(transform.offset().x, transform.offset().y, transform.offset().z);
                    else offset.set(0F, 0F, 0F);
                }
                Matrix4f modelView = transform != null ? transform.modelView() : new Matrix4f(RenderSystem.getModelViewMatrix());
                buffer.vbo.bind();
                buffer.vbo.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), shader);
                VertexBuffer.unbind();
                // the shader is shared with the holes drawn through a buffer source, which do not set the offset
                if (offset != null) offset.set(0F, 0F, 0F);
            } finally {
                type.clearRenderState();
            }
        }
    }
}
