package acup.gunfeel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import acup.gunfeel.host.render.CameraFrame;
import acup.gunfeel.host.render.Drawer;
import acup.gunfeel.host.render.Intent;
import acup.gunfeel.host.render.Layouts;
import acup.gunfeel.host.render.MaterialSpec;
import acup.gunfeel.host.render.Part;
import acup.gunfeel.host.render.RenderPort;
import acup.gunfeel.host.render.RenderSink;
import acup.gunfeel.host.render.VertexWriter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

// The render port on 26.2 (blaze3d): core's materials, kept buffers and parts as the game's render types, GpuBuffers and
// queued geometry, and its drawers run where this version's table puts their intent (notes/ports-base/render-step1.md
// §2). The places are the lines where the mod drew before the port (Tracers), in the same order with what is drawn
// there besides. RenderFrames counts only the start of the whole rendered frame.
//   intent        plain, CrankShaft's OIT, a shader pack         the vanilla transparency chain
//   AFTER_OPAQUE  AfterOpaqueFeatures (after NTM's casings)      the same
//   QUEUE         SubmitCustomGeometryEvent, the game's queue   the same
//   OVER_GLASS    AfterWeather, a pass of its own               SubmitCustomGeometryEvent, order -1 (with the late
//                                                               render types moved after the translucent terrain:
//                                                               MixinSubmitNodeCollection)
//   AFTER_OPAQUE_SOLID  AfterOpaqueFeatures, first (HIGHEST: before the smoke copies the depth), CasingInstances.draw
//   AFTER_TERRAIN order(0).afterTerrain, particle material target; foreign collector: submitCustomGeometry
public final class RenderPort26 implements RenderPort, RenderSink, CameraFrame {
    public static final RenderPort26 INSTANCE = new RenderPort26();

    private static final int SUBMIT = 1, AFTER_OPAQUE = 2, AFTER_WEATHER = 3, AFTER_OPAQUE_SOLID = 4;

    private final List<RenderType> materials = new ArrayList<>();
    private final Map<MaterialSpec, Integer> materialOf = new HashMap<>();
    private final List<Kept> buffers = new ArrayList<>();
    private final List<Part> parts = new ArrayList<>();
    private final List<SubmitNodeCollector.CustomGeometryRenderer> partRenderers = new ArrayList<>();
    // the late parts' vertices drawn by the pass of its own (AfterWeather), a buffer a part, grown as needed
    private final Map<Integer, GpuBuffer> ownPass = new HashMap<>();
    private final Drawer[][] drawers = new Drawer[Intent.AFTER_TERRAIN + 1][0];
    // the instanced materials (MESH_ENTITY), by handle; null for a render type's
    private final List<Instanced> instanced = new ArrayList<>();
    private final Map<String, Instanced> instancedSources = new HashMap<>();
    // params buffers: handles from PARAMS on (apart from the kept buffers', both freed by free), their names
    private static final int PARAMS = 1 << 24;
    private final List<GpuBuffer> params = new ArrayList<>();
    private final List<String> paramNames = new ArrayList<>();
    // params buffers written in place (map): a ring of the version's mappable buffers each, made at the first map
    private final List<net.minecraft.client.renderer.MappableRingBuffer> rings = new ArrayList<>();
    private final List<Integer> ringSizes = new ArrayList<>();
    private final MapWriter mapWriter = new MapWriter();
    // textures: handles from TEXTURES on, their texture and view
    private static final int TEXTURES = 1 << 25;
    private final List<com.mojang.blaze3d.textures.GpuTexture> textures = new ArrayList<>();
    private final List<com.mojang.blaze3d.textures.GpuTextureView> textureViews = new ArrayList<>();
    // a place's instanced draws, sent together at flush
    private int pending;
    private int[] pendingMaterial = new int[16], pendingMesh = new int[16], pendingParams = new int[16], pendingOffset = new int[16], pendingN = new int[16];
    private org.joml.Matrix4fc viewRef;
    // the place's command encoder: the upload's, used again by its pass (one a frame, as the casings' draw had)
    private com.mojang.blaze3d.systems.CommandEncoder encoder;
    private VertexFormat decalFormat;
    private PrimitiveTopology decalTopology;

