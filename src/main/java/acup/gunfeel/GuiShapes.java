package acup.gunfeel;

import org.joml.Matrix3x2f;
import org.joml.Vector2f;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;

import acup.gunfeel.mixin.mc.GuiGraphicsAccess;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;

// Client: the shapes of ShapeMesh for the GUI (RadialMenu, ShockArcs), drawn by the GUI's own colour pipeline in the
// current stratum, so items and text of that stratum stay above them.
public final class GuiShapes extends ShapeMesh implements GuiElementRenderState {
    private final Matrix3x2f pose;

    public GuiShapes(GuiGraphicsExtractor g) {
        pose = new Matrix3x2f(g.pose());
    }

    public void submit(GuiGraphicsExtractor g) {
        if (n > 0) ((GuiGraphicsAccess) g).gunfeel$state().addGuiElement(this);
    }

    @Override
    public void buildVertices(VertexConsumer vc) {
        for (int i = 0; i < n; i++) vc.addVertexWith2DPose(pose, xy[i * 2], xy[i * 2 + 1]).setColor(col[i]);
    }

    @Override
    public RenderPipeline pipeline() {
        return RenderPipelines.GUI;
    }

    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }

    @Override
    public ScreenRectangle scissorArea() {
        return null;
    }

    @Override
    public ScreenRectangle bounds() {
        if (n == 0) return new ScreenRectangle(0, 0, 0, 0);
        Vector2f a = pose.transformPosition(minX, minY, new Vector2f()), b = pose.transformPosition(maxX, maxY, new Vector2f());
        Vector2f c = pose.transformPosition(minX, maxY, new Vector2f()), d = pose.transformPosition(maxX, minY, new Vector2f());
        float x0 = Math.min(Math.min(a.x, b.x), Math.min(c.x, d.x)), y0 = Math.min(Math.min(a.y, b.y), Math.min(c.y, d.y));
        float x1 = Math.max(Math.max(a.x, b.x), Math.max(c.x, d.x)), y1 = Math.max(Math.max(a.y, b.y), Math.max(c.y, d.y));
        return new ScreenRectangle((int) Math.floor(x0), (int) Math.floor(y0), (int) Math.ceil(x1 - x0) + 1, (int) Math.ceil(y1 - y0) + 1);
    }
}
