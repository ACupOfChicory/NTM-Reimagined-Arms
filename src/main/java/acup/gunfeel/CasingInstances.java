package acup.gunfeel;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.mixin.CasingInstanceAccess;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * NTM's spent casings' bodies drawn by the GPU, one draw for each part of the casing model (MixinCasingDraw). NTM turns
 * each casing's 150 to 500 vertices by its matrix on the CPU every frame; here each part's vertices go to the GPU
 * once, and a frame sends only each casing's matrices, colour and light. The picture is NTM's: its model and texture,
 * vanilla's entity shader (cutout, culled, lit, fogged as NTM's render type draws it), the same casings (CasingCull).
 *
 * <p>Drawn after the opaque features (RenderLevelStageEvent.AfterOpaqueFeatures), where the casings' own pass is
 * done. Not with a shader pack (Iris draws into its own targets: NTM's way then), and off for good, with a line in the
 * log, should its shader not compile, a frame's casings not get drawn (the event not after NTM's submit) or the draw
 * fail.
 */
public final class CasingInstances {
    /** the stand's switch (FireStand alt=inst-/inst+) */
    public static volatile boolean on = true;

    static final int BATCH = 64, STRIDE = 160, BLOCK = BATCH * STRIDE, MAX_PARTS = 4;
    static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
            .withLocation(id("pipeline/casing_instances"))
            .withVertexShader(id("core/casing_instances"))
            .withShaderDefine("ALPHA_CUTOUT", 0.1F)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("CasingPoses", UniformType.UNIFORM_BUFFER).build())
            .build();

    private record Mesh(GpuBuffer buffer, int indexCount) {}

    private static final Map<Integer, Mesh> MESHES = new HashMap<>();
    private static Object meshesOf;
    private static boolean failed;

    // this frame's casings: matrix (16), normal matrix (9), light; their model's parts and colours
    private static float[] poses = new float[256 * 25];
    private static int[] lights = new int[256];
    private static int[][] partIds = new int[256][], colors = new int[256][];
    private static int count;
    private static RenderType type;
    private static boolean queuedUndrawn;
    /** frames in a row whose casings were not drawn (the event did not come after NTM's submit) */
    private static int missed;

    // the frame's grouping, kept between frames
    private static int[] partOf = new int[8];
    private static int[][] byPart = new int[8][];
    private static Mesh[] drawMesh = new Mesh[16];
    private static int[] drawAt = new int[16], drawN = new int[16];

    private static ByteBuffer staging;
    private static GpuBuffer uniforms;

    private CasingInstances() {
    }

    /** the pipeline compiled with the game's (a pipeline not registered is not drawn) */
    public static void register(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent.class, e -> e.registerPipeline(PIPELINE));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("ntmgunfeel", path);
    }

    /** before NTM's casings go to the renderer this frame */
    public static void begin() {
        // a level drawn twice before the event (another mod's second view) loses the first view's casings once;
        // three frames in a row and the event is not where it should be
        if (queuedUndrawn && count > 0) {
            if (++missed >= 3) off("the casings of three frames in a row were not drawn after NTM's submit");
        } else missed = 0;
        count = 0;
        type = null;
        queuedUndrawn = false;
    }

    /**
     * Takes a casing's body for the instanced draw: false when it is to be drawn NTM's way (off, a shader pack, another
     * render type than the frame's first, a casing of more parts than kept).
     */
    public static boolean take(PoseStack stack, RenderType renderType, Object instance) {
        try {
            return takeChecked(stack, renderType, instance);
        } catch (RuntimeException | LinkageError e) {
            off(e.toString());
            return false;
        }
    }

    private static boolean takeChecked(PoseStack stack, RenderType renderType, Object instance) {
        if (!on || !Config.casingInstances || failed || !(instance instanceof CasingInstanceAccess casing) || SmokeRender_irisPack()) return false;
        if (type == null) {
            // the shader built (else the draw would be skipped and the casings gone)
            if (!RenderSystem.getDevice().precompilePipeline(PIPELINE).isValid()) {
                off("its shader did not compile");
                return false;
            }
            type = renderType;
        } else if (type != renderType) return false;
        var config = casing.gunfeel$config();
        int[] parts = config.getType().partIds(), cs = config.getColors();
        if (parts.length > MAX_PARTS || cs.length < parts.length) return false;
        // each part's mesh made now: a part with none (the model not loaded, mid-reload) is drawn NTM's way
        try {
            var model = com.hbm.main.ResourceManager.casings;
            if (model == null) return false;
            if (meshesOf != model) {
                clear();
                meshesOf = model;
            }
            for (int part : parts) if (mesh(part) == null) return false;
        } catch (RuntimeException e) {
            return false;
        }
        if (failed) return false;
        if (count == lights.length) grow();
        var pose = stack.last();
        Matrix4f m = pose.pose();
        Matrix3f n = pose.normal();
        int k = count * 25;
        m.get(poses, k);
        n.get(poses, k + 16);
        lights[count] = casing.gunfeel$light();
        partIds[count] = parts;
        colors[count] = cs;
        count++;
        queuedUndrawn = true;
        return true;
    }

    private static boolean SmokeRender_irisPack() {
        return acup.gunfeel.smoke.client.SmokeRender.irisPack();
    }

    private static void grow() {
        int n = lights.length * 2;
        poses = java.util.Arrays.copyOf(poses, n * 25);
        lights = java.util.Arrays.copyOf(lights, n);
        partIds = java.util.Arrays.copyOf(partIds, n);
        colors = java.util.Arrays.copyOf(colors, n);
    }

    /** the frame's casings, after the opaque features */
    public static void draw(org.joml.Matrix4fc modelView) {
        if (count == 0 || type == null) return;
        queuedUndrawn = false;
        try {
            drawChecked(modelView);
        } catch (RuntimeException | LinkageError e) {
            off("the draw failed: " + e);
        } finally {
            count = 0;
        }
    }

    private static void drawChecked(org.joml.Matrix4fc modelView) {
        var device = RenderSystem.getDevice();
        int alignment = device.getDeviceInfo().limits().minUniformOffsetAlignment();
        // the parts drawn (a handful), each with the casings that have it, as casing * MAX_PARTS + its part's place
        int kinds = 0;
        for (int i = 0; i < count; i++) for (int p : partIds[i]) {
            int at = 0;
            while (at < kinds && partOf[at] != p) at++;
            if (at == kinds) {
                if (kinds == partOf.length) {
                    partOf = java.util.Arrays.copyOf(partOf, kinds * 2);
                    byPart = java.util.Arrays.copyOf(byPart, kinds * 2);
                }
                partOf[kinds] = p;
                if (byPart[kinds] == null || byPart[kinds].length < count * MAX_PARTS + 1) byPart[kinds] = new int[count * MAX_PARTS * 2 + 1];
                byPart[kinds++][0] = 0;
            }
        }
        for (int i = 0; i < count; i++) for (int j = 0; j < partIds[i].length; j++) {
            int at = 0;
            while (partOf[at] != partIds[i][j]) at++;
            int[] list = byPart[at];
            list[++list[0]] = i * MAX_PARTS + j;
        }
        int length = 0, mostIndices = 0;
        for (int at = 0; at < kinds; at++) {
            int[] list = byPart[at];
            for (int first = 0; first < list[0]; first += BATCH)
                length = ModelDebugMask.aligned(length + Math.min(BATCH, list[0] - first) * STRIDE, alignment);
            Mesh mesh = MESHES.get(partOf[at]);
            if (mesh != null) mostIndices = Math.max(mostIndices, mesh.indexCount());
        }
        length += BLOCK;
        if (staging == null || staging.capacity() < length) {
            if (staging != null) MemoryUtil.memFree(staging);
            staging = MemoryUtil.memCalloc(length);
        }
        if (uniforms == null || uniforms.size() < length) {
            if (uniforms != null) uniforms.close();
            uniforms = device.createBuffer(() -> "NTM casings' poses", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, length);
        }
        staging.clear();
        staging.limit(length);
        int draws = 0, offset = 0;
        for (int at = 0; at < kinds; at++) {
            Mesh mesh = MESHES.get(partOf[at]);
            int[] list = byPart[at];
            for (int first = 0; first < list[0]; first += BATCH) {
                int n = Math.min(BATCH, list[0] - first);
                staging.position(offset);
                for (int q = 0; q < n; q++) {
                    int code = list[1 + first + q], i = code / MAX_PARTS, j = code % MAX_PARTS;
                    put(i, 0xFF000000 | colors[i][j]);
                }
                if (mesh != null) {
                    if (draws == drawMesh.length) {
                        drawMesh = java.util.Arrays.copyOf(drawMesh, draws * 2);
                        drawAt = java.util.Arrays.copyOf(drawAt, draws * 2);
                        drawN = java.util.Arrays.copyOf(drawN, draws * 2);
                    }
                    drawMesh[draws] = mesh;
                    drawAt[draws] = offset;
                    drawN[draws++] = n;
                }
                offset = ModelDebugMask.aligned(offset + n * STRIDE, alignment);
            }
        }
        if (draws == 0) return;
        staging.position(0);
        var encoder = device.createCommandEncoder();
        encoder.writeToBuffer(uniforms.slice(0, length), staging);

        PreparedRenderType prepared = type.prepare();
        RenderTarget target = prepared.outputTarget().getRenderTarget();
        GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride
                : target.getColorTextureView();
        GpuTextureView depth = !target.useDepth ? null
                : RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView();
        var transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(modelView), new Vector4f(1F, 1F, 1F, 1F), new Vector3f(),
                new Matrix4f());
        // the index buffer for the largest part got before the pass (it grows by being made anew)
        var indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer(mostIndices);
        try (var pass = encoder.createRenderPass(() -> "NTM casings, instanced", color, Optional.empty(), depth, OptionalDouble.empty())) {
            pass.setPipeline(PIPELINE);
            var scissor = prepared.scissorState();
            if (scissor.enabled()) pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            for (var t : prepared.textures()) pass.bindTexture(t.name(), t.textureView(), t.sampler());
            pass.setIndexBuffer(indexBuffer, indices.type());
            for (int d = 0; d < draws; d++) {
                pass.setVertexBuffer(0, drawMesh[d].buffer().slice());
                pass.setUniform("CasingPoses", uniforms.slice(drawAt[d], BLOCK));
                pass.drawIndexed(drawMesh[d].indexCount(), drawN[d], 0, 0, 0);
            }
        }
    }

    // one slot (std140: mat4 pose, mat4 normal, vec4 colour, ivec4 light)
    private static void put(int i, int argb) {
        int k = i * 25;
        for (int c = 0; c < 16; c++) staging.putFloat(poses[k + c]);
        for (int col = 0; col < 3; col++) {
            staging.putFloat(poses[k + 16 + col * 3]).putFloat(poses[k + 17 + col * 3]).putFloat(poses[k + 18 + col * 3]).putFloat(0F);
        }
        staging.putFloat(0F).putFloat(0F).putFloat(0F).putFloat(1F);
        staging.putFloat((argb >> 16 & 255) / 255F).putFloat((argb >> 8 & 255) / 255F).putFloat((argb & 255) / 255F)
                .putFloat((argb >>> 24) / 255F);
        int light = lights[i];
        staging.putInt(light & 0xFFFF).putInt(light >>> 16 & 0xFFFF).putInt(0).putInt(0);
    }

    // a part of NTM's casing model as NTM emits it, unmoved, white, unlit: the shader moves, colours and lights it
    /** parts that made no mesh with this model */
    private static final java.util.Set<Integer> EMPTY = new java.util.HashSet<>();

    private static Mesh mesh(int part) {
        Mesh cached = MESHES.get(part);
        if (cached != null || EMPTY.contains(part)) return cached;
        Mesh mesh = null;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(16384)) {
            var builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.ENTITY);
            com.hbm.main.ResourceManager.casings.renderPart(new PoseStack().last(), builder, 0, -1, part);
            try (MeshData data = builder.build()) {
                if (data != null) {
                    var buffer = RenderSystem.getDevice().createBuffer(() -> "NTM casing part " + part, GpuBuffer.USAGE_VERTEX,
                            data.vertexBuffer());
                    mesh = new Mesh(buffer, data.drawState().indexCount());
                }
            }
        }
        // a part with no mesh stays NTM's for this model (not built again each casing, each frame)
        if (mesh != null) MESHES.put(part, mesh);
        else EMPTY.add(part);
        return mesh;
    }

    private static void off(String why) {
        if (failed) return;
        failed = true;
        count = 0;
        GunFeel.LOGGER.info("[casings] instanced draw off, NTM draws them: {}", why);
    }

    /** the meshes (another model, or the level left: then the poses' buffers too) */
    public static void clear() {
        for (Mesh m : MESHES.values()) m.buffer().close();
        MESHES.clear();
        EMPTY.clear();
        meshesOf = null;
    }

    public static void leave() {
        clear();
        if (uniforms != null) uniforms.close();
        uniforms = null;
        if (staging != null) MemoryUtil.memFree(staging);
        staging = null;
        count = 0;
        type = null;
        missed = 0;
        queuedUndrawn = false;
    }
}