    // the place being drawn, the intent whose drawers run there, and its frame
    private int place, drawing;
    private SubmitCustomGeometryEvent submitted;
    private Matrix4f view, pulled;
    private float pulledBy;
    private double camX, camY, camZ;
    private long nanos;

    // a kept buffer: its GpuBuffer once written (a mesh the version made: its index count and topology too)
    private static final class Kept {
        GpuBuffer vb;
        int indexCount;
        PrimitiveTopology topology;
        int layout = Layouts.MESH_ENTITY;
    }

    /**
     * An instanced program whose texture, target and scissor are a render type's the version has this frame: its
     * pipeline, the name of its params' uniform and the bytes a draw reads of it, the pass's name.
     */
    record Instanced(com.mojang.blaze3d.pipeline.RenderPipeline pipeline, java.util.function.Supplier<RenderType> type, String uniform,
            int block, java.util.function.Supplier<String> passName) {
    }

    private final Writer queueWriter = new Writer(), passWriter = new Writer();
    private final FillWriter fillWriter = new FillWriter();
    private static final PoseStack.Pose IDENTITY = new PoseStack().last();

    private RenderPort26() {
        materials.add(null);
        instanced.add(null);
        buffers.add(null);
        parts.add(null);
        partRenderers.add(null);
    }

    // ---- the places (Tracers, the lines where the holes were drawn)

    public static long frameNumber() {
        return RenderFrames.frame;
    }

    /** SubmitCustomGeometryEvent: the queue's drawers, then under the transparency chain the ones over the glass */
    public static void submit(SubmitCustomGeometryEvent event, Vec3 cam, long now) {
        RenderPort26 p = INSTANCE;
        p.at(SUBMIT, cam, now, null);
        p.submitted = event;
        try {
            p.run(Intent.QUEUE);
            if (chain()) p.run(Intent.OVER_GLASS);
            p.run(Intent.AFTER_TERRAIN);
        } finally {
            p.submitted = null;
            p.place = 0;
        }
    }

    /** Submit in place, before the adapter's following draws. */
    public static void submitAt(SubmitCustomGeometryEvent event, Vec3 cam, long now, int intent, Drawer drawer) {
        RenderPort26 p = INSTANCE;
        p.at(SUBMIT, cam, now, null);
        p.submitted = event;
        p.drawing = intent;
        try { drawer.draw(p); }
        finally { p.submitted = null; p.place = 0; p.drawing = 0; }
    }

    /** AfterOpaqueFeatures */
    public static void afterOpaque(RenderLevelStageEvent.AfterOpaqueFeatures event) {
        RenderPort26 p = INSTANCE;
        p.at(AFTER_OPAQUE, event.getLevelRenderState().cameraRenderState.pos, 0, new Matrix4f(event.getModelViewMatrix()));
        try {
            p.run(Intent.AFTER_OPAQUE);
        } finally {
            p.place = 0;
        }
    }

    /** AfterWeather: without the transparency chain, what goes over the glass */
    public static void afterWeather(RenderLevelStageEvent.AfterWeather event) {
        if (chain()) return;
        RenderPort26 p = INSTANCE;
        p.at(AFTER_WEATHER, event.getLevelRenderState().cameraRenderState.pos, 0, new Matrix4f(event.getModelViewMatrix()));
        try {
            p.run(Intent.OVER_GLASS);
        } finally {
            p.place = 0;
        }
    }

