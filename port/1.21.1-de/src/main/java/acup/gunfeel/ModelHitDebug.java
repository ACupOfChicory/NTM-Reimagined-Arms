package acup.gunfeel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

// Port copy (notes/ports/de-1.21.1.md, «Копии»), as port/1.12.2's: F3+B with model hitboxes (26.2: ModelHitDebug,
// MixinModelHitboxDebug, HeadDebug). Where the client has a usable pose the entity's box and eye strip give way to the
// outline of its posed cubes (ModelContours: edges hidden inside another part left out), in 26.2's orange, with the
// head zones in its green; a multipart entity's part boxes (the dragon's) are hidden when it has a pose. The pose is
// the client's ModelHits store, the one its rays use, so what is drawn is what is tested.
// As 26.2 draws F3+B after the world with this frame's pose, renderHitbox only marks the entity here and the outline
// is drawn at the level's end (render, GunFeel's AFTER_LEVEL after ModelHitClient.finishFrame), once this frame's
// poses are in the store, under the level's model-view matrix. The green is what the server
// counts as head: each zone's own cubes (HeadZone.pieces, else its box), cut by its planes, as 26.2's HeadDebug draws
// them; the orange leaves out what lies under the green, so a head edge is drawn once, in green. 26.2's GPU mask, which
// hides an edge behind another mob or a block, is not ported for an outline drawn only in debug mode.
// -Dntmgunfeel.outlineLegacy (the stand's numbers before): as it was, drawn at renderHitbox with the pose the last frame
// published, the head zones' boxes over the whole outline.
public final class ModelHitDebug {
    static final boolean LEGACY = Boolean.getBoolean("ntmgunfeel.outlineLegacy");
    private record Target(Entity entity, float partial) {}
    private static final List<Target> TARGETS = new ArrayList<>();
    record Colored(List<Vec3[]> orange, List<Vec3[]> green) {}
    // the level is being drawn (AFTER_SKY to render): renderHitbox outside it (an inventory's preview) keeps its box
    private static boolean level;
    // the level's fog as its entities had it, for lines drawn after LevelRenderer turned it off
    private static float fogStart, fogEnd;
    private static float[] fogColor = {0, 0, 0, 0};
    private static com.mojang.blaze3d.shaders.FogShape fogShape = com.mojang.blaze3d.shaders.FogShape.SPHERE;

    private ModelHitDebug() {}

    // a new level: what renderHitbox left and no frame drew
    public static void beginFrame() { TARGETS.clear(); MEMO.clear(); level = false; }

    // RenderLevelStageEvent AFTER_SKY: the level's entities come next
    public static void beginLevel() { TARGETS.clear(); level = true; }

    // AFTER_BLOCK_ENTITIES: the fog the level's entities and their F3+B are drawn with
    public static void captureFog() {
        fogStart = com.mojang.blaze3d.systems.RenderSystem.getShaderFogStart();
        fogEnd = com.mojang.blaze3d.systems.RenderSystem.getShaderFogEnd();
        fogColor = com.mojang.blaze3d.systems.RenderSystem.getShaderFogColor().clone();
        fogShape = com.mojang.blaze3d.systems.RenderSystem.getShaderFogShape();
    }

    // renderHitbox HEAD; true: drawn here or at the level's end (render), the box and the eye strip are not
    public static boolean draw(PoseStack pose, VertexConsumer lines, Entity entity, float partial) {
        if (!(entity instanceof LivingEntity) || !ModelHitClient.enabled() || !acup.gunfeel.compat.Feature.MODEL_HITBOXES.on()) return false;
        if (!level && !LEGACY) return false;
        if (LEGACY) { long started = System.nanoTime(); boolean drawn = legacy(pose, lines, entity, partial); OutlineDrift.drawNanos += System.nanoTime() - started; return drawn; }
        boolean custom = ModelHits.hasPose(entity);
        if (custom) TARGETS.add(new Target(entity, partial));
        else MobSmoke.debug(entity, false);
        return custom;
    }

    // the boxes are in the world where the last frame drew the model (ModelHitClient.publishAt: the lerped position); the
    // pose stands at this frame's lerped position, without the renderer's offset (EntityRenderDispatcher.render
    // takes it off before the hitbox)
    private static boolean legacy(PoseStack pose, VertexConsumer lines, Entity entity, float partial) {
        List<ModelBox> boxes = ModelHits.worldBoxes(entity);
        boolean custom = !boxes.isEmpty();
        MobSmoke.debug(entity, custom);
        if (!custom) return false;
        // as LevelRenderer.renderEntity places it (xOld, not getPosition's xo)
        Vec3 at = lerped(entity, partial);
        var orange = ModelContours.edges(boxes);
        var green = new ArrayList<Vec3[]>();
        for (HeadZone zone : ModelHits.headZones(entity)) green.addAll(ModelContours.edges(List.of(zone.box())));
        lines(pose, lines, orange, at, 1F, .8F, 1F / 3);
        lines(pose, lines, green, at, .15F, 1F, .15F);
        OutlineDrift.drawn(entity, boxes, new Colored(orange, green));
        return true;
    }

