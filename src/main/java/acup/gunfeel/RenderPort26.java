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
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

// The render port on 26.2 (blaze3d): core's materials, kept buffers and parts as the game's render types, GpuBuffers and
// queued geometry, and its drawers run where this version's table puts their intent (notes/ports-base/render-step1.md
// §2). The places are the lines where the mod drew before the port (Tracers), in the same order with what is drawn
// there besides: no listener of its own.
//   intent        plain, CrankShaft's OIT, a shader pack         the vanilla transparency chain
//   AFTER_OPAQUE  AfterOpaqueFeatures (after NTM's casings)      the same
//   QUEUE         SubmitCustomGeometryEvent, the game's queue   the same
//   OVER_GLASS    AfterWeather, a pass of its own               SubmitCustomGeometryEvent, order -1 (with the late
//                                                               render types moved after the translucent terrain:
//                                                               MixinSubmitNodeCollection)
public final class RenderPort26 implements RenderPort, RenderSink, CameraFrame {
    public static final RenderPort26 INSTANCE = new RenderPort26();

    private static final int SUBMIT = 1, AFTER_OPAQUE = 2, AFTER_WEATHER = 3;

    private final List<RenderType> materials = new ArrayList<>();
    private final Map<MaterialSpec, Integer> materialOf = new HashMap<>();
    private final List<Kept> buffers = new ArrayList<>();
    private final List<Part> parts = new ArrayList<>();
    private final List<SubmitNodeCollector.CustomGeometryRenderer> partRenderers = new ArrayList<>();
    // the late parts' vertices drawn by the pass of its own (AfterWeather), a buffer a part, grown as needed
    private final Map<Integer, GpuBuffer> ownPass = new HashMap<>();
    private final Drawer[][] drawers = new Drawer[Intent.OVER_GLASS + 1][0];
    private VertexFormat decalFormat;
    private PrimitiveTopology decalTopology;

    // the place being drawn, the intent whose drawers run there, and its frame
    private int place, drawing;
    private SubmitCustomGeometryEvent submitted;
    private Matrix4f view, pulled;
    private float pulledBy;
    private double camX, camY, camZ;
    private long nanos;

    // a kept buffer: its GpuBuffer once written
    private static final class Kept {
        GpuBuffer vb;
    }

    private final Writer queueWriter = new Writer(), passWriter = new Writer();
    private final FillWriter fillWriter = new FillWriter();
    private static final PoseStack.Pose IDENTITY = new PoseStack().last();

    private RenderPort26() {
        materials.add(null);
        buffers.add(null);
        parts.add(null);
        partRenderers.add(null);
    }

    // ---- the places (Tracers, the lines where the holes were drawn)

    /** SubmitCustomGeometryEvent: the queue's drawers, then under the transparency chain the ones over the glass */
    public static void submit(SubmitCustomGeometryEvent event, Vec3 cam, long now) {
        RenderPort26 p = INSTANCE;
        p.at(SUBMIT, cam, now, null);
        p.submitted = event;
        try {
            p.run(Intent.QUEUE);
            if (chain()) p.run(Intent.OVER_GLASS);
        } finally {
            p.submitted = null;
            p.place = 0;
        }
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
        for (Drawer d : drawers[intent]) d.draw(this);
        drawing = 0;
    }

    private static boolean chain() {
        return Minecraft.getInstance().gameRenderer.gameRenderState().useShaderTransparency();
    }

    // ---- RenderPort

    @Override
    public int material(MaterialSpec spec) {
        Integer known = materialOf.get(spec);
        if (known != null) return known;
        RenderType type = HoleRender.of(spec);
        if (spec.layout() == Layouts.DECAL) {
            if (decalFormat == null) {
                decalFormat = type.format();
                decalTopology = type.primitiveTopology();
            } else if (decalFormat != type.format() || decalTopology != type.primitiveTopology()) {
                throw new IllegalArgumentException("a decal material of another vertex format: " + spec);
            }
        }
        materials.add(type);
        materialOf.put(spec, materials.size() - 1);
        return materials.size() - 1;
    }

    @Override
    public int buffer(int layout, int room) {
        if (layout != Layouts.DECAL) throw new IllegalArgumentException("no such layout here: " + layout);
        buffers.add(new Kept());
        return buffers.size() - 1;
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
            part.write(queueWriter);
        });
        return handle;
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
            if (overGlass && collector instanceof SubmitNodeStorage storage)
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

        @Override
        public void decal(float x, float y, float z, int argb, float u, float v, int light, float nx, float ny, float nz) {
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
            bytes = new ByteBufferBuilder(Math.max(64, room) * decalFormat.getVertexSize());
            pose = IDENTITY;
            vc = new BufferBuilder(bytes, decalTopology, decalFormat);
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