    /**
     * AfterOpaqueFeatures, first (CasingInstances.draw, the line where NTM's casings were drawn before the port): the
     * event's view as it is, copied only by a draw (as the casings' draw copied it)
     */
    public static void afterOpaqueSolid(org.joml.Matrix4fc modelView) {
        RenderPort26 p = INSTANCE;
        p.at(AFTER_OPAQUE_SOLID, Minecraft.getInstance().gameRenderer.mainCamera().position(), 0, null);
        p.viewRef = modelView;
        try {
            p.run(Intent.AFTER_OPAQUE_SOLID);
        } finally {
            p.place = 0;
            p.viewRef = null;
            p.pending = 0;
            p.encoder = null;
        }
    }

    private void at(int where, Vec3 cam, long now, Matrix4f modelView) {
        place = where;
        camX = cam.x;
        camY = cam.y;
        camZ = cam.z;
        nanos = now;
        view = modelView;
        pulled = null;
    }

    // the view scaled about the camera: once a place, as drawBatches made its mvMesh once before the port
    private Matrix4f view(float pull) {
        if (pull == 1F) return view;
        if (pulled == null || pulledBy != pull) {
            pulled = new Matrix4f(view).scale(pull);
            pulledBy = pull;
        }
        return pulled;
    }

    private void run(int intent) {
        drawing = intent;
        for (Drawer d : drawers[intent]) {
            d.draw(this);
            if (pending > 0) flush();
        }
        drawing = 0;
    }

    private static boolean chain() {
        return Minecraft.getInstance().gameRenderer.gameRenderState().useShaderTransparency();
    }

    // ---- RenderPort

    /** an instanced program for core's material of that name (a version's own: CasingInstances), before core asks */
    void instancedSource(String name, Instanced source) {
        instancedSources.put(name, source);
    }

    /** a mesh the version made itself (MESH_ENTITY), as a kept buffer's handle */
    int keep(GpuBuffer vb, int indexCount, PrimitiveTopology topology) {
        Kept k = new Kept();
        k.vb = vb;
        k.indexCount = indexCount;
        k.topology = topology;
        buffers.add(k);
        return buffers.size() - 1;
    }

    @Override
    public int material(MaterialSpec spec) {
        Integer known = materialOf.get(spec);
        if (known != null) return known;
        if (spec.layout() == Layouts.MESH_ENTITY) {
            Instanced source = instancedSources.get(spec.name());
            if (source == null) throw new IllegalArgumentException("no instanced program here for " + spec);
            materials.add(null);
            instanced.add(source);
            materialOf.put(spec, materials.size() - 1);
            return materials.size() - 1;
        }
        RenderType type = renderType(spec);
        if (spec.layout() == Layouts.DECAL) {
            if (decalFormat == null) {
                decalFormat = type.format();
                decalTopology = type.primitiveTopology();
            } else if (decalFormat != type.format() || decalTopology != type.primitiveTopology()) {
                throw new IllegalArgumentException("a decal material of another vertex format: " + spec);
            }
        }
        materials.add(type);
        instanced.add(null);
        materialOf.put(spec, materials.size() - 1);
        return materials.size() - 1;
    }

    private static final OutputTarget PARTICLES = new OutputTarget("ntmgunfeel_particles_target",
            () -> Minecraft.getInstance().levelRenderer.particlesTarget());

    // Native names and factories preserve other mods' pipeline hooks.
    static RenderType renderType(MaterialSpec spec) {
        return switch (spec.name()) {
            case "debug_quads" -> RenderTypes.debugQuads();
            case "entity_translucent" -> RenderTypes.entityTranslucent(net.minecraft.resources.Identifier.parse(spec.texture()));
            case "ntmgunfeel_water_ring" -> HoleRender.create(spec.name(), RenderSetup.builder(net.minecraft.client.renderer.RenderPipelines.TRANSLUCENT_PARTICLE)
                    .withTexture("Sampler0", net.minecraft.resources.Identifier.parse(spec.texture())).useLightmap().sortOnUpload()
                    .setOutputTarget(PARTICLES).createRenderSetup());
            default -> HoleRender.of(spec);
        };
    }

