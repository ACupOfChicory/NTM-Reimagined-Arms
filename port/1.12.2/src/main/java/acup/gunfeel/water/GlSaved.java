package acup.gunfeel.water;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;

// port: the GL state the water's drawing changes, read as it starts and put back through GlStateManager, so its cache
// stays the GL's (glPopAttrib would put GL back behind the cache's back: the particles drawn after it would skip
// their own blend). Texture unit 0, as the world's passes leave it active.
final class GlSaved {
    private final boolean blend, texture, alpha, cull, lighting, depthMask, lightmap;
    private final int src, dst, srcA, dstA, alphaFunc;
    private final float alphaRef;

    GlSaved() {
        blend = GL11.glIsEnabled(GL11.GL_BLEND);
        texture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        src = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        dst = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        srcA = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        dstA = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        alphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        alphaRef = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        lightmap = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    void restore() {
        if (blend) GlStateManager.enableBlend();
        else GlStateManager.disableBlend();
        GlStateManager.tryBlendFuncSeparate(src, dst, srcA, dstA);
        if (texture) GlStateManager.enableTexture2D();
        else GlStateManager.disableTexture2D();
        if (alpha) GlStateManager.enableAlpha();
        else GlStateManager.disableAlpha();
        if (cull) GlStateManager.enableCull();
        else GlStateManager.disableCull();
        if (lighting) GlStateManager.enableLighting();
        else GlStateManager.disableLighting();
        GlStateManager.depthMask(depthMask);
        GlStateManager.alphaFunc(alphaFunc, alphaRef);
        GlStateManager.color(1F, 1F, 1F, 1F);
        if (lightmap) Minecraft.getMinecraft().entityRenderer.enableLightmap();
        else Minecraft.getMinecraft().entityRenderer.disableLightmap();
    }
}
