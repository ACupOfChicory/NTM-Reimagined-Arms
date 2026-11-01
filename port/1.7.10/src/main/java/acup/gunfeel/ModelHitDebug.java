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
        near(entity);
        if (aroundCamera(entity)) return true;
        if (!ModelHitClient.enabled() || !acup.gunfeel.compat.Feature.MODEL_HITBOXES.on()) return false;
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

    // A box the camera is inside is not drawn: its lines pass a few hundredths of a block from the eye and cross the
    // whole screen as "a box in front of the player that moves with them" (owner's report). In the owner's pack that is
    // iChunUtil's EntityTrail (ichun.client.voxel), a 0.1 client-only entity kept at the player's position, at eye
    // height in 1.7.10 (the client player's posY is its eye). Vanilla skips the player's own box the same way in first
    // person.
    static boolean aroundCamera(Entity entity) {
        Entity view = net.minecraft.client.Minecraft.getMinecraft().renderViewEntity;
        if (view == null || entity == view || entity.boundingBox == null) return false;
        // the box is drawn where the entity is between two ticks, the camera is where the view is between them: both
        // ends of the tick are checked. EntityRenderer.orientCamera: the eye is posY - (yOffset - 1.62).
        double eye = 1.62F - view.yOffset;
        var b = entity.boundingBox;
        double x0 = b.minX - entity.posX, y0 = b.minY - entity.posY, z0 = b.minZ - entity.posZ;
        double x1 = b.maxX - entity.posX, y1 = b.maxY - entity.posY, z1 = b.maxZ - entity.posZ;
        return contains(x0, y0, z0, x1, y1, z1, view.posX - entity.posX, view.posY + eye - entity.posY, view.posZ - entity.posZ, 0.05)
                && contains(x0, y0, z0, x1, y1, z1, view.lastTickPosX - entity.lastTickPosX, view.lastTickPosY + eye - entity.lastTickPosY,
                        view.lastTickPosZ - entity.lastTickPosZ, 0.05);
    }

    static boolean contains(double x0, double y0, double z0, double x1, double y1, double z1, double x, double y, double z, double margin) {
        return x >= x0 - margin && x <= x1 + margin && y >= y0 - margin && y <= y1 + margin && z >= z0 - margin && z <= z1 + margin;
    }

    // diagnostic (owner's report: a box in front of the player that moves with them): each entity within 3 blocks of
    // the camera that gets an F3+B box, once per entity and viewport, with the viewport (a shader's shadow pass draws
    // into a square one)
    private static final java.util.Set<String> NEAR = new java.util.HashSet<>();
    private static final java.nio.IntBuffer VIEWPORT = org.lwjgl.BufferUtils.createIntBuffer(16);
    private static void near(Entity entity) {
        Entity view = net.minecraft.client.Minecraft.getMinecraft().renderViewEntity;
        if (view == null || entity.getDistanceSqToEntity(view) > 9) return;
        VIEWPORT.clear();
        GL11.glGetInteger(GL11.GL_VIEWPORT, VIEWPORT);
        String key = entity.getEntityId() + " " + VIEWPORT.get(2) + "x" + VIEWPORT.get(3);
        if (NEAR.size() > 200 || !NEAR.add(key)) return;
        GunFeel.LOGGER.info("box near the camera: {} id={} self={} riding={} ridden={} at {} {} {} from the view, box {}, viewport {}",
                entity.getClass().getName(), entity.getEntityId(), entity == view, entity.ridingEntity, entity.riddenByEntity,
                String.format("%.2f", entity.posX - view.posX), String.format("%.2f", entity.posY - view.posY),
                String.format("%.2f", entity.posZ - view.posZ), entity.boundingBox, VIEWPORT.get(2) + "x" + VIEWPORT.get(3));
    }

    // F3+B lines at full brightness, in the dark and in fog as in daylight: vanilla draws the box with the world's
    // lightmap on (and its fog), so at night the lines were nearly black. The lightmap unit's texture off and its
    // coordinates at full light (a shader pack's line program reads them), fog off; put back after.
    private static boolean fog, bright;
    private static float lightX, lightY;

    public static void brightBegin() {
        if (bright) return;
        bright = true;
        fog = GL11.glIsEnabled(GL11.GL_FOG);
        GL11.glDisable(GL11.GL_FOG);
        lightX = net.minecraft.client.renderer.OpenGlHelper.lastBrightnessX;
        lightY = net.minecraft.client.renderer.OpenGlHelper.lastBrightnessY;
        net.minecraft.client.renderer.OpenGlHelper.setLightmapTextureCoords(net.minecraft.client.renderer.OpenGlHelper.lightmapTexUnit, 240F, 240F);
        net.minecraft.client.renderer.OpenGlHelper.setActiveTexture(net.minecraft.client.renderer.OpenGlHelper.lightmapTexUnit);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        net.minecraft.client.renderer.OpenGlHelper.setActiveTexture(net.minecraft.client.renderer.OpenGlHelper.defaultTexUnit);
    }

    public static void brightEnd() {
        if (!bright) return;
        bright = false;
        net.minecraft.client.renderer.OpenGlHelper.setActiveTexture(net.minecraft.client.renderer.OpenGlHelper.lightmapTexUnit);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        net.minecraft.client.renderer.OpenGlHelper.setActiveTexture(net.minecraft.client.renderer.OpenGlHelper.defaultTexUnit);
        net.minecraft.client.renderer.OpenGlHelper.setLightmapTextureCoords(net.minecraft.client.renderer.OpenGlHelper.lightmapTexUnit, lightX, lightY);
        if (fog) GL11.glEnable(GL11.GL_FOG);
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
