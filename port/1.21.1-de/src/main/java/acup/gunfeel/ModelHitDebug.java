package acup.gunfeel;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

// Port copy (notes/ports/de-1.21.1.md, «Копии»), as port/1.12.2's: F3+B with model hitboxes (26.2: ModelHitDebug,
// MixinModelHitboxDebug, HeadDebug). Where the client has a usable pose the entity's box and eye strip give way to the
// outline of its posed cubes (ModelContours: edges hidden inside another part left out), in 26.2's orange, with the
// head zones over it in its green; a multipart entity's part boxes (the dragon's) are hidden when it has a pose. The
// pose is the client's ModelHits store, the one its rays use, so what is drawn is what is tested. Drawn at once into
// the lines F3+B draws the box with (EntityRenderDispatcher.renderHitbox: the pose is at the entity, its box at
// getBoundingBox() less its position); 26.2's GPU mask, which hides an edge behind another mob or a block, is not
// ported for an outline drawn only in debug mode. The pose is the one the last frame published (26.2 draws F3+B after
// the world, with this frame's): the outline is a frame behind the model, where the rays are, and a mob drawn for the
// first time has its plain box for that frame.
// Not drawn: 26.2's cut planes of a head zone (HeadLabelCuts); a cut head is drawn as its whole part.
public final class ModelHitDebug {
    private ModelHitDebug() {}

    public static void beginFrame() {}

    // renderHitbox HEAD; true: drawn here, the box and the eye strip are not
    // the boxes are in the world where the frame drew the model (ModelHitClient.publishAt: the lerped position); the
    // pose stands at this frame's lerped position, without the renderer's offset (EntityRenderDispatcher.render
    // takes it off before the hitbox)
    public static boolean draw(PoseStack pose, VertexConsumer lines, Entity entity, float partial) {
        if (!(entity instanceof LivingEntity) || !ModelHitClient.enabled() || !acup.gunfeel.compat.Feature.MODEL_HITBOXES.on()) return false;
        List<ModelBox> boxes = ModelHits.worldBoxes(entity);
        boolean custom = !boxes.isEmpty();
        MobSmoke.debug(entity, custom);
        if (!custom) return false;
        // as LevelRenderer.renderEntity places it (xOld, not getPosition's xo)
        Vec3 at = new Vec3(net.minecraft.util.Mth.lerp(partial, entity.xOld, entity.getX()),
                net.minecraft.util.Mth.lerp(partial, entity.yOld, entity.getY()), net.minecraft.util.Mth.lerp(partial, entity.zOld, entity.getZ()));
        lines(pose, lines, ModelContours.edges(boxes), at, 1F, .8F, 1F / 3);
        for (HeadZone zone : ModelHits.headZones(entity)) lines(pose, lines, ModelContours.edges(List.of(zone.box())), at, .15F, 1F, .15F);
        return true;
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
