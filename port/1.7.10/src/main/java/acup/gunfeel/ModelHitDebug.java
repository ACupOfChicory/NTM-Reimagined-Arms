package acup.gunfeel;

import java.util.List;

import org.lwjgl.opengl.GL11;

import acup.gunfeel.phys.Vec3;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;

// F3+B with model hitboxes (26.2: ModelHitDebug, MixinModelHitboxDebug, HeadDebug): where the client has a usable
// pose, the entity's outline is the outline of its posed cubes (ModelContours: edges hidden inside another part
// left out) instead of the box, and the head zones are drawn over it in red. The pose is the client's ModelHits
// store, the same one its rays use, so what is drawn is what is tested. A multipart entity's part boxes (the
// dragon's) are hidden when the owner has a pose, as in 26.2. Drawn at once in GL lines, in the place and GL state of
// 1.7.10's own box (RenderManager.renderDebugBoundingBox); 26.2's GPU buffers and background builds are not needed
// for an outline that is only drawn in debug mode.
// Not drawn: 26.2's cut planes of a head zone (HeadLabelCuts); a cut head is drawn as its whole part.
public final class ModelHitDebug {
    private ModelHitDebug() {}

    // RenderManager.renderDebugBoundingBox HEAD; true: drawn here, skip the box
    public static boolean draw(Entity entity) {
        if (!ModelHitClient.enabled()) return false;
        Entity parent = ModelPartEntities.parent(entity);
        if (parent != null) return ModelHits.hasPose(parent);
        if (!(entity instanceof EntityLivingBase) || !ModelHits.hasPose(entity)) return false;
        List<ModelBox> boxes = ModelHits.worldBoxes(entity);
        if (boxes.isEmpty()) return false;
        List<Vec3[]> outline = ModelContours.edges(boxes);
        List<HeadZone> heads = ModelHits.headZones(entity);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_BLEND);
        lines(outline, 0xFFFFFF);
        for (HeadZone zone : heads) lines(ModelContours.edges(List.of(zone.box())), 0xFF3030);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDepthMask(true);
        return true;
    }

    private static void lines(List<Vec3[]> strips, int color) {
        Tessellator t = Tessellator.instance;
        t.startDrawing(GL11.GL_LINES);
        t.setColorOpaque_I(color);
        double ox = RenderManager.renderPosX, oy = RenderManager.renderPosY, oz = RenderManager.renderPosZ;
        for (Vec3[] strip : strips)
            for (int i = 0; i + 1 < strip.length; i += 2) {
                t.addVertex(strip[i].x - ox, strip[i].y - oy, strip[i].z - oz);
                t.addVertex(strip[i + 1].x - ox, strip[i + 1].y - oy, strip[i + 1].z - oz);
            }
        t.draw();
    }
}
