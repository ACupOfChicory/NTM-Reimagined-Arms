package acup.gunfeel;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.mojang.blaze3d.vertex.PoseStack;

import dev.golem.core.render.gem.Gem;
import dev.golem.core.render.gem.GemBER;
import dev.golem.core.render.gem.Surface;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.renderer.texture.OverlayTexture;

// port/continuum: what a machine of NTM Continuum draws, for RenderedMesh. Continuum's machine renderers are Golem
// Core GemBERs: the model is declared once and replayed from meshes kept on the GPU (StaticMeshes) or by an instanced
// visual, so a recorder handed to their submit gets nothing. Here the declared model is walked as the draw walks it
// (Gem.walk: the same visibility tests and pose-stack calls) and each part goes to the recorder as the non-resident
// draw submits it: its geometry drawn into the part's surface's render type. What a GemBER builds each frame outside
// the declaration (beams, flames, fluid levels: submitUndeclared) is left out.
public final class GemMesh {
    private GemMesh() {
    }

    // ---- a machine read before MeshShapes takes it, so no frame gives it more than a look (under 2 ms): on the game's
    // thread only the renderer's state is extracted; on a worker of its own the model is walked for its textures, they
    // are read (a big PNG and its masks, RenderedMesh.Image, take tens of milliseconds; each triangle's window test is made
    // there too), its triangles are made, then MeshShapes' capture is built (build) and run (aside) there too.
    // MeshShapes starts the machine once ready() says so

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ntm-gunfeel Continuum machine take");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    // read on the worker, not handed to RenderedMesh yet (its table is the game thread's)
    private static final Map<Identifier, RenderedMesh.Tex> READ = new ConcurrentHashMap<>();
    private static final Set<Identifier> READING = ConcurrentHashMap.newKeySet();

    // true: every texture the machine's renderer draws with is known to RenderedMesh, it can be taken now; false: some
    // are being read (asked again next frame). The game's thread only.
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean ready(BlockEntity be) {
        BlockPos pos = be.getBlockPos().immutable();
        // its triangles asked for already
        Made made = MADE.get(pos);
        if (made != null && made.be == be) return made.tris.isDone();
        BlockEntityRenderer renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
        if (!(renderer instanceof GemBER ber)) {
            // any other renderer is read here, in a frame of its own (it reads the block entity as it draws)
            MADE.put(pos, new Made(be, java.util.concurrent.CompletableFuture.completedFuture(RenderedMesh.of(be))));
            return false;
        }
        // its state and textures taken once, while they are read
        Pending pending = PENDING.get(pos);
        if (pending == null || pending.be != be) {
            pending = pending(be, renderer, ber);
            if (pending == null) return true; // RenderedMesh reads it as it can
            PENDING.put(pos, pending);
        }
        if (!pending.walked.isDone()) return false;
        Map<Identifier, java.util.List<Drawn>> textures;
        try {
            textures = pending.walked.get();
        } catch (Exception e) {
            PENDING.remove(pos);
            return true; // RenderedMesh reads it as it can
        }
        boolean all = true;
        for (Identifier id : textures.keySet()) {
            if (RenderedMesh.known(id)) continue;
            RenderedMesh.Tex got = READ.remove(id);
            if (got != null) {
                RenderedMesh.known(id, got);
                continue;
            }
            AbstractTexture had = Minecraft.getInstance().getTextureManager().getTexture(id);
            // an atlas or a texture drawn into: RenderedMesh reads it at once, cheaply
            if (had instanceof TextureAtlas || had instanceof DynamicTexture) continue;
            all = false;
            if (!READING.add(id)) continue;
            java.util.List<Drawn> drawn = textures.get(id);
            WORKER.execute(() -> {
                RenderedMesh.Tex tex = RenderedMesh.read(id);
                try {
                    if (tex instanceof RenderedMesh.Image img && img.window != null) warm(img, drawn);
                } catch (Throwable t) {
                    GunFeel.LOGGER.debug("Could not look at the windows of {} ahead", id, t);
                }
                READ.put(id, tex);
                READING.remove(id);
            });
        }
        if (!all) return false;
        // its triangles, made on the worker from the state taken
        PENDING.remove(pos);
        Object taken = pending.state;
        MADE.put(pos, new Made(be, WORKER.submit(() -> RenderedMesh.ofGem(pos, renderer, taken))));
        return false;
    }

    private record Pending(BlockEntity be, Object state, java.util.concurrent.Future<Map<Identifier, java.util.List<Drawn>>> walked) {
    }

    private static final Map<BlockPos, Pending> PENDING = new ConcurrentHashMap<>();