    @Override
    public int buffer(int layout, int room) {
        format(layout);
        Kept k = new Kept();
        k.layout = layout;
        buffers.add(k);
        return buffers.size() - 1;
    }

    private VertexFormat format(int layout) {
        return switch (layout) {
            case Layouts.DECAL -> decalFormat;
            case Layouts.COLOR -> com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR;
            case Layouts.PARTICLE -> com.mojang.blaze3d.vertex.DefaultVertexFormat.PARTICLE;
            default -> throw new IllegalArgumentException("no such layout here: " + layout);
        };
    }

    @Override
    public VertexWriter fill(int buffer, int room) {
        Kept k = buffers.get(buffer);
        if (k == null) throw new IllegalStateException("buffer " + buffer + " was freed");
        fillWriter.start(k, room);
        return fillWriter;
    }

    @Override
    public void free(int buffer) {
        if (buffer >= TEXTURES) {
            int i = buffer - TEXTURES;
            if (textureViews.get(i) != null) textureViews.get(i).close();
            var t = textures.get(i);
            if (t != null && !t.isClosed()) t.close();
            textures.set(i, null);
            textureViews.set(i, null);
            return;
        }
        if (buffer >= PARAMS) {
            // a params buffer: its GpuBuffer (or its ring) let go (made again by the next upload or map)
            GpuBuffer ub = params.get(buffer - PARAMS);
            if (ub != null) ub.close();
            params.set(buffer - PARAMS, null);
            var ring = rings.get(buffer - PARAMS);
            if (ring != null) ring.close();
            rings.set(buffer - PARAMS, null);
            return;
        }
        Kept k = buffers.get(buffer);
        if (k == null) return;
        if (k.vb != null) k.vb.close();
        buffers.set(buffer, null);
    }

    @Override
    public int part(Part part) {
        parts.add(part);
        int handle = parts.size() - 1;
        partRenderers.add((pose, vc) -> {
            queueWriter.pose = pose;
            queueWriter.vc = vc;
            try { part.write(queueWriter); }
            finally { queueWriter.vc = null; queueWriter.pose = null; }
        });
        return handle;
    }

    @Override
    public int params(String name) {
        params.add(null);
        paramNames.add(name);
        rings.add(null);
        ringSizes.add(0);
        return PARAMS + params.size() - 1;
    }

    @Override
    public acup.gunfeel.host.render.ParamsWriter map(int handle, int length) {
        int i = handle - PARAMS;
        if (i < 0 || i >= params.size()) throw new IllegalArgumentException("unknown params handle");
        if (params.get(i) != null) throw new IllegalStateException("params " + paramNames.get(i) + " written by upload, then mapped");
        if (mapWriter.data != null) throw new IllegalStateException("params " + paramNames.get(i) + " mapped while another is");
        prepareMap(handle, length);
        mapWriter.start(rings.get(i).currentBuffer().map(false, true), length);
        return mapWriter;
    }

    /** the params buffer's ring made (or grown) before the geometry and the clocks, where the smoke's renderer made it before the port */
    public void prepareMap(int handle, int length) {
        int i = handle - PARAMS;
        var ring = rings.get(i);
        if (ring == null || ringSizes.get(i) < length) {
            if (ring != null) ring.close();
            String name = paramNames.get(i);
            ring = new net.minecraft.client.renderer.MappableRingBuffer(() -> name, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, length);
            rings.set(i, ring);
            ringSizes.set(i, length);
        }
    }

    /** 26.2's own: the buffer a mapped params buffer was last written into, to bind in a pass */
    public GpuBuffer mapped(int handle) {
        return rings.get(handle - PARAMS).currentBuffer();
    }

    /** 26.2's own: a mapped params buffer's next frame goes into the ring's next buffer (after its passes) */
    public void rotate(int handle) {
        rings.get(handle - PARAMS).rotate();
    }

    // the version's mapped memory written as Std140Builder writes it: aligned, then the floats; a matrix by its get
    static final class MapWriter implements acup.gunfeel.host.render.ParamsWriter {
        com.mojang.blaze3d.buffers.GpuBufferSlice.MappedView view;
        java.nio.ByteBuffer data;
        int start, length;

