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
    public static void beginFrame() { TARGETS.clear(); level = false; }

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
            buffers.endBatch(Lines.TYPE);
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

    private static void outlines(List<Target> targets, VertexConsumer out, Vec3 cam) {
        var pose = new PoseStack();
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
            var colored = split(ModelContours.edges(boxes), green(ModelHits.headZones(entity)));
            lines(pose, out, colored.orange(), cam, 1F, .8F, 1F / 3);
            lines(pose, out, colored.green(), cam, .15F, 1F, .15F);
            OutlineDrift.drawn(entity, boxes, colored);
        }
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
    // faces (26.2's HeadDebug through HeadLabelCuts)
    static List<Vec3[]> green(List<HeadZone> zones) {
        var out = new ArrayList<Vec3[]>();
        for (var zone : zones)
            for (var box : zone.pieces().isEmpty() ? List.of(zone.box()) : zone.pieces()) {
                for (var strip : ModelContours.edges(List.of(box)))
                    for (int i = 0; i + 1 < strip.length; i += 2) keep(out, strip[i], strip[i + 1], zone.planes());
                if (zone.planes().isEmpty()) continue;
                float[] data = HeadSmoke.triangles(box);
                for (int at = 0; at + 8 < data.length; at += 9) {
                    Vec3[] corner = {ModelContours.point(box, data[at], data[at + 1], data[at + 2]),
                            ModelContours.point(box, data[at + 3], data[at + 4], data[at + 5]), ModelContours.point(box, data[at + 6], data[at + 7], data[at + 8])};
                    for (var plane : zone.planes()) {
                        var crossing = new ArrayList<Vec3>();
                        for (int i = 0; i < 3; i++) {
                            Vec3 a = corner[i], b = corner[(i + 1) % 3];
                            double da = distance(plane, a), db = distance(plane, b);
                            if (Math.abs(da) < 1e-8 && crossing.stream().noneMatch(p -> p.distanceToSqr(a) < 1e-16)) crossing.add(a);
                            if (da * db < 0) crossing.add(a.add(b.subtract(a).scale(da / (da - db))));
                        }
                        if (crossing.size() == 2) keep(out, crossing.get(0), crossing.get(1), zone.planes());
                    }
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
        for (var strip : outline)
            for (int i = 0; i + 1 < strip.length; i += 2) {
                Vec3 a = strip[i], b = strip[i + 1], d = b.subtract(a);
                double length = d.length();
                if (length < 1e-9) continue;
                Vec3 unit = d.scale(1 / length);
                var covered = new ArrayList<double[]>();
                for (var g : index.near(a, b)) {
                    // both ends by the green segment: all of it is (a mesh's edge a millionth of a block long, its
                    // direction too rough for the line test below)
                    if (distance(a, g) < UNDER && distance(b, g) < UNDER) { covered.add(new double[] {0, length}); continue; }
                    double ta = along(a, unit, g[0]), tb = along(a, unit, g[1]);
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
                    if (c[0] > at + UNDER) orange.add(new Vec3[] {a.add(unit.scale(at)), a.add(unit.scale(c[0]))});
                    at = Math.max(at, c[1]);
                }
                if (length > at + UNDER) orange.add(new Vec3[] {a.add(unit.scale(at)), b});
            }
        return new Colored(orange, List.copyOf(green));
    }

    // p to the segment g
    private static double distance(Vec3 p, Vec3[] g) {
        Vec3 d = g[1].subtract(g[0]);
        double length = d.lengthSqr();
        double t = length < 1e-18 ? 0 : Math.max(0, Math.min(1, p.subtract(g[0]).dot(d) / length));
        return g[0].add(d.scale(t)).distanceTo(p);
    }

    // segments (a strip's pairs) by the cells of a grid their bounds cross, for the ones near a segment: a mesh mob's
    // outline and its head's green are thousands of segments each, and each against all is a frame's worth of time
    static final class Segments {
        private static final double CELL = .25;
        // a segment whose bounds cross more cells (a long diagonal one of a big mob) is taken as near every one
        private static final long MAX_CELLS = 512;
        private final java.util.Map<Long, List<Vec3[]>> cells = new java.util.HashMap<>();
        private final List<Vec3[]> wide = new ArrayList<>();

        Segments(List<Vec3[]> strips) {
            for (var strip : strips)
                for (int i = 0; i + 1 < strip.length; i += 2) {
                    var segment = new Vec3[] {strip[i], strip[i + 1]};
                    if (count(strip[i], strip[i + 1]) > MAX_CELLS) wide.add(segment);
                    else forCells(strip[i], strip[i + 1], key -> cells.computeIfAbsent(key, k -> new ArrayList<>()).add(segment));
                }
        }

        // the segments in the cells a to b's bounds (widened by UNDER) cross, each once; for long a to b all of them
        List<Vec3[]> near(Vec3 a, Vec3 b) {
            if (count(a, b) > MAX_CELLS) {
                var all = new ArrayList<Vec3[]>(wide);
                var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Vec3[], Boolean>());
                for (var list : cells.values()) for (var segment : list) if (seen.add(segment)) all.add(segment);
                return all;
            }
            var out = new ArrayList<Vec3[]>(wide);
            var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Vec3[], Boolean>());
            forCells(a, b, key -> {
                var list = cells.get(key);
                if (list != null) for (var segment : list) if (seen.add(segment)) out.add(segment);
            });
            return out;
        }

        private static long count(Vec3 a, Vec3 b) {
            long x = cell(Math.max(a.x, b.x) + UNDER) - cell(Math.min(a.x, b.x) - UNDER) + 1L;
            long y = cell(Math.max(a.y, b.y) + UNDER) - cell(Math.min(a.y, b.y) - UNDER) + 1L;
            long z = cell(Math.max(a.z, b.z) + UNDER) - cell(Math.min(a.z, b.z) - UNDER) + 1L;
            return x * y * z;
        }

        private static void forCells(Vec3 a, Vec3 b, java.util.function.LongConsumer action) {
            int x0 = cell(Math.min(a.x, b.x) - UNDER), x1 = cell(Math.max(a.x, b.x) + UNDER);
            int y0 = cell(Math.min(a.y, b.y) - UNDER), y1 = cell(Math.max(a.y, b.y) + UNDER);
            int z0 = cell(Math.min(a.z, b.z) - UNDER), z1 = cell(Math.max(a.z, b.z) + UNDER);
            for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++)
                action.accept(((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF));
        }

        private static int cell(double v) { return (int) Math.floor(v / CELL); }
    }

    // p's distance along the line from a, if p lies on it
    private static double along(Vec3 a, Vec3 unit, Vec3 p) {
        Vec3 r = p.subtract(a);
        double t = r.dot(unit);
        return r.subtract(unit.scale(t)).lengthSqr() < UNDER * UNDER ? t : Double.NaN;
    }

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
