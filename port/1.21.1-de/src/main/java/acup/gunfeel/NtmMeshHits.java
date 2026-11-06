package acup.gunfeel;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.hbm.backport.client.core.SubmitNodeCollector.CustomGeometryRenderer;
import com.hbm.render.loader.GroupObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

// Port copy (notes/ports/de-1.21.1.md, «Копии»). 26.2's NtmMeshHits takes a mob's geometry from what its renderer
// submits (its models, custom geometry, OBJ groups, block parts), keyed to the entity's render state, and publishes
// it once the frame is drawn ("rendered_layers"). On DE 1.21.1 an entity draws everything at once inside
// LevelRenderer.renderEntity: vanilla renderers straight to the buffers, DE's backport ones through
// ImmediateSubmitNodeCollector. So the frame here is that call (begin/end, MixinEntityMeshHits), and what it draws
// is taken as it is drawn:
// - a model a part at a time, at ModelPart.compile (MixinModelPartDraws), with the pose it is drawn at. The part is
//   named by its path from its model's root ("root/head/hat"), as 26.2's traversal names it: the parents and the names
//   ModelPart's constructor gives its children (port/ModelPartNode). Its texture is that of the render type its vertex
//   consumer belongs to (BufferSource.getBuffer, MixinBufferTypes); the renderer's main model has the renderer's own
//   too, for a consumer that wraps another (the glowing outline). A wrapper the world's buffers or ItemRenderer give out
//   for a type (OutlineBufferSource's for a glowing mob, the elytra's foil) is the type's as well. The glint drawn over
//   enchanted armor is not geometry (26.2's GPU path leaves Transparency.GLINT out too).
// - the main model (MixinLivingModelHits, MixinBackportModelHits) and the layers over it as 26.2's prepared() names
//   them: the primary model drawn again in an opaque layer (the golem's cracks) adds only what its own texture leaves
//   transparent; a blended layer's cubes are whole; the sheep's fur ends in ModelWool.SUFFIX, the slime's outer body in
//   ModelBodyGeometry.SHELL_SUFFIX, worn armor in ARMOR_SUFFIX (port/continuum's MixinEquipmentLayerModel: WornGear
//   leaves it at its drawn size).
// - custom geometry (wrap, MixinBackportModelHits) and OBJ groups (group, MixinNtmMeshHits) as in 26.2. A draw that
//   gave neither parts nor groups is taken by its triangles (SubmittedModelGeometry).
// - items drawn on the mob (held ones, a pumpkin on the head) are left out, as 26.2's submitItem is (suspend,
//   MixinItemDraws). 26.2's block models (submitBlockModel: an enderman's block, a mooshroom's mushrooms) are 1.21.1's
//   BlockRenderDispatcher quads and are not taken here: the mob keeps its model without them.
// Render thread only: DE draws its entities there, and what Flywheel's workers compile (a MeshTree's parts, a mesh of
// OBJ groups) is not this frame's. An entity some renderer draws inside the frame's (EntityRenderDispatcher.render
// within the render, MixinEntityRenderDepth) is not the frame's entity either: what it draws is left out.
public final class NtmMeshHits {
    public static Runnable gpuFlush = () -> {};
    private static final class Frame {
        final LivingEntity entity;
        final Vec3 origin, offset;
        final float yaw;
        final LinkedHashSet<ModelBox> boxes = new LinkedHashSet<>();
        final java.util.Set<ModelPart> primaryParts = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        boolean incomplete;
        int nodes;