        void start(com.mojang.blaze3d.buffers.GpuBufferSlice.MappedView v, int size) {
            view = v;
            data = v.data();
            start = data.position();
            length = size;
        }

        void start(java.nio.ByteBuffer buffer, int size, Runnable closer) {
            start(new com.mojang.blaze3d.buffers.GpuBufferSlice.MappedView(null, buffer, closer), size);
        }

        private java.nio.ByteBuffer at(int size) {
            if (data == null) throw new IllegalStateException("params written after done");
            int pos = start + net.minecraft.util.Mth.roundToward(data.position() - start, 16);
            if ((long) pos + size > (long) start + length) throw new IndexOutOfBoundsException("params element");
            data.position(pos);
            return data;
        }

        @Override
        public acup.gunfeel.host.render.ParamsWriter vec4(float x, float y, float z, float w) {
            at(16).putFloat(x).putFloat(y).putFloat(z).putFloat(w);
            return this;
        }

        @Override
        public acup.gunfeel.host.render.ParamsWriter mat4(org.joml.Matrix4fc m) {
            java.nio.ByteBuffer to = at(64);
            m.get(to);
            to.position(to.position() + 64);
            return this;
        }

        @Override
        public void done() {
            if (data == null) throw new IllegalStateException("params done twice");
            int written = data.position() - start;
            abort();
            if (written != length) throw new IllegalStateException("params size differs from specification");
        }

        @Override
        public void abort() {
            if (data == null) return;
            data = null;
            var v = view;
            view = null;
            v.close();
        }
    }

    @Override
    public int texture(String name, int width, int height) {
        var device = RenderSystem.getDevice();
        var t = device.createTexture(() -> name, com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_DST | com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING,
                com.mojang.blaze3d.GpuFormat.RGBA8_UNORM, width, height, 1, 1);
        com.mojang.blaze3d.textures.GpuTextureView view;
        try {
            view = device.createTextureView(t);
        } catch (Throwable failure) {
            try {
                t.close();
            } catch (Throwable closing) {
                failure.addSuppressed(closing);
            }
            throw failure;
        }
        textures.add(t);
        textureViews.add(view);
        return TEXTURES + textures.size() - 1;
    }

    @Override
    public void texels(int texture, int x, int y, int width, int height, java.nio.ByteBuffer data) {
        var t = textures.get(texture - TEXTURES);
        if (t == null) throw new IllegalStateException("texture " + texture + " was freed");
        texelEncoder.writeToTexture(t, data, 0, 0, x, y, width, height);
    }

    private com.mojang.blaze3d.systems.CommandEncoder texelEncoder;

    public void beginTexels() {
        texelEncoder = RenderSystem.getDevice().createCommandEncoder();
    }

    public void endTexels() {
        texelEncoder = null;
    }

    @Override
    public boolean live(int texture) {
        int i = texture - TEXTURES;
        if (i < 0 || i >= textures.size()) return false;
        var t = textures.get(i);
        return t != null && !t.isClosed();
    }

    /** 26.2's own: a texture's view, to bind in a pass (null: freed) */
    public com.mojang.blaze3d.textures.GpuTextureView textureView(int texture) {
        int i = texture - TEXTURES;
        return i < 0 || i >= textureViews.size() ? null : textureViews.get(i);
    }

    @Override
    public int paramsAlignment() {
        return RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
    }

    @Override
    public void drawer(int intent, Drawer drawer) {
        Drawer[] was = drawers[intent];
        Drawer[] now = java.util.Arrays.copyOf(was, was.length + 1);
        now[was.length] = drawer;
        drawers[intent] = now;
    }

    // ---- RenderSink

    @Override
    public CameraFrame frame() {
        return this;
    }

