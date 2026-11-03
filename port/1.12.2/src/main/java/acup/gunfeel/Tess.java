package acup.gunfeel;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;

// port: new for 1.12.2. 1.7.10's Tessellator as the port's drawing code uses it (startDrawing, a colour, brightness,
// normal or UV set before each vertex, addVertex), drawn through 1.12.2's: the vertices are kept, and draw() picks a
// vertex format with what was set (position, then colour, UV, light, normal, as 1.12.2's BLOCK format orders them).
public final class Tess {
    public static final Tess instance = new Tess();

    private int mode;
    private boolean drawing, hasColor, hasTex, hasLight, hasNormal;
    private int color = -1, light, normal;
    private double u, v, tx, ty, tz;
    private double[] pos = new double[3 * 256];
    private double[] uv = new double[2 * 256];
    private int[] col = new int[256], lig = new int[256], nor = new int[256];
    private int n;

    private Tess() {}

    public void startDrawingQuads() {
        startDrawing(GL11.GL_QUADS);
    }

    public void startDrawing(int mode) {
        if (drawing) throw new IllegalStateException("Already tesselating!");
        drawing = true;
        this.mode = mode;
        n = 0;
        hasColor = hasTex = hasLight = hasNormal = false;
    }

    public void setColorRGBA_I(int rgb, int a) {
        setColorRGBA(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, a);
    }

    public void setColorOpaque_I(int rgb) {
        setColorRGBA_I(rgb, 255);
    }

    public void setColorRGBA_F(float r, float g, float b, float a) {
        setColorRGBA((int) (r * 255F), (int) (g * 255F), (int) (b * 255F), (int) (a * 255F));
    }

    public void setColorOpaque_F(float r, float g, float b) {
        setColorRGBA_F(r, g, b, 1F);
    }

    public void setColorRGBA(int r, int g, int b, int a) {
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        a = Math.max(0, Math.min(255, a));
        color = a << 24 | r << 16 | g << 8 | b;
        hasColor = true;
    }

    public void setBrightness(int b) {
        light = b;
        hasLight = true;
    }

    public void setNormal(float x, float y, float z) {
        normal = ((int) (x * 127F) & 255) | ((int) (y * 127F) & 255) << 8 | ((int) (z * 127F) & 255) << 16;
        hasNormal = true;
    }

    public void setTextureUV(double u, double v) {
        this.u = u;
        this.v = v;
        hasTex = true;
    }

    public void setTranslation(double x, double y, double z) {
        tx = x;
        ty = y;
        tz = z;
    }

    public void addTranslation(float x, float y, float z) {
        tx += x;
        ty += y;
        tz += z;
    }

    public void addVertexWithUV(double x, double y, double z, double u, double v) {
        setTextureUV(u, v);
        addVertex(x, y, z);
    }

    public void addVertex(double x, double y, double z) {
        if (n == col.length) grow();
        pos[n * 3] = x + tx;
        pos[n * 3 + 1] = y + ty;
        pos[n * 3 + 2] = z + tz;
        uv[n * 2] = u;
        uv[n * 2 + 1] = v;
        col[n] = color;
        lig[n] = light;
        nor[n] = normal;
        n++;
    }

    private void grow() {
        int c = col.length * 2;
        pos = java.util.Arrays.copyOf(pos, c * 3);
        uv = java.util.Arrays.copyOf(uv, c * 2);
        col = java.util.Arrays.copyOf(col, c);
        lig = java.util.Arrays.copyOf(lig, c);
        nor = java.util.Arrays.copyOf(nor, c);
    }

    public int draw() {
        if (!drawing) throw new IllegalStateException("Not tesselating!");
        drawing = false;
        int count = n;
        if (count == 0) return 0;
        VertexFormat f = new VertexFormat();
        f.addElement(DefaultVertexFormats.POSITION_3F);
        if (hasColor) f.addElement(DefaultVertexFormats.COLOR_4UB);
        if (hasTex) f.addElement(DefaultVertexFormats.TEX_2F);
        if (hasLight) f.addElement(DefaultVertexFormats.TEX_2S);
        if (hasNormal) {
            f.addElement(DefaultVertexFormats.NORMAL_3B);
            f.addElement(DefaultVertexFormats.PADDING_1B);
        }
        Tessellator t = Tessellator.getInstance();
        BufferBuilder b = t.getBuffer();
        b.begin(mode, f);
        for (int i = 0; i < count; i++) {
            b.pos(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]);
            if (hasColor) b.color(col[i] >> 16 & 255, col[i] >> 8 & 255, col[i] & 255, col[i] >>> 24);
            if (hasTex) b.tex(uv[i * 2], uv[i * 2 + 1]);
            if (hasLight) b.lightmap(lig[i] >> 16 & 65535, lig[i] & 65535);
            if (hasNormal) b.normal((byte) nor[i] / 127F, (byte) (nor[i] >> 8) / 127F, (byte) (nor[i] >> 16) / 127F);
            b.endVertex();
        }
        t.draw();
        n = 0;
        return count * 32;
    }
}