        Frame(LivingEntity entity, Vec3 origin, Vec3 offset, float yaw) {
            this.entity = entity;
            this.origin = origin;
            this.offset = offset;
            this.yaw = yaw;
        }
    }
    // what is drawn now inside the frame: the main model or a layer over it
    private record Scope(boolean primary, ResourceLocation texture, String suffix, boolean avatar) {}
    private static final Scope NONE = new Scope(false, null, "", false);
    // Models a layer draws over the mob's own: the armor (port/continuum: MixinEquipmentLayerModel; here
    // MixinLivingModelHits' HumanoidArmorLayer). Their parts take a layer's name, ".../armor/draws/0", so WornGear
    // leaves them at their drawn size.
    public static final String ARMOR_SUFFIX = "/armor/draws/0";
    private static Frame frame;
    private static final java.util.ArrayDeque<Scope> SCOPES = new java.util.ArrayDeque<>();
    private static final java.util.ArrayDeque<ResourceLocation> DRAW_TEXTURE = new java.util.ArrayDeque<>();
    private static final java.util.ArrayDeque<SubmittedModelGeometry> DRAWS = new java.util.ArrayDeque<>();
    private static final Map<VertexConsumer, RenderType> TYPES = new IdentityHashMap<>();
    private static int suspended;
    // EntityRenderDispatcher.render calls open inside the frame: 1 is the frame's entity, more an entity it draws
    private static int depth;
    private static ResourceLocation primaryTexture;
    private static final List<Frame> PENDING = new ArrayList<>();
    private record Prepared(LivingEntity entity, float yaw, List<ModelBox> boxes, Vec3 origin) {}
    private static final Map<UUID, Prepared> PREPARED = new java.util.concurrent.ConcurrentHashMap<>();

    private NtmMeshHits() {}

    // LevelRenderer.renderEntity HEAD: the world draws this entity now
    public static void begin(Entity entity, double camX, double camY, double camZ, float partial) {
        frame = null;
        SCOPES.clear(); DRAW_TEXTURE.clear(); DRAWS.clear(); TYPES.clear(); suspended = 0; depth = 0; primaryTexture = null;
        if (!(entity instanceof LivingEntity living) || !ModelHitClient.enabled()
                || !acup.gunfeel.compat.Feature.MODEL_HITBOXES.on() || !ModelHitClient.eligible(living)) return;
        Vec3 origin = new Vec3(Mth.lerp(partial, entity.xOld, entity.getX()), Mth.lerp(partial, entity.yOld, entity.getY()),
                Mth.lerp(partial, entity.zOld, entity.getZ()));
        frame = new Frame(living, origin, new Vec3(camX, camY, camZ).subtract(origin),
                Mth.rotLerp(partial, living.yBodyRotO, living.yBodyRot));
    }

    // LevelRenderer.renderEntity RETURN: published with the frame's others once the world is drawn (flush)
    public static void end() {
        Frame done = frame;
        frame = null;
        SCOPES.clear(); DRAW_TEXTURE.clear(); DRAWS.clear(); TYPES.clear(); suspended = 0; depth = 0; primaryTexture = null;
        if (done != null && (done.incomplete || !done.boxes.isEmpty())) PENDING.add(done);
    }

    public static boolean active() { return frame != null; }

    // what is drawn now is the frame's own: the frame's entity, on the render thread
    private static boolean own() {
        return depth <= 1 && com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread();
    }

    // EntityRenderDispatcher.render HEAD (true) and RETURN (false)
    public static void dispatch(boolean on) {
        if (frame != null) depth = on ? depth + 1 : Math.max(0, depth - 1);
    }

    private static final java.util.Set<RenderType> GLINTS = java.util.Set.of(RenderType.armorEntityGlint(),
            RenderType.glintTranslucent(), RenderType.glint(), RenderType.entityGlint(), RenderType.entityGlintDirect());

    // BufferSource.getBuffer: which render type a consumer draws, for the texture of what is drawn to it
    public static void buffer(RenderType type, VertexConsumer consumer) {
        if (frame != null && type != null && consumer != null && com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread())
            TYPES.put(consumer, type);
    }

    // around the renderer's main model draw (26.2: the render state's primary model)
    // avatar: the model is a player's (26.2: PlayerModel), for 3D Skin Layers' meshes on its parts
    public static void primary(ResourceLocation texture, boolean avatar, boolean on) {
        if (frame == null || !own()) return;
        if (on) { primaryTexture = texture; SCOPES.push(new Scope(true, texture, "", avatar)); } else if (!SCOPES.isEmpty()) SCOPES.pop();
    }