    @Override
    public void drawAt(int material, int buffer, int first, int count, double ox, double oy, double oz, float pull) {
        if (place != AFTER_OPAQUE && place != AFTER_WEATHER) throw new IllegalStateException("drawAt only in a pass of the mod's own");
        GpuBuffer vb = buffers.get(buffer).vb;
        RenderType type = materials.get(material);
        PreparedRenderType pr = type.prepare();
        var transforms = RenderSystem.getDynamicUniforms().writeTransform(view(pull),
                new Vector4f(1F, 1F, 1F, 1F), new Vector3f((float) (ox - camX), (float) (oy - camY), (float) (oz - camZ)), new Matrix4f());
        var indices = RenderSystem.getSequentialBuffer(type.primitiveTopology());
        int indexCount = type.primitiveTopology().indexCount(count), firstIndex = type.primitiveTopology().indexCount(first);
        new PreparedRenderType(pr.pipeline(), pr.outputTarget(), transforms, pr.scissorState(), pr.textures())
                .drawFromBuffer(vb, indices.getBuffer(firstIndex + indexCount), indices.type(), 0, firstIndex, indexCount);
    }

    @Override
    public void queue(int material, int part, int room) {
        RenderType type = materials.get(material);
        if (place == SUBMIT) {
            SubmitNodeCollector collector = submitted.getSubmitNodeCollector();
            // over the glass under the transparency chain: before the translucent particles drawn after the
            // translucent terrain (an order before 0 runs first)
            boolean overGlass = drawing == Intent.OVER_GLASS;
            if (drawing == Intent.AFTER_TERRAIN && collector instanceof SubmitNodeStorage storage)
                storage.order(0).afterTerrain.submit(new CustomFeatureRenderer.Submit(submitted.getPoseStack().last().copy(), type, partRenderers.get(part)));
            else if (overGlass && collector instanceof SubmitNodeStorage storage)
                storage.order(-1).submitCustomGeometry(submitted.getPoseStack(), type, partRenderers.get(part));
            else
                collector.submitCustomGeometry(submitted.getPoseStack(), type, partRenderers.get(part));
            return;
        }
        if (place != AFTER_WEATHER) throw new IllegalStateException("queue only into the game's queue or a pass of the mod's own");
        // the pass of its own: written now, drawn now
        try (var bytes = new ByteBufferBuilder(Math.max(64, room) * type.format().getVertexSize())) {
            var builder = new BufferBuilder(bytes, type.primitiveTopology(), type.format());
            passWriter.pose = IDENTITY;
            passWriter.vc = builder;
            parts.get(part).write(passWriter);
            try (MeshData mesh = builder.build()) {
                if (mesh == null) return;
                var device = RenderSystem.getDevice();
                java.nio.ByteBuffer data = mesh.vertexBuffer();
                GpuBuffer vb = ownPass.get(part);
                if (vb == null || vb.size() < data.remaining()) {
                    if (vb != null) vb.close();
                    vb = device.createBuffer(() -> "Bullet hole vertices", 40, data.remaining());
                    ownPass.put(part, vb);
                }
                device.createCommandEncoder().writeToBuffer(vb.slice(), data);
                var indices = RenderSystem.getSequentialBuffer(mesh.drawState().primitiveTopology());
                int count = mesh.drawState().indexCount();
                type.prepare().drawFromBuffer(vb, indices.getBuffer(count), indices.type(), 0, 0, count);
            }
        }
    }

    @Override
    public void upload(int handle, java.nio.ByteBuffer data, int length) {
        if (rings.get(handle - PARAMS) != null) throw new IllegalStateException("params " + paramNames.get(handle - PARAMS) + " mapped, then uploaded");
        var device = RenderSystem.getDevice();
        GpuBuffer ub = params.get(handle - PARAMS);
        if (ub == null || ub.size() < length) {
            if (ub != null) ub.close();
            String name = paramNames.get(handle - PARAMS);
            ub = device.createBuffer(() -> name, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, length);
            params.set(handle - PARAMS, ub);
        }
        if (encoder == null) encoder = device.createCommandEncoder();
        // writeToBuffer takes data from its position to its limit: 0..length whatever they are, and given back as they were
        int position = data.position(), limit = data.limit();
        data.limit(length).position(0);
        try {
            encoder.writeToBuffer(ub.slice(0, length), data);
        } finally {
            data.limit(limit).position(position);
        }
    }