    // RenderLevelStageEvent AFTER_LEVEL, after ModelHitClient.finishFrame: the outlines renderHitbox left, with the pose
    // this frame drew (26.2's render(view) after finishFrame). The level's model-view matrix is off the stack by now
    // With Fabulous graphics 1.21.1's lines() go to the item entities' target, which is in the frame already: these go
    // to the main one (Lines.TYPE), with the fog the level had
    public static void render(org.joml.Matrix4fc view, net.minecraft.client.Camera camera) {
        level = false;
        if (TARGETS.isEmpty()) return;
        var targets = List.copyOf(TARGETS);
        TARGETS.clear();
        var stack = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
        stack.pushMatrix(); stack.set(view);
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        com.mojang.blaze3d.systems.RenderSystem.setShaderFogStart(fogStart);
        com.mojang.blaze3d.systems.RenderSystem.setShaderFogEnd(fogEnd);
        com.mojang.blaze3d.systems.RenderSystem.setShaderFogColor(fogColor[0], fogColor[1], fogColor[2], fogColor[3]);
        com.mojang.blaze3d.systems.RenderSystem.setShaderFogShape(fogShape);
        var buffers = net.minecraft.client.Minecraft.getInstance().renderBuffers().bufferSource();
        try {
            long started = System.nanoTime();
            outlines(targets, buffers.getBuffer(Lines.TYPE), camera.getPosition());
            OutlineDrift.drawNanos += System.nanoTime() - started;
            long batch = MobBenchMetrics.start();
            buffers.endBatch(Lines.TYPE);
            MobBenchmark.debug(MobBenchmark.LINES, batch);
        } finally {
            stack.popMatrix();
            com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
            net.minecraft.client.renderer.FogRenderer.setupNoFog();
        }
    }

    // 1.21.1's lines() into the main target (its state shards are protected)
    private static final class Lines extends net.minecraft.client.renderer.RenderType {
        static final net.minecraft.client.renderer.RenderType TYPE = com.hbm.backport.client.rendertype.RenderTypeFactory.create(
                "ntmgunfeel_model_hitbox_lines", com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR_NORMAL,
                com.mojang.blaze3d.vertex.VertexFormat.Mode.LINES, 1536, false, false, CompositeState.builder()
                        .setShaderState(RENDERTYPE_LINES_SHADER)
                        .setLineState(new LineStateShard(java.util.OptionalDouble.empty()))
                        .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setOutputState(MAIN_TARGET)
                        .setWriteMaskState(COLOR_DEPTH_WRITE)
                        .setCullState(NO_CULL)
                        .createCompositeState(false));

        private Lines() {
            super("", com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR_NORMAL, com.mojang.blaze3d.vertex.VertexFormat.Mode.LINES,
                    0, false, false, () -> {}, () -> {});
        }
    }

    // lines in a GPU buffer of their own (26.2's ModelDebugLines), in their own coordinates: built once, drawn each frame
    // under a matrix that puts them where they stand. One a frame has not drawn for SWEEP frames (its entity gone, its
    // pose changed, the level left) gives its buffer back
    // how old a shown contour may be when it is not the last frame's (ModelContours, the port's rewrite.txt; the drift
    // stand checks the same): -PcontourLag=<ms>, 1/60 s
    static final long LAG = (long) (Double.parseDouble(System.getProperty("ntmgunfeel.contourLag", "16.667")) * 1e6);
    private static class Drawn {
        com.mojang.blaze3d.vertex.VertexBuffer buffer;
        boolean empty;
        long used;
    }
    // an entity's orange: its contour (ModelContours.Outline: the cache gives the same list for the same shape) less
    // what lies under the head zones' green, in the contour's frame. It holds while the contour and the zones' pieces
    // (piece: the same list for the same cut) are the same lists: zones that come after the contour cut it anew
    private static final class Memo extends Drawn {
        final List<Vec3[]> contour, orange;
        final List<List<Vec3[]>> pieces;
        Memo(List<Vec3[]> contour, List<List<Vec3[]>> pieces, List<Vec3[]> orange) {
            this.contour = contour; this.pieces = pieces; this.orange = orange;
        }
        boolean holds(List<Vec3[]> contour, List<List<Vec3[]>> pieces) {
            if (this.contour != contour || this.pieces.size() != pieces.size()) return false;
            for (int i = 0; i < pieces.size(); i++) if (this.pieces.get(i) != pieces.get(i)) return false;
            return true;
        }
    }
    private static final java.util.Map<Entity, Memo> MEMO = new java.util.WeakHashMap<>();
    // a head cube's green (piece: in the cube's own coordinates) by its list, render thread only
    private static final java.util.Map<List<Vec3[]>, Drawn> PIECE_LINES = new java.util.IdentityHashMap<>();
    private static final List<Drawn> BUFFERED = new ArrayList<>();
    private static final int SWEEP = 120;
    private static long frames;