    // around a layer the renderer draws over its model; suffix: what its parts are named with (ARMOR_SUFFIX, the
    // wool's, the shell's) or ""
    public static void layer(String suffix, boolean on) {
        if (frame == null || !own()) return;
        if (on) SCOPES.push(new Scope(false, null, suffix, false)); else if (!SCOPES.isEmpty()) SCOPES.pop();
    }

    // around an item drawn inside the frame (held, on the head): not the mob's body
    public static void suspend(boolean on) {
        if (frame != null && own()) suspended = Math.max(0, suspended + (on ? 1 : -1));
    }

    // ModelPart.compile HEAD: a visible part draws its cubes at pose
    public static void part(ModelPart part, PoseStack.Pose pose, VertexConsumer consumer) {
        Frame f = frame;
        if (f == null || suspended > 0 || f.incomplete || f.boxes.size() > ModelPosePayload.MAX_BOXES || !own()) return;
        RenderType type = TYPES.get(consumer);
        if (type != null && GLINTS.contains(type)) return;
        if (++f.nodes > 2048) { failed(f, "nodes=" + f.nodes); return; }
        long started = System.nanoTime();
        Scope scope = SCOPES.isEmpty() ? NONE : SCOPES.peek();
        ResourceLocation texture = type != null ? ModelTextureMask.texture(type) : null;
        if (texture == null) texture = scope.primary() ? scope.texture() : DRAW_TEXTURE.peek();
        boolean blended = type != null && acup.gunfeel.port.Mc.blending(type);
        if (scope.primary()) f.primaryParts.add(part);
        // 26.2: a layer over the main model with an opaque type adds what is transparent in the model's texture
        ResourceLocation base = !scope.primary() && !blended && f.primaryParts.contains(part) ? primaryTexture : null;
        var parts = ModelTextureMask.parts(texture, base);
        boolean avatar = scope.avatar();
        String name = ModelPoseCapture.label(acup.gunfeel.port.ModelPartNode.path(part));
        var out = new ArrayList<ModelBox>();
        try {
            var stack = new PoseStack();
            acup.gunfeel.port.Mc.setPose(stack.last(), pose);
            var offset = f.offset;
            if (!(avatar && SkinLayerGeometry.append(part, name, stack, out, offset.x, offset.y, offset.z)))
                for (ModelPart.Cube cube : parts.cubes(part)) {
                    var raw = ModelTextureMask.geometry(cube, null);
                    var b = raw.bounds();
                    var geometry = !avatar && blended && b[3] > b[0] && b[4] > b[1] && b[5] > b[2] ? raw : parts.geometry(cube);
                    geometry.append(out, parts.partName(name, cube), pose.pose(), offset.x, offset.y, offset.z);
                }
        } catch (RuntimeException ex) {
            failed(f, ex.toString());
            return;
        }
        String suffix = scope.suffix();
        for (var box : out) f.boxes.add(suffix.isEmpty() ? box : new ModelBox(box.part() + suffix, box.x(), box.y(), box.z(),
                box.ux(), box.uy(), box.uz(), box.vx(), box.vy(), box.vz(), box.wx(), box.wy(), box.wz(), box.mesh()));
        // the draw's own triangles are this part's: not taken again
        if (!DRAWS.isEmpty()) DRAWS.peek().ignore();
        ModelHitClient.captureCost(System.nanoTime() - started);
    }

    private static void failed(Frame f, String why) {
        f.incomplete = true;
        ModelHitLog.event("client", "prepared_geometry_incomplete", f.entity, () -> why);
    }

    // NTM's beams (BeamPronter: the tesla and taint crabs' arcs, the UFO's and the radiation blaze's rays) are light, not
    // the mob, as in 26.2 (X473). Taken in, a zapping crab's arcs ran its pose past MAX_BOXES, and the whole pose was
    // thrown away.
    static boolean beam(RenderType type) {
        return type == com.hbm.client.render.BeamRenderTypes.ADDITIVE || type == com.hbm.client.render.BeamRenderTypes.ADDITIVE_NO_FOG
                || type == com.hbm.client.render.BeamRenderTypes.LINE;
    }