    @Override
    public void drawInstanced(int material, int mesh, int paramsHandle, int offset, int n) {
        if (place != AFTER_OPAQUE_SOLID) throw new IllegalStateException("drawInstanced only in a pass of the mod's own");
        if (instanced.get(material) == null) throw new IllegalArgumentException("not an instanced material: " + material);
        // one index buffer a pass, the first mesh's kind: the place's meshes all of its topology
        if (pending > 0) oneTopology(buffers.get(pendingMesh[0]).topology, buffers.get(mesh).topology, mesh);
        if (pending == pendingMesh.length) {
            int m = pending * 2;
            pendingMaterial = java.util.Arrays.copyOf(pendingMaterial, m);
            pendingMesh = java.util.Arrays.copyOf(pendingMesh, m);
            pendingParams = java.util.Arrays.copyOf(pendingParams, m);
            pendingOffset = java.util.Arrays.copyOf(pendingOffset, m);
            pendingN = java.util.Arrays.copyOf(pendingN, m);
        }
        pendingMaterial[pending] = material;
        pendingMesh[pending] = mesh;
        pendingParams[pending] = paramsHandle;
        pendingOffset[pending] = offset;
        pendingN[pending++] = n;
    }

    static void oneTopology(PrimitiveTopology first, PrimitiveTopology next, int mesh) {
        if (first != next) throw new IllegalArgumentException("mesh " + mesh + " is " + next + ", the place's first " + first + ": one flush, one topology");
    }