    // As 26.2's render: the outline of a mob shown before comes from ModelContours.outlines with its owner, so a new
    // pose is built in the background while the frame draws the last outline in the body's frame of this one; a limb
    // is late by the build, a frame at most (OutlineDrift holds it to that). The orange's cut against the green is made
    // in the frame the outline comes (in the background it would be a frame later), the mobs' cuts side by side. The
    // green is the head zones as this frame has them (HeadDebug): each cube's cut is kept in the cube's coordinates and
    // drawn under the cube's matrix, so the head is exact
    private static void outlines(List<Target> targets, VertexConsumer out, Vec3 cam) {
        var pose = new PoseStack();
        var entities = new ArrayList<Entity>(targets.size());
        var all = new ArrayList<List<ModelBox>>(targets.size());
        for (var target : targets) {
            Entity entity = target.entity();
            if (entity.isRemoved()) continue;
            List<ModelBox> boxes = ModelHits.worldBoxes(entity);
            MobSmoke.debug(entity, !boxes.isEmpty());
            if (boxes.isEmpty()) {
                // the pose lapsed since renderHitbox: the box it left out, where vanilla draws it
                var box = entity.getBoundingBox().move(lerped(entity, target.partial()).subtract(entity.position()).subtract(cam));
                net.minecraft.client.renderer.LevelRenderer.renderLineBox(pose, out, box, 1F, 1F, 1F, 1F);
                continue;
            }
            entities.add(entity); all.add(boxes);
        }
        if (entities.isEmpty()) return;
        long contour = MobBenchMetrics.start();
        var outlines = ModelContours.outlines(all, entities);
        MobBenchmark.debug(MobBenchmark.CONTOUR, contour);
        long cut = MobBenchMetrics.start();
        int n = entities.size();
        var memos = new Memo[n];
        var zones = new List<?>[n];
        var cubes = new List<?>[n];
        var cuts = new List<?>[n];
        var greens = new List<?>[n];
        var misses = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            var entity = entities.get(i);
            var outline = outlines.get(i);
            var headZones = ModelHits.headZones(entity);
            zones[i] = headZones;
            var boxes = new ArrayList<ModelBox>();
            var pieces = new ArrayList<List<Vec3[]>>();
            for (var zone : headZones)
                for (var box : zone.pieces().isEmpty() ? List.of(zone.box()) : zone.pieces()) {
                    boxes.add(box); pieces.add(piece(box, zone.planes()));
                }
            cubes[i] = boxes; cuts[i] = pieces;
            var memo = MEMO.get(entity);
            if (memo != null && memo.holds(outline.edges(), pieces)) { memos[i] = memo; continue; }
            // the last one's buffer goes at the next sweep
            if (memo != null) memo.used = Long.MIN_VALUE / 2;
            greens[i] = local(green(headZones), outline.frame());
            misses.add(i);
        }
        // the cuts side by side: each reads only its own lists
        java.util.function.IntConsumer split = i -> {
            @SuppressWarnings("unchecked") var green = (List<Vec3[]>) greens[i];
            @SuppressWarnings("unchecked") var pieces = (List<List<Vec3[]>>) cuts[i];
            memos[i] = new Memo(outlines.get(i).edges(), pieces, split(outlines.get(i).edges(), green).orange());
        };
        if (misses.size() < 2) misses.forEach(split::accept);
        else misses.parallelStream().forEach(split::accept);
        for (int i : misses) MEMO.put(entities.get(i), memos[i]);
        MobBenchmark.debug(MobBenchmark.ZONES, cut);
        long drawn = MobBenchMetrics.start();
        frames++;
        Lines.TYPE.setupRenderState();
        try {
            var shader = com.mojang.blaze3d.systems.RenderSystem.getShader();
            if (shader != null) {
                // after the type's setup: its view offset is in the model-view matrix
                var view = new org.joml.Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix());
                shader.setDefaultUniforms(com.mojang.blaze3d.vertex.VertexFormat.Mode.LINES, view,
                        com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix(), net.minecraft.client.Minecraft.getInstance().getWindow());
                shader.apply();
                var at = new org.joml.Matrix4f();
                for (int i = 0; i < n; i++) {
                    var memo = memos[i];
                    if (memo.buffer == null && !memo.empty) upload(memo, memo.orange, 1F, .8F, 1F / 3);
                    var frame = outlines.get(i).frame();
                    Vec3 u = frame.u(), v = frame.v(), w = frame.w();
                    draw(shader, view, at.set((float) u.x, (float) u.y, (float) u.z, 0, (float) v.x, (float) v.y, (float) v.z, 0,
                            (float) w.x, (float) w.y, (float) w.z, 0, (float) (frame.origin().x - cam.x), (float) (frame.origin().y - cam.y),
                            (float) (frame.origin().z - cam.z), 1), memo);
                    @SuppressWarnings("unchecked") var boxes = (List<ModelBox>) cubes[i];
                    for (int c = 0; c < boxes.size(); c++) {
                        var box = boxes.get(c);
                        var piece = memo.pieces.get(c);
                        var lines = PIECE_LINES.computeIfAbsent(piece, k -> new Drawn());
                        if (lines.buffer == null && !lines.empty) upload(lines, piece, .15F, 1F, .15F);
                        draw(shader, view, at.set((float) box.ux(), (float) box.uy(), (float) box.uz(), 0, (float) box.vx(), (float) box.vy(),
                                (float) box.vz(), 0, (float) box.wx(), (float) box.wy(), (float) box.wz(), 0, (float) (box.x() - cam.x),
                                (float) (box.y() - cam.y), (float) (box.z() - cam.z), 1), lines);
                    }
                }
                shader.clear();
                com.mojang.blaze3d.vertex.VertexBuffer.unbind();
            }
        } finally {
            Lines.TYPE.clearRenderState();
        }
        sweep();
        MobBenchmark.debug(MobBenchmark.LINES, drawn);
        if (MobBenchMetrics.ENABLED) MobBenchmark.debugTargets += n;
        if (OutlineDrift.ENABLED)
            for (int i = 0; i < n; i++) {
                @SuppressWarnings("unchecked") var headZones = (List<HeadZone>) zones[i];
                OutlineDrift.drawn(entities.get(i), all.get(i), new Colored(world(memos[i].orange, outlines.get(i).frame()), green(headZones)));
            }
    }

    // one buffer under view times at, the shader set up for the frame
    private static void draw(net.minecraft.client.renderer.ShaderInstance shader, org.joml.Matrix4f view, org.joml.Matrix4f at, Drawn lines) {
        lines.used = frames;
        if (lines.buffer == null) return;
        if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(new org.joml.Matrix4f(view).mul(at));
            shader.MODEL_VIEW_MATRIX.upload();
        }
        lines.buffer.bind();
        lines.buffer.draw();
    }

    // the lines into a buffer, in their own coordinates (the normal too: the lines shader takes it under the same
    // model-view matrix as the position)
    private static void upload(Drawn lines, List<Vec3[]> strips, float r, float g, float b) {
        var format = com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR_NORMAL;
        int segments = 0;
        for (var strip : strips) segments += strip.length / 2;
        if (segments == 0) { lines.empty = true; return; }
        try (var bytes = new com.mojang.blaze3d.vertex.ByteBufferBuilder(segments * 2 * format.getVertexSize())) {
            var builder = new com.mojang.blaze3d.vertex.BufferBuilder(bytes, com.mojang.blaze3d.vertex.VertexFormat.Mode.LINES, format);
            for (Vec3[] strip : strips)
                for (int i = 0; i + 1 < strip.length; i += 2) {
                    Vec3 p = strip[i], q = strip[i + 1];
                    double dx = q.x - p.x, dy = q.y - p.y, dz = q.z - p.z, length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (length < 1e-9) continue;
                    float nx = (float) (dx / length), ny = (float) (dy / length), nz = (float) (dz / length);
                    builder.addVertex((float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, 1F).setNormal(nx, ny, nz);
                    builder.addVertex((float) q.x, (float) q.y, (float) q.z).setColor(r, g, b, 1F).setNormal(nx, ny, nz);
                }
            var mesh = builder.build();
            if (mesh == null) { lines.empty = true; return; }
            lines.buffer = new com.mojang.blaze3d.vertex.VertexBuffer(com.mojang.blaze3d.vertex.VertexBuffer.Usage.STATIC);
            lines.buffer.bind();
            lines.buffer.upload(mesh);
            BUFFERED.add(lines);
        }
    }

    private static void sweep() {
        for (int i = BUFFERED.size() - 1; i >= 0; i--) {
            var lines = BUFFERED.get(i);
            if (frames - lines.used <= SWEEP) continue;
            lines.buffer.close();
            lines.buffer = null;
            BUFFERED.set(i, BUFFERED.getLast());
            BUFFERED.removeLast();
        }
        PIECE_LINES.values().removeIf(lines -> lines.buffer == null && frames - lines.used > SWEEP);
    }

    // segments in the world to the frame's coordinates (a turn and a shift: distances stay) and back
    private static List<Vec3[]> local(List<Vec3[]> strips, ModelContours.Frame frame) {
        var out = new ArrayList<Vec3[]>(strips.size());
        for (var strip : strips) {
            var moved = new Vec3[strip.length];
            for (int i = 0; i < strip.length; i++) moved[i] = frame.local(strip[i]);
            out.add(moved);
        }
        return out;
    }

    private static List<Vec3[]> world(List<Vec3[]> strips, ModelContours.Frame frame) {
        var out = new ArrayList<Vec3[]>(strips.size());
        for (var strip : strips) {
            var moved = new Vec3[strip.length];
            for (int i = 0; i < strip.length; i++) moved[i] = frame.world(strip[i]);
            out.add(moved);
        }
        return out;
    }

    // F3+B for an entity Flywheel draws itself: Flywheel cancels its LevelRenderer.renderEntity, and with it the
    // F3+B 1.21.1 draws inside the render. Drawn here as EntityRenderDispatcher.render would, at the lerped position
    // (its renderer's offset taken off again before the hitbox), through its own renderHitbox, so the outline or the plain box come as for any other
    // entity; only while the model hitboxes are on, so DE stays as it is with them off
    public static void skipped(Entity entity, double camX, double camY, double camZ, float partial, PoseStack stack,
            net.minecraft.client.renderer.MultiBufferSource buffers) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        var dispatcher = mc.getEntityRenderDispatcher();
        if (!dispatcher.shouldRenderHitBoxes() || entity.isInvisible() || mc.showOnlyReducedInfo()) return;
        if (!ModelHitClient.enabled() || !acup.gunfeel.compat.Feature.MODEL_HITBOXES.on()) return;
        if (!dev.engine_room.flywheel.api.visualization.VisualizationManager.supportsVisualization(entity.level())
                || !dev.engine_room.flywheel.lib.visualization.VisualizationHelper.skipVanillaRender(entity)) return;
        stack.pushPose();
        stack.translate(net.minecraft.util.Mth.lerp(partial, entity.xOld, entity.getX()) - camX,
                net.minecraft.util.Mth.lerp(partial, entity.yOld, entity.getY()) - camY,
                net.minecraft.util.Mth.lerp(partial, entity.zOld, entity.getZ()) - camZ);
        acup.gunfeel.mixin.mc.EntityRenderDispatcherAccess.gunfeel$renderHitbox(stack,
                buffers.getBuffer(net.minecraft.client.renderer.RenderType.lines()), entity, partial, 1F, 1F, 1F);
        stack.popPose();
    }

    // a multipart entity's part boxes: hidden once the owner's outline is drawn
    public static boolean parts(Entity owner) {
        return ModelHitClient.enabled() && acup.gunfeel.compat.Feature.MODEL_HITBOXES.on() && ModelHits.hasPose(owner);
    }

    private static Vec3 lerped(Entity entity, float partial) {
        return new Vec3(net.minecraft.util.Mth.lerp(partial, entity.xOld, entity.getX()),
                net.minecraft.util.Mth.lerp(partial, entity.yOld, entity.getY()), net.minecraft.util.Mth.lerp(partial, entity.zOld, entity.getZ()));
    }

    // the head zones' outline, as the server takes a point for head (HeadZone.contains): each cube of the zone (its
    // pieces, else its box), its edges kept where the planes keep them, and where a plane cuts it the cut across its
    // faces (26.2's HeadDebug through HeadLabelCuts). As 26.2's HeadDebug, a cube's cut is kept in the cube's own
    // coordinates (corner 0, edges 1) by its mesh and its planes there: a walking mob's head moves, its cut stays
    static List<Vec3[]> green(List<HeadZone> zones) {
        var out = new ArrayList<Vec3[]>();
        for (var zone : zones)
            for (var box : zone.pieces().isEmpty() ? List.of(zone.box()) : zone.pieces())
                for (var segment : piece(box, zone.planes()))
                    out.add(new Vec3[] {ModelContours.point(box, segment[0].x, segment[0].y, segment[0].z),
                            ModelContours.point(box, segment[1].x, segment[1].y, segment[1].z)});
        return out;
    }

    private record Piece(long mesh, List<HeadZone.Plane> planes) {}
    private static final java.util.LinkedHashMap<Piece, List<Vec3[]>> PIECES = new java.util.LinkedHashMap<>(32, .75F, true);

    // the cut of one cube in its own coordinates, the planes there with a unit normal, rounded to 1e-6
    private static List<Vec3[]> piece(ModelBox box, List<HeadZone.Plane> planes) {
        var local = new ArrayList<HeadZone.Plane>(planes.size());
        for (var plane : planes) {
            var p = HeadZone.localPlane(box, plane);
            double length = Math.sqrt(p.x() * p.x() + p.y() * p.y() + p.z() * p.z());
            if (length < 1e-12) length = 1;
            local.add(new HeadZone.Plane(unit(p.x() / length), unit(p.y() / length), unit(p.z() / length), unit(p.d() / length)));
        }
        var key = new Piece(box.mesh(), List.copyOf(local));
        var cut = PIECES.get(key);
        if (cut == null) {
            cut = List.copyOf(cut(new ModelBox("head", 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, box.mesh()), key.planes()));
            PIECES.put(key, cut);
            while (PIECES.size() > 256) PIECES.remove(PIECES.keySet().iterator().next());
        }
        return cut;
    }

    private static double unit(double value) { return Math.round(value * 1e6) / 1e6; }

    // one cube's edges the planes keep and the planes' cut across its faces, where the cube stands
    private static List<Vec3[]> cut(ModelBox box, List<HeadZone.Plane> planes) {
        var out = new ArrayList<Vec3[]>();
        for (var strip : ModelContours.edges(List.of(box)))
            for (int i = 0; i + 1 < strip.length; i += 2) keep(out, strip[i], strip[i + 1], planes);
        if (planes.isEmpty()) return out;
        float[] data = HeadSmoke.triangles(box);
        for (int at = 0; at + 8 < data.length; at += 9) {
            Vec3[] corner = {ModelContours.point(box, data[at], data[at + 1], data[at + 2]),
                    ModelContours.point(box, data[at + 3], data[at + 4], data[at + 5]), ModelContours.point(box, data[at + 6], data[at + 7], data[at + 8])};
            for (var plane : planes) {
                var crossing = new ArrayList<Vec3>();
                for (int i = 0; i < 3; i++) {
                    Vec3 a = corner[i], b = corner[(i + 1) % 3];
                    double da = distance(plane, a), db = distance(plane, b);
                    if (Math.abs(da) < 1e-8 && crossing.stream().noneMatch(p -> p.distanceToSqr(a) < 1e-16)) crossing.add(a);
                    if (da * db < 0) crossing.add(a.add(b.subtract(a).scale(da / (da - db))));
                }
                if (crossing.size() == 2) keep(out, crossing.get(0), crossing.get(1), planes);
            }
        }
        return out;
    }

    private static double distance(HeadZone.Plane plane, Vec3 p) { return plane.x() * p.x + plane.y() * p.y + plane.z() * p.z + plane.d(); }

    // the part of a to b every plane keeps (HeadZone.Plane.accepts)
    private static void keep(List<Vec3[]> out, Vec3 a, Vec3 b, List<HeadZone.Plane> planes) {
        for (var plane : planes) {
            double da = distance(plane, a), db = distance(plane, b);
            if (da < -1e-8 && db < -1e-8) return;
            if (da < 0 && db >= 0) a = a.add(b.subtract(a).scale(da / (da - db)));
            else if (db < 0 && da >= 0) b = a.add(b.subtract(a).scale(da / (da - db)));
        }
        if (a.distanceToSqr(b) > 1e-16) out.add(new Vec3[] {a, b});
    }

    // how far off a line a point still lies on it
    static final double ON = 1e-5;
    // how far off a green line an orange one is still under it: ModelContours draws a pose with the outline it cached
    // for one within 1/32768 per number, the whole model's and a head cube's apart, so the two can stand apart by more
    // than ON; 1e-3 block is far below a pixel
    static final double UNDER = 1e-3;

    // the outline in two colors: the green as given, the orange the model's outline less what lies on a green segment
    static Colored split(List<Vec3[]> outline, List<Vec3[]> green) {
        if (green.isEmpty()) return new Colored(outline, List.of());
        var orange = new ArrayList<Vec3[]>();
        var index = new Segments(green);
        var near = new ArrayList<Segment>();
        var covered = new ArrayList<double[]>();
        for (var strip : outline)
            for (int i = 0; i + 1 < strip.length; i += 2) {
                Vec3 a = strip[i], b = strip[i + 1];
                double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z, length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (length < 1e-9) continue;
                // nowhere near the green (its bounds widened by UNDER): all of it is orange
                if (!index.reaches(a, b)) { orange.add(new Vec3[] {a, b}); continue; }
                double ux = dx / length, uy = dy / length, uz = dz / length;
                covered.clear();
                index.near(a, b, near);
                for (var g : near) {
                    // both ends by the green segment: all of it is (a mesh's edge a millionth of a block long, its
                    // direction too rough for the line test below)
                    if (distance(a, g) < UNDER && distance(b, g) < UNDER) { covered.add(new double[] {0, length}); continue; }
                    double ta = along(a, ux, uy, uz, g.ax, g.ay, g.az), tb = along(a, ux, uy, uz, g.bx, g.by, g.bz);
                    if (!Double.isFinite(ta) || !Double.isFinite(tb)) continue;
                    double from = Math.max(0, Math.min(ta, tb)), to = Math.min(length, Math.max(ta, tb));
                    if (to - from > 1e-9) covered.add(new double[] {from, to});
                }
                if (covered.isEmpty()) { orange.add(new Vec3[] {a, b}); continue; }
                covered.sort((x, y) -> Double.compare(x[0], y[0]));
                // what the green leaves of an edge it lies on: a sliver shorter than UNDER between or past the green
                // segments (an edge a hair off the green's) is no line on the screen
                double at = 0;
                for (var c : covered) {
                    if (c[0] > at + UNDER) orange.add(new Vec3[] {point(a, ux, uy, uz, at), point(a, ux, uy, uz, c[0])});
                    at = Math.max(at, c[1]);
                }
                if (length > at + UNDER) orange.add(new Vec3[] {point(a, ux, uy, uz, at), b});
            }
        return new Colored(orange, List.copyOf(green));
    }

    private static Vec3 point(Vec3 a, double ux, double uy, double uz, double t) { return new Vec3(a.x + ux * t, a.y + uy * t, a.z + uz * t); }

    // p to the segment g
    private static double distance(Vec3 p, Segment g) {
        double dx = g.bx - g.ax, dy = g.by - g.ay, dz = g.bz - g.az, length = dx * dx + dy * dy + dz * dz;
        double t = length < 1e-18 ? 0 : Math.max(0, Math.min(1, ((p.x - g.ax) * dx + (p.y - g.ay) * dy + (p.z - g.az) * dz) / length));
        double x = g.ax + dx * t - p.x, y = g.ay + dy * t - p.y, z = g.az + dz * t - p.z;
        return Math.sqrt(x * x + y * y + z * z);
    }

    // a green segment of the index; seen: the query that last gave it, so a query gives each once
    static final class Segment {
        final double ax, ay, az, bx, by, bz;
        final Vec3[] pair;
        int seen;
        Segment(Vec3 a, Vec3 b) { ax = a.x; ay = a.y; az = a.z; bx = b.x; by = b.y; bz = b.z; pair = new Vec3[] {a, b}; }
    }

    // segments (a strip's pairs) by the cells of a grid, for the ones near a segment: a mesh mob's outline and its
    // head's green are thousands of segments each, and each against all is a frame's worth of time
    static final class Segments {
        // the grid's cell: twice the segments' mean length, between 4 UNDER and a quarter block, so a mesh head's
        // hundreds of short edges do not share a few cells. A segment is taken in pieces a cell long, each by the cells
        // its bounds (widened by UNDER) cross, so a long diagonal one is in the cells along it, not in all its bounds'
        private final double cell;
        private final java.util.Map<Long, List<Segment>> cells = new java.util.HashMap<>();
        private final List<Segment> all = new ArrayList<>();
        // the bounds of all of them, widened by UNDER
        private double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY,
                maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        private int query;

        Segments(List<Vec3[]> strips) {
            double sum = 0;
            int count = 0;
            for (var strip : strips)
                for (int i = 0; i + 1 < strip.length; i += 2) { sum += strip[i].distanceTo(strip[i + 1]); count++; }
            cell = Math.max(4 * UNDER, Math.min(.25, count == 0 ? .25 : 2 * sum / count));
            for (var strip : strips)
                for (int i = 0; i + 1 < strip.length; i += 2) {
                    Vec3 a = strip[i], b = strip[i + 1];
                    var segment = new Segment(a, b);
                    all.add(segment);
                    minX = Math.min(minX, Math.min(a.x, b.x) - UNDER); maxX = Math.max(maxX, Math.max(a.x, b.x) + UNDER);
                    minY = Math.min(minY, Math.min(a.y, b.y) - UNDER); maxY = Math.max(maxY, Math.max(a.y, b.y) + UNDER);
                    minZ = Math.min(minZ, Math.min(a.z, b.z) - UNDER); maxZ = Math.max(maxZ, Math.max(a.z, b.z) + UNDER);
                    int pieces = pieces(a, b);
                    for (int k = 0; k < pieces; k++) {
                        double t0 = (double) k / pieces, t1 = (double) (k + 1) / pieces;
                        double ax = a.x + (b.x - a.x) * t0, ay = a.y + (b.y - a.y) * t0, az = a.z + (b.z - a.z) * t0;
                        double bx = a.x + (b.x - a.x) * t1, by = a.y + (b.y - a.y) * t1, bz = a.z + (b.z - a.z) * t1;
                        int x0 = cell(Math.min(ax, bx) - UNDER), x1 = cell(Math.max(ax, bx) + UNDER);
                        int y0 = cell(Math.min(ay, by) - UNDER), y1 = cell(Math.max(ay, by) + UNDER);
                        int z0 = cell(Math.min(az, bz) - UNDER), z1 = cell(Math.max(az, bz) + UNDER);
                        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
                            var list = cells.computeIfAbsent(key(x, y, z), key -> new ArrayList<>());
                            if (list.isEmpty() || list.getLast() != segment) list.add(segment);
                        }
                    }
                }
        }

        // a to b's bounds (widened by UNDER) meet the segments' bounds: one of them may lie within UNDER of it
        boolean reaches(Vec3 a, Vec3 b) {
            return Math.max(a.x, b.x) + UNDER >= minX && Math.min(a.x, b.x) - UNDER <= maxX
                    && Math.max(a.y, b.y) + UNDER >= minY && Math.min(a.y, b.y) - UNDER <= maxY
                    && Math.max(a.z, b.z) + UNDER >= minZ && Math.min(a.z, b.z) - UNDER <= maxZ;
        }

        // the segments in the cells along a to b (widened by UNDER), each once, into out
        void near(Vec3 a, Vec3 b, List<Segment> out) {
            out.clear();
            int stamp = ++query;
            int pieces = pieces(a, b);
            for (int k = 0; k < pieces; k++) {
                double t0 = (double) k / pieces, t1 = (double) (k + 1) / pieces;
                double ax = a.x + (b.x - a.x) * t0, ay = a.y + (b.y - a.y) * t0, az = a.z + (b.z - a.z) * t0;
                double bx = a.x + (b.x - a.x) * t1, by = a.y + (b.y - a.y) * t1, bz = a.z + (b.z - a.z) * t1;
                int x0 = cell(Math.min(ax, bx) - UNDER), x1 = cell(Math.max(ax, bx) + UNDER);
                int y0 = cell(Math.min(ay, by) - UNDER), y1 = cell(Math.max(ay, by) + UNDER);
                int z0 = cell(Math.min(az, bz) - UNDER), z1 = cell(Math.max(az, bz) + UNDER);
                for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) {
                    var list = cells.get(key(x, y, z));
                    if (list != null) for (var segment : list) if (segment.seen != stamp) { segment.seen = stamp; out.add(segment); }
                }
            }
        }

        // the stand's (OutlineDrift): the same, as pairs
        List<Vec3[]> near(Vec3 a, Vec3 b) {
            var found = new ArrayList<Segment>();
            near(a, b, found);
            var out = new ArrayList<Vec3[]>(found.size());
            for (var segment : found) out.add(segment.pair);
            return out;
        }

        // a cell long each, a million at most (a segment off in the far lands)
        private int pieces(Vec3 a, Vec3 b) { return (int) Math.max(1, Math.min(1e6, Math.ceil(a.distanceTo(b) / cell))); }

        private static long key(int x, int y, int z) { return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF); }

        private int cell(double v) { return (int) Math.floor(v / cell); }
    }

    // p's distance along the line from a (unit u), if p lies on it
    private static double along(Vec3 a, double ux, double uy, double uz, double px, double py, double pz) {
        double rx = px - a.x, ry = py - a.y, rz = pz - a.z;
        double t = rx * ux + ry * uy + rz * uz;
        double x = rx - ux * t, y = ry - uy * t, z = rz - uz * t;
        return x * x + y * y + z * z < UNDER * UNDER ? t : Double.NaN;
    }

    // segments in the frame's coordinates, drawn where the frame stands, relative to the camera
    private static void lines(PoseStack.Pose last, VertexConsumer out, List<Vec3[]> strips, ModelContours.Frame frame, Vec3 cam,
            float r, float g, float b) {
        Vec3 u = frame.u(), v = frame.v(), w = frame.w();
        double ox = frame.origin().x - cam.x, oy = frame.origin().y - cam.y, oz = frame.origin().z - cam.z;
        for (Vec3[] strip : strips)
            for (int i = 0; i + 1 < strip.length; i += 2) {
                Vec3 p = strip[i], q = strip[i + 1];
                double px = ox + u.x * p.x + v.x * p.y + w.x * p.z, py = oy + u.y * p.x + v.y * p.y + w.y * p.z, pz = oz + u.z * p.x + v.z * p.y + w.z * p.z;
                double qx = ox + u.x * q.x + v.x * q.y + w.x * q.z, qy = oy + u.y * q.x + v.y * q.y + w.y * q.z, qz = oz + u.z * q.x + v.z * q.y + w.z * q.z;
                double dx = qx - px, dy = qy - py, dz = qz - pz, length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (length < 1e-9) continue;
                float nx = (float) (dx / length), ny = (float) (dy / length), nz = (float) (dz / length);
                out.addVertex(last, (float) px, (float) py, (float) pz).setColor(r, g, b, 1F).setNormal(last, nx, ny, nz);
                out.addVertex(last, (float) qx, (float) qy, (float) qz).setColor(r, g, b, 1F).setNormal(last, nx, ny, nz);
            }
    }

    // the legacy path's: segments in the world, relative to at
    private static void lines(PoseStack pose, VertexConsumer out, List<Vec3[]> strips, Vec3 at, float r, float g, float b) {
        var last = pose.last();
        for (Vec3[] strip : strips)
            for (int i = 0; i + 1 < strip.length; i += 2) {
                Vec3 p = strip[i].subtract(at), q = strip[i + 1].subtract(at), d = q.subtract(p);
                double length = d.length();
                if (length < 1e-9) continue;
                float nx = (float) (d.x / length), ny = (float) (d.y / length), nz = (float) (d.z / length);
                out.addVertex(last, (float) p.x, (float) p.y, (float) p.z).setColor(r, g, b, 1F).setNormal(last, nx, ny, nz);
                out.addVertex(last, (float) q.x, (float) q.y, (float) q.z).setColor(r, g, b, 1F).setNormal(last, nx, ny, nz);
            }
    }
}
