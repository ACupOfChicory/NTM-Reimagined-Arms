package acup.gunfeel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.lwjgl.opengl.GL11;

import acup.gunfeel.phys.Vec3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.world.World;

// The client's side of model hitboxes (26.2: ModelHitClient): takes each nearby living entity's model as it is
// drawn (ModelCapture, from the hooks in mixins/early/MixinRendererLivingEntity and MixinModelRenderer), keeps it in
// the client's ModelHits for F3+B and the client's own rays, and sends it to the server, which tests its bullets
// against it. The publishing and sending are 26.2's publishChecked and sync, rule for rule: the same filters
// (AvatarHitPolicy, PlaneHitPolicy), the same validity radius, the same budget (MAX_UPDATES poses per INTERVAL ticks,
// the longest unsent first) and meshes before the pose that uses them.
// 26.2 also reads poses from Flywheel visuals and NTM's OBJ mobs (VanillatePose, NtmMeshHits); 1.7.10 draws neither,
// and an NTM mob drawn from OBJ groups (glyphids) has no ModelRenderer: it keeps its plain box.
public final class ModelHitClient {
    private record Sync(EntityLivingBase entity, float yaw, List<ModelBox> boxes, String source, long tick, Vec3 origin) {}
    private static final Map<UUID, Sync> SYNC = new HashMap<>();
    private static final Map<UUID, Long> SENT = new HashMap<>();
    private static final Set<Long> MESH_SENT = new HashSet<>();
    private static volatile boolean enabled = true;
    private static World level;
    private static long nextCheck, sendPeriod = Long.MIN_VALUE;
    private static int sendBudget;
    // the entity whose model is drawing now, and whether its main model pass (renderModel) is on
    private static ModelCapture current;
    private static EntityLivingBase target;
    private static boolean inModel;
    private static double renderX, renderY, renderZ;
    private static final FloatBuffer MATRIX = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();

    private ModelHitClient() {}

    public static boolean enabled() { return enabled; }

