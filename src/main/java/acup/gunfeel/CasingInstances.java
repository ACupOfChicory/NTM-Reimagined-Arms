package acup.gunfeel;

import java.util.HashMap;
import java.util.Map;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;

import acup.gunfeel.mixin.CasingInstanceAccess;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
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
 *
 * <p>The grouping, the blocks of poses and the switch off are core's (CasingBatches, render port step 2); here what is
 * 26.2's and NTM's: the casings taken from NTM's submit (their matrices into core's array), the parts' meshes from
 * NTM's model, the pipeline, the frame's render type, and the place (RenderPort26.afterOpaqueSolid).
 */
public final class CasingInstances {
    /** the stand's switch (FireStand alt=inst-/inst+) */
    public static volatile boolean on = true;

    static final int MAX_PARTS = 4;
    static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.ENTITY_SNIPPET)
            .withLocation(id("pipeline/casing_instances"))
            .withVertexShader(id("core/casing_instances"))
            .withShaderDefine("ALPHA_CUTOUT", 0.1F)
            .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("CasingPoses", UniformType.UNIFORM_BUFFER).build())
            .build();

    private static final Map<Integer, Integer> MESHES = new HashMap<>();
    private static Object meshesOf;
    private static RenderType type;
    private static boolean wired;

    private CasingInstances() {
    }

    // core's batches on the port, once: this pipeline is the material core asks for
    private static void wire() {
        if (wired) return;
        wired = true;
        RenderPort26.INSTANCE.instancedSource(CasingBatches.MATERIAL.name(), new RenderPort26.Instanced(PIPELINE, () -> type, "CasingPoses",
                CasingBatches.BLOCK, () -> "NTM casings, instanced"));
        CasingBatches.init(acup.gunfeel.CoreServices.load(acup.gunfeel.host.render.RenderPort.class, acup.gunfeel.host.render.RenderPort.MISSING),
                new CasingBatches.Host() {
                    @Override
                    public int mesh(int part) {
                        Integer m = MESHES.get(part);
                        return m == null ? acup.gunfeel.host.render.RenderPort.NONE : m;
                    }

                    @Override
                    public boolean ready() {
                        return type != null;
                    }

                    @Override
                    public void off(String why) {
                        GunFeel.LOGGER.info("[casings] instanced draw off, NTM draws them: {}", why);
                    }
                });
    }

    /** the pipeline compiled with the game's (a pipeline not registered is not drawn) */
    public static void register(net.neoforged.bus.api.IEventBus modBus) {
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent.class, e -> e.registerPipeline(PIPELINE));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("ntmgunfeel", path);
    }

    /** before NTM's casings go to the renderer this frame: core's frame of casings begins */
    public static void begin() {
        wire();
        CasingBatches.begin();
        type = null;
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
        if (!on || !Config.casingInstances || CasingBatches.failed() || !(instance instanceof CasingInstanceAccess casing) || SmokeRender_irisPack()) return false;
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
        if (CasingBatches.failed()) return false;
        float[] poses = CasingBatches.poses();
        var pose = stack.last();
        Matrix4f m = pose.pose();
        Matrix3f n = pose.normal();
        int k = CasingBatches.count() * 25;
        m.get(poses, k);
        n.get(poses, k + 16);
        CasingBatches.add(casing.gunfeel$light(), parts, cs);
        return true;
    }

    private static boolean SmokeRender_irisPack() {
        return acup.gunfeel.smoke.client.SmokeRender.irisPack();
    }

    /** the frame's casings, after the opaque features (the line in GunFeel where they were drawn before the port) */
    public static void draw(org.joml.Matrix4fc modelView) {
        RenderPort26.afterOpaqueSolid(modelView);
    }

    // a part of NTM's casing model as NTM emits it, unmoved, white, unlit: the shader moves, colours and lights it
    /** parts that made no mesh with this model */
    private static final java.util.Set<Integer> EMPTY = new java.util.HashSet<>();

    private static Integer mesh(int part) {
        Integer cached = MESHES.get(part);
        if (cached != null || EMPTY.contains(part)) return cached;
        Integer mesh = null;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(16384)) {
            var builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, DefaultVertexFormat.ENTITY);
            com.hbm.main.ResourceManager.casings.renderPart(new PoseStack().last(), builder, 0, -1, part);
            try (MeshData data = builder.build()) {
                if (data != null) {
                    var buffer = RenderSystem.getDevice().createBuffer(() -> "NTM casing part " + part, GpuBuffer.USAGE_VERTEX,
                            data.vertexBuffer());
                    mesh = RenderPort26.INSTANCE.keep(buffer, data.drawState().indexCount(), data.drawState().primitiveTopology());
                }
            }
        }
        // a part with no mesh stays NTM's for this model (not built again each casing, each frame)
        if (mesh != null) MESHES.put(part, mesh);
        else EMPTY.add(part);
        return mesh;
    }

    private static void off(String why) {
        wire();
        CasingBatches.off(why);
    }

    /** the meshes (another model, or the level left: then the poses' buffers too) */
    public static void clear() {
        for (int m : MESHES.values()) RenderPort26.INSTANCE.free(m);
        MESHES.clear();
        EMPTY.clear();
        meshesOf = null;
    }

    public static void leave() {
        clear();
        CasingBatches.leave();
        type = null;
    }
}