    // the place's instanced draws in one pass: the first material's render type gives the target, scissor and textures
    // (CasingInstances' draw before the port, its calls in its order)
    @Override
    public void flush() {
        int draws = pending;
        pending = 0;
        if (draws == 0) return;
        Instanced first = instanced.get(pendingMaterial[0]);
        int mostIndices = 0;
        for (int d = 0; d < draws; d++) mostIndices = Math.max(mostIndices, buffers.get(pendingMesh[d]).indexCount);
        if (encoder == null) encoder = RenderSystem.getDevice().createCommandEncoder();
        PreparedRenderType prepared = first.type().get().prepare();
        com.mojang.blaze3d.pipeline.RenderTarget target = prepared.outputTarget().getRenderTarget();
        com.mojang.blaze3d.textures.GpuTextureView color = RenderSystem.outputColorTextureOverride != null ? RenderSystem.outputColorTextureOverride
                : target.getColorTextureView();
        com.mojang.blaze3d.textures.GpuTextureView depth = !target.useDepth ? null
                : RenderSystem.outputDepthTextureOverride != null ? RenderSystem.outputDepthTextureOverride : target.getDepthTextureView();
        var transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(viewRef), new Vector4f(1F, 1F, 1F, 1F), new Vector3f(),
                new Matrix4f());
        // the index buffer for the largest mesh got before the pass (it grows by being made anew)
        var indices = RenderSystem.getSequentialBuffer(buffers.get(pendingMesh[0]).topology);
        GpuBuffer indexBuffer = indices.getBuffer(mostIndices);
        try (var pass = encoder.createRenderPass(first.passName(), color, java.util.Optional.empty(), depth, java.util.OptionalDouble.empty())) {
            Instanced now = null;
            for (int d = 0; d < draws; d++) {
                Instanced m = instanced.get(pendingMaterial[d]);
                if (m != now) {
                    PreparedRenderType pr = m == first ? prepared : m.type().get().prepare();
                    pass.setPipeline(m.pipeline());
                    var scissor = pr.scissorState();
                    if (scissor.enabled()) pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height());
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", transforms);
                    for (var t : pr.textures()) pass.bindTexture(t.name(), t.textureView(), t.sampler());
                    pass.setIndexBuffer(indexBuffer, indices.type());
                    now = m;
                }
                Kept mesh = buffers.get(pendingMesh[d]);
                pass.setVertexBuffer(0, mesh.vb.slice());
                pass.setUniform(m.uniform(), params.get(pendingParams[d] - PARAMS).slice(pendingOffset[d], m.block()));
                pass.drawIndexed(mesh.indexCount, pendingN[d], 0, 0, 0);
            }
        }
    }

    // ---- CameraFrame

    @Override
    public double camX() {
        return camX;
    }

    @Override
    public double camY() {
        return camY;
    }

    @Override
    public double camZ() {
        return camZ;
    }

    @Override
    public long nanos() {
        // AfterWeather: nobody there read the clock before the drawer, so the drawer's asking is the reading
        return place == SUBMIT ? nanos : GunClock.nanos();
    }

    @Override
    public int translucency() {
        if (chain()) return VANILLA_CHAIN;
        if (acup.gunfeel.smoke.client.SmokeRender.irisPack()) return SHADER_PACK;
        return Minecraft.getInstance().options.improvedTransparency().get() ? OIT : PLAIN;
    }

    // ---- the writers: the calls the holes made on the game's VertexConsumer before the port, one by one

    // (package-private: HoleDecalsBitTest writes through it)
    static class Writer implements VertexWriter {
        PoseStack.Pose pose;
        VertexConsumer vc;

        private void active() {
            if (vc == null) throw new IllegalStateException("writer after end");
        }

        @Override public void color(float x, float y, float z, int argb) {
            active();
            vc.addVertex(pose.pose(), x, y, z).setColor(argb);
        }

        @Override public void particle(float x, float y, float z, int argb, float u, float v, int light) {
            active();
            vc.addVertex(pose, x, y, z).setColor(argb).setUv(u, v).setLight(light);
        }

        @Override
        public void decal(float x, float y, float z, int argb, float u, float v, int light, float nx, float ny, float nz) {
            active();
            vc.addVertex(pose, x, y, z).setColor(argb).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
        }

        @Override
        public int done() {
            throw new IllegalStateException("a part is finished by the version");
        }
    }

    // a kept buffer written anew: on the CPU first, then into its GpuBuffer (made anew, with room to grow, when too small)
    private final class FillWriter extends Writer {
        ByteBufferBuilder bytes;
        Kept kept;

        void start(Kept k, int room) {
            // a fill left unfinished (thrown out of before done()): its memory given back, this one begins clean
            if (bytes != null) bytes.close();
            kept = k;
            VertexFormat format = format(k.layout);
            bytes = new ByteBufferBuilder(Math.max(64, room) * format.getVertexSize());
            pose = IDENTITY;
            vc = new BufferBuilder(bytes, k.layout == Layouts.DECAL ? decalTopology : PrimitiveTopology.QUADS, format);
        }

        @Override
        public int done() {
            try (ByteBufferBuilder b = bytes) {
                bytes = null;
                try (MeshData mesh = ((BufferBuilder) vc).build()) {
                    if (mesh == null) return 0;
                    var device = RenderSystem.getDevice();
                    java.nio.ByteBuffer data = mesh.vertexBuffer();
                    GpuBuffer vb = kept.vb;
                    if (vb == null || vb.size() < data.remaining()) {
                        if (vb != null) vb.close();
                        // room to grow: the open batch gets a hole at a time
                        vb = device.createBuffer(() -> "Bullet hole batch", 40, data.remaining() * 3L / 2L + 1024L);
                        kept.vb = vb;
                    }
                    device.createCommandEncoder().writeToBuffer(vb.slice(0, data.remaining()), data);
                    return mesh.drawState().vertexCount();
                }
            } finally {
                vc = null;
                kept = null;
            }
        }
    }
}