    // the renderer's state taken from the block entity (the game's thread), and the textures its parts draw with (walked on
    // the worker), or null
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Pending pending(BlockEntity be, BlockEntityRenderer renderer, GemBER ber) {
        Object state;
        try {
            state = renderer.createRenderState();
            var mc = Minecraft.getInstance();
            renderer.extractRenderState(be, (net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState) state,
                    mc.getDeltaTracker().getGameTimeDeltaPartialTick(true), mc.gameRenderer.getMainCamera().position(), null);
        } catch (Throwable t) {
            return null;
        }
        Object taken = state;
        return new Pending(be, state, WORKER.submit(() -> {
            Map<Identifier, java.util.List<Drawn>> textures = new java.util.HashMap<>();
            Gem.walk(ber.gem(), taken, new PoseStack(), (Gem.Visitor) (part, geometry, at) -> {
                Surface surface = ((Gem.Part) part).surfaceOf(taken);
                if (surface != null) {
                    Identifier id = Identifier.tryParse(surface.texture());
                    if (id != null) textures.computeIfAbsent(id, k -> new java.util.ArrayList<>())
                            .add(new Drawn((dev.golem.core.render.gem.Geometry) geometry, surface.renderType().mode()));
                }
            });
            return textures;
        }));
    }

    // a world left: what was asked for there is dropped
    public static void forget() {
        PENDING.clear();
        MADE.clear();
        build = null;
        buildOf = null;
        aside = null;
        asideOf = null;
    }

    // a machine's triangles made on the worker (ready), for MeshShapes' capture
    private record Made(BlockEntity be, java.util.concurrent.Future<java.util.List<RenderedMesh.Tri>> tris) {
    }

    private static final Map<BlockPos, Made> MADE = new ConcurrentHashMap<>();

    // the capture of the machine here is made: what was made for it is dropped
    public static void taken(BlockPos pos) {
        MADE.remove(pos);
    }

    // what the block entity draws (asked by the capture, on the worker): made when ready() said so, else read now
    public static java.util.List<RenderedMesh.Tri> trisOf(BlockEntity be) {
        Made made = MADE.get(be.getBlockPos());
        if (made != null && made.be == be && made.tris.isDone()) {
            try {
                return made.tris.get();
            } catch (Exception e) {
                GunFeel.LOGGER.warn("Could not read what {} draws on the worker", be.getType(), e);
            }
        }
        return RenderedMesh.of(be);
    }

    // ---- work of MeshShapes' capture done on the worker (Capture.step, which reads only what the capture holds): true
    // once it is done; the worker's exception is thrown here

    private static Object asideOf;
    private static java.util.concurrent.Future<Boolean> aside;

    public static boolean aside(Object of, java.util.function.BooleanSupplier work) throws Exception {
        if (asideOf != of || aside == null) {
            asideOf = of;
            aside = WORKER.submit(work::getAsBoolean);
            return false;
        }
        if (!aside.isDone()) return false;
        var done = aside;
        aside = null;
        asideOf = null;
        return done.get();
    }

    // something made on the worker for MeshShapes (a capture, made of the machine's blocks and triangles): null until it
    // is made (asked again with an equal key next frame); the worker's exception is thrown here
    private static Object buildOf;
    private static java.util.concurrent.Future<?> build;

    @SuppressWarnings("unchecked")
    public static <T> T build(Object of, java.util.concurrent.Callable<T> work) throws Exception {
        if (!of.equals(buildOf) || build == null) {
            buildOf = of;
            build = WORKER.submit(work);
            return null;
        }
        if (!build.isDone()) return null;
        var done = build;
        build = null;
        buildOf = null;
        return (T) done.get();
    }

    private record Drawn(dev.golem.core.render.gem.Geometry geometry, com.mojang.blaze3d.vertex.VertexFormat.Mode topology) {
    }

    // Each of the texture's triangles asked once whether it covers a window pixel (Image.glassIn, kept per triangle in
    // the texture's masks), as RenderedMesh will ask when it reads the machine: on the worker, while the texture is not
    // handed to RenderedMesh yet. The triangles as RenderedMesh.Capture makes them of what the geometry draws.
    private static void warm(RenderedMesh.Image img, java.util.List<Drawn> drawn) {
        for (Drawn d : drawn) {
            Uvs uvs = new Uvs();
            d.geometry().draw(new PoseStack().last(), uvs, 0xF000F0, OverlayTexture.NO_OVERLAY, -1);
            float[] v = uvs.uv;
            int n = uvs.n;
            switch (d.topology()) {
                case QUADS -> {
                    for (int i = 0; i + 3 < n; i += 4) {
                        img.glassIn(tri(v, i, i + 1, i + 2));
                        img.glassIn(tri(v, i, i + 2, i + 3));
                    }
                }
                case TRIANGLES -> {
                    for (int i = 0; i + 2 < n; i += 3) img.glassIn(tri(v, i, i + 1, i + 2));
                }
                default -> {
                }
            }
        }
    }

    private static float[] tri(float[] v, int a, int b, int c) {
        return new float[] {v[a * 2], v[a * 2 + 1], v[b * 2], v[b * 2 + 1], v[c * 2], v[c * 2 + 1]};
    }

    // only the texture coordinates of what is drawn
    private static final class Uvs implements com.mojang.blaze3d.vertex.VertexConsumer {
        float[] uv = new float[256];
        int n;

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
            if ((n + 1) * 2 > uv.length) uv = java.util.Arrays.copyOf(uv, uv.length * 2);
            n++;
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
            if (n > 0) {
                uv[(n - 1) * 2] = u;
                uv[(n - 1) * 2 + 1] = v;
            }
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int color) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setLineWidth(float width) {
            return this;
        }
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