    // ImmediateSubmitNodeCollector.submitCustomGeometry (26.2: SubmitNodeCollection's): drawn at once, inside the frame
    public static CustomGeometryRenderer wrap(CustomGeometryRenderer renderer, RenderType type) {
        Frame f = frame;
        if (f == null || suspended > 0 || !own() || beam(type)) return renderer;
        return (pose, buffer) -> {
            DRAW_TEXTURE.push(java.util.Objects.requireNonNullElse(ModelTextureMask.texture(type), MISSING));
            var captured = new SubmittedModelGeometry(buffer);
            DRAWS.push(captured);
            int before = f.boxes.size();
            TYPES.put(captured, type);
            try {
                renderer.render(pose, captured);
                if (f.boxes.size() == before && frame == f) {
                    try {
                        var offset = f.offset;
                        ResourceLocation texture = ModelTextureMask.texture(type);
                        f.boxes.addAll(captured.boxes(acup.gunfeel.port.Mc.topology(type), texture,
                                acup.gunfeel.port.Mc.blending(type), offset.x, offset.y, offset.z));
                    } catch (RuntimeException ex) {
                        f.incomplete = true;
                        ModelHitLog.event("client", "submitted_geometry_failed", f.entity, () -> ex.toString());
                    }
                }
            } finally {
                DRAWS.pop();
                DRAW_TEXTURE.pop();
            }
        };
    }
    // a custom draw with no texture: ArrayDeque takes no null
    private static final ResourceLocation MISSING = ResourceLocation.fromNamespaceAndPath("ntmgunfeel", "none");

    public static void group(GroupObject group, PoseStack.Pose pose, VertexConsumer buffer, float us, float vs, float uo, float vo) {
        Frame f = frame;
        if (f == null || suspended > 0 || f.boxes.size() > ModelPosePayload.MAX_BOXES || !own()) return;
        if (buffer instanceof SubmittedModelGeometry captured) captured.ignore();
        else if (!DRAWS.isEmpty()) DRAWS.peek().ignore();
        long started = System.nanoTime();
        var offset = f.offset;
        var boxes = new ArrayList<ModelBox>();
        ResourceLocation texture = DRAW_TEXTURE.peek();
        if (texture == MISSING) texture = null;
        try {
            ModelMeshCapture.geometry(group, texture, us, vs, uo, vo).append(boxes, group.name, pose.pose(), offset.x, offset.y, offset.z);
            f.boxes.addAll(boxes);
        } catch (RuntimeException ex) {
            f.incomplete = true;
            ModelHitLog.event("client", "group_capture_failed", f.entity, () -> ex.toString());
        }
        ModelHitClient.captureCost(System.nanoTime() - started);
    }

    public static void flush() {
        for (Frame f : PENDING) if (ModelHitClient.eligible(f.entity) && !f.incomplete && !f.boxes.isEmpty()) {
            boolean published = ModelHitClient.publishAt(f.entity, f.yaw, List.copyOf(f.boxes), "rendered_layers", f.origin);
            if (published && MobSmoke.enabled()) ModelHitClient.applied(f.entity);
        }
        PENDING.clear();
        for (var entry : PREPARED.entrySet()) if (PREPARED.remove(entry.getKey(), entry.getValue())) {
            Prepared pose = entry.getValue();
            if (ModelHitClient.eligible(pose.entity()))
                ModelHitClient.publishAt(pose.entity(), pose.yaw(), pose.boxes(), "ntm_flywheel_mesh", pose.origin());
        }
        gpuFlush.run();
    }

    public static void offer(LivingEntity entity, float yaw, List<ModelBox> boxes, org.joml.Vector3f origin) {
        if (PREPARED.size() >= 512 && !PREPARED.containsKey(entity.getUUID())) return;
        PREPARED.put(entity.getUUID(), new Prepared(entity, yaw, List.copyOf(boxes), new Vec3(origin.x, origin.y, origin.z)));
    }

    public static void clear() {
        frame = null; depth = 0;
        SCOPES.clear(); DRAW_TEXTURE.clear(); DRAWS.clear(); TYPES.clear(); PENDING.clear(); PREPARED.clear();
        SubmittedModelGeometry.clear();
    }

    static Minecraft mc() { return Minecraft.getInstance(); }
}