    // 26.2: frame(); once a frame before the world draws (RenderTickEvent START)
    public static void frame() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != level) { clear(); level = mc.theWorld; }
        if (level == null || mc.thePlayer == null || mc.getNetHandler() == null) return;
        long now = System.currentTimeMillis();
        if (MobBenchMetrics.ENABLED || now >= nextCheck) { enabled = ModelHits.enabled(); nextCheck = now + 1000; }
    }

    // 26.2: finishFrame(); after the world has drawn (RenderWorldLastEvent)
    public static void finishFrame() {
        if (!enabled || level == null) return;
        sync();
    }

    // ---- capture hooks (render thread) ----

    // EntityRenderer.renderWorld HEAD/RETURN (MixinEntityRenderer). A living entity drawn outside the world's pass is
    // drawn somewhere else: the player in the inventory (GuiInventory draws them at the camera with a yaw the mouse
    // gives), a mod's mob in a GUI. Taken, its pose went to ModelHits and to the server as the entity's own. Until the
    // hook has run once (not applied) every draw is taken, as before.
    private static int worldDepth;
    private static boolean worldSeen;

    public static void world(boolean in) {
        worldSeen = true;
        worldDepth = Math.max(0, worldDepth + (in ? 1 : -1));
    }

    // RendererLivingEntity.doRender HEAD: x, y, z are camera-relative, as RenderManager passes them
    public static void begin(EntityLivingBase entity, ModelBase model, double x, double y, double z) {
        start(entity, model, x, y, z, true);
    }

    // needModel: a RendererLivingEntity's main model (none: nothing to take); a plain Render's may draw OBJ groups only
    private static boolean start(EntityLivingBase entity, ModelBase model, double x, double y, double z, boolean needModel) {
        current = null;
        target = null;
        inModel = false;
        inPass = false;
        if (!enabled || !acup.gunfeel.compat.Feature.MODEL_HITBOXES.on() || (needModel && model == null) || !eligible(entity)) return false;
        if (worldSeen && worldDepth == 0) return false;
        target = entity;
        ModelHitClient.model = model;
        renderX = x;
        renderY = y;
        renderZ = z;
        // the capture itself starts at rotateCorpse, where the body's yaw is known
        view = modelview();
        return true;
    }
    private static float[] view;

    // the body yaw rotateCorpse is called with (doRender's f2, the interpolated renderYawOffset or the mount's)
    public static float yaw(float yaw) {
        if (target != null && current == null && view != null)
            current = new ModelCapture(model, view, renderX, renderY, renderZ, yaw);
        return yaw;
    }
    private static ModelBase model;

    // around doRender's call of renderModel: only the main model pass, not armour, overlays or the hurt tint
    public static void inModel(boolean on) {
        inModel = on && current != null;
    }

    public static boolean capturing() {
        return inModel || inPass;
    }

    // around doRender's calls of renderPassModel.render: the passes that add to the body's shape (26.2: the sheep's wool
    // layer, the slime's outer body; the armour a biped wears), named with suffix; null: a pass that adds nothing (the
    // spider's and the enderman's eyes, which are the head again, an enchantment's glint)
    public static void pass(ModelBase passModel, String suffix) {
        if (current == null || passModel == null || suffix == null) { inPass = false; return; }
        current.pass(passModel, suffix);
        inPass = true;
    }

    public static void passEnd() {
        if (current != null) current.pass(null, null);
        inPass = false;
    }
    private static boolean inPass;

    // ModelRenderer.render / renderWithRotation HEAD
    public static void part(ModelRenderer part, float scale, boolean withRotation) {
        if (!(inModel || inPass) || current == null) return;
        try {
            current.part(part, scale, withRotation, modelview(), GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
        } catch (RuntimeException ex) {
            ModelHitLog.event("client", "capture_failed", target, ex::toString);
            current = null;
            inModel = false;
            inPass = false;
        }
    }

    // an OBJ group drawn now (ModelObjParts)
    public static void group(Object group, String name, ModelObjGeometry.Faces faces) {
        if (!(inModel || inPass) || current == null) return;
        try {
            current.group(group, name, faces, modelview());
        } catch (RuntimeException ex) {
            ModelHitLog.event("client", "capture_failed", target, ex::toString);
            current = null;
            inModel = false;
            inPass = false;
        }
    }

    // RenderManager.func_147939_a around render.doRender, for a living entity whose renderer is a plain Render (NTM's
    // hunter chopper, plastic bag, worm, UFO, drone: RenderHunterChopper and the rest draw the model
    // themselves, turned by yRot, a spin or a fixed angle, not by rotateCorpse). The whole doRender is the model's pass:
    // the cubes of the renderer's ModelBase (named from its field) and every OBJ group it draws; a beam drawn with the
    // Tessellator (RenderUFO) is neither. The pose's yaw is the body's, as rotateCorpse's (26.2: the pose carries the
    // client's body), or the drawn yRot for a mob drawn by it (ModelHits.takenYaw), and ModelHits.Turn turns it as the
    // renderer turns the mob. Returns whether it is taking the model:
    // then endRender must follow.
    public static boolean beginRender(net.minecraft.entity.Entity entity, net.minecraft.client.renderer.entity.Render render,
            double x, double y, double z, float partial) {
        if (render instanceof net.minecraft.client.renderer.entity.RendererLivingEntity || !(entity instanceof EntityLivingBase living)) return false;
        ModelBase m = renderModel(render);
        if (!start(living, m, x, y, z, false)) return false;
        current = new ModelCapture(m, view, x, y, z, ModelHits.takenYaw(living, bodyYaw(living, partial), partial));
        inModel = true;
        return true;
    }

    public static void endRender(net.minecraft.entity.Entity entity) {
        if (entity instanceof EntityLivingBase living) end(living, "render");
    }

    // RendererLivingEntity.interpolateRotation(prevRenderYawOffset, renderYawOffset, partial), what doRender hands rotateCorpse
    static float bodyYaw(EntityLivingBase e, float partial) {
        float d = e.renderYawOffset - e.prevRenderYawOffset;
        while (d < -180F) d += 360F;
        while (d >= 180F) d -= 360F;
        return e.prevRenderYawOffset + partial * d;
    }

    // the renderer's model: the first ModelBase one of its fields holds (RenderHunterChopper.chopperModel); null when it
    // draws OBJ groups only
    private static final ClassValue<java.lang.reflect.Field> MODEL_FIELD = new ClassValue<>() {
        @Override protected java.lang.reflect.Field computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && c != net.minecraft.client.renderer.entity.Render.class; c = c.getSuperclass())
                for (java.lang.reflect.Field f : c.getDeclaredFields())
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) && ModelBase.class.isAssignableFrom(f.getType())) {
                        try { f.setAccessible(true); return f; } catch (RuntimeException ex) { return null; }
                    }
            return null;
        }
    };

    private static ModelBase renderModel(net.minecraft.client.renderer.entity.Render render) {
        java.lang.reflect.Field f = MODEL_FIELD.get(render.getClass());
        try { return f == null ? null : (ModelBase) f.get(render); }
        catch (ReflectiveOperationException | RuntimeException ex) { return null; }
    }

    // RendererLivingEntity.doRender RETURN
    public static void end(EntityLivingBase entity) {
        end(entity, "vanilla_rendered");
    }

    private static void end(EntityLivingBase entity, String source) {
        ModelCapture done = current;
        EntityLivingBase who = target;
        current = null;
        target = null;
        inModel = false;
        inPass = false;
        view = null;
        model = null;
        if (done == null || who != entity || !done.complete()) return;
        Vec3 origin = new Vec3(renderX + RenderManager.renderPosX, renderY + RenderManager.renderPosY, renderZ + RenderManager.renderPosZ);
        publishAt(entity, done.yaw(), done.boxes(), source, origin);
    }

    private static float[] modelview() {
        MATRIX.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MATRIX);
        float[] m = new float[16];
        MATRIX.get(m);
        return m;
    }

    public static boolean eligible(EntityLivingBase entity) {
        var mc = Minecraft.getMinecraft();
        return entity.worldObj == level && entity.isEntityAlive() && mc.thePlayer != null
                && entity.getDistanceSqToEntity(mc.thePlayer) <= ModelHits.RANGE * ModelHits.RANGE;
    }

    // ---- 26.2's publishAt / publishChecked ----

    public static boolean publishAt(EntityLivingBase entity, float yaw, List<ModelBox> boxes, String source, Vec3 origin) {
        try { return publishChecked(entity, yaw, boxes, source, origin); }
        catch (RuntimeException ex) {
            ModelHitLog.event("client", "publish_failed", entity, () -> "source=" + source + " " + ex);
            return false;
        }
    }

    static boolean publishChecked(EntityLivingBase entity, float yaw, List<ModelBox> boxes, String source, Vec3 origin) {
        var filtered = PlaneHitPolicy.filter(entity, AvatarHitPolicy.filter(entity, boxes));
        double radius = ModelHits.radius(entity);
        boolean invalid = false;
        for (int i = 0; i < filtered.size() && !invalid; i++) invalid = !filtered.get(i).valid(radius);
        if (filtered.isEmpty() || filtered.size() > ModelPosePayload.MAX_BOXES || invalid) {
            ModelHitLog.event("client", "capture_rejected", entity, () -> "source=" + source + " cubes=" + boxes.size()
                    + " radius=" + ModelHits.radius(entity) + " invalid=" + filtered.stream().filter(b -> !b.valid(ModelHits.radius(entity))).findFirst());
            return false;
        }
        var delta = origin.subtract(ModelHits.position(entity));
        var rendered = delta.lengthSqr() == 0 ? filtered : filtered.stream().map(b -> new ModelBox(b.part(), b.x() + delta.x, b.y() + delta.y, b.z() + delta.z,
                b.ux(), b.uy(), b.uz(), b.vx(), b.vy(), b.vz(), b.wx(), b.wy(), b.wz(), b.mesh())).toList();
        World world = entity.worldObj;
        ModelHits.put(world, entity, yaw, rendered);
        ModelHitLog.count("client", "local_pose");
        SYNC.put(entity.getUniqueID(), new Sync(entity, yaw, List.copyOf(filtered), source, world.getTotalWorldTime(), origin));
        return true;
    }

    private static void sync() {
        long tick = level.getTotalWorldTime();
        if (tick / ModelHits.INTERVAL != sendPeriod) {
            sendPeriod = tick / ModelHits.INTERVAL;
            sendBudget = ModelHits.MAX_UPDATES;
        }
        SYNC.entrySet().removeIf(e -> tick - e.getValue().tick() > ModelHits.MAX_SYNC_AGE || !eligible(e.getValue().entity()));
        if (sendBudget <= 0) return;
        // В толпе приоритет у давно не отправлявшихся сущностей, а не у первых в порядке рисования.
        var selected = SYNC.values().stream().filter(s -> tick - SENT.getOrDefault(s.entity().getUniqueID(), Long.MIN_VALUE / 2) >= ModelHits.INTERVAL)
                .sorted(java.util.Comparator.comparingLong(s -> SENT.getOrDefault(s.entity().getUniqueID(), Long.MIN_VALUE / 2)))
                .limit(sendBudget).toList();
        for (Sync pose : selected) {
            EntityLivingBase entity = pose.entity();
            for (ModelBox box : pose.boxes()) if (box.mesh() != 0 && MESH_SENT.add(box.mesh())) {
                ModelMesh mesh = ModelMesh.get(box.mesh());
                ModelHitNet.send(new ModelMeshPayload(mesh.id(), mesh.triangles()));
            }
            sendBudget--;
            SENT.put(entity.getUniqueID(), tick);
            ModelHitNet.send(new ModelPosePayload(entity.getEntityId(), entity.getUniqueID(), pose.origin().x, pose.origin().y,
                    pose.origin().z, pose.yaw(), pose.boxes()));
            ModelHitLog.event("client", "sent", entity, () -> "source=" + pose.source() + " cubes=" + pose.boxes().size() + " tick=" + tick);
        }
        if (SENT.size() > 512) SENT.entrySet().removeIf(e -> tick - e.getValue() > 100);
    }

    public static void clear() {
        if (level != null) ModelHits.clear(level);
        SENT.clear(); MESH_SENT.clear(); SYNC.clear(); ModelTextureMask.clear(); ModelContours.clear();
        current = null; target = null; inModel = false; view = null;
        level = null;
        nextCheck = 0;
        sendPeriod = Long.MIN_VALUE;
    }
}
