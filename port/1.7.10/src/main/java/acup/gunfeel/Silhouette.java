package acup.gunfeel;

import java.util.IdentityHashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;

// port: copy of 26.2's Silhouette (e284339). Flat side view of an NTM gun for the HUD: the drawn parts of the
// first-person model rasterised on the CPU (orthographic, looking along -X of the aligned frame, muzzle to the right),
// light grey-white with simple diffuse shading, 2x supersampled. Port: NTM 1.7.10's faces carry no normals in
// GunGeometry.quads (x y z a vertex), so a quad's normal is its two diagonals' cross product; the picture goes into a
// DynamicTexture (26.2: NativeImage).
public final class Silhouette {
    public static final int W = 192, H = 64;
    private static final int SS = 2;
    private static final Map<GunGeometry, ResourceLocation> TEXTURES = new IdentityHashMap<GunGeometry, ResourceLocation>();

    private Silhouette() {
    }

    // null until the gun has been drawn once in first person
    public static ResourceLocation get(Item item) {
        GunGeometry geo = Arms.geometry(item);
        if (geo == null) return null;
        ResourceLocation id = TEXTURES.get(geo);
        if (id != null) return id;
        String key = String.valueOf(Item.itemRegistry.getNameForObject(item)).replace(':', '/');
        DynamicTexture tex = new DynamicTexture(W, H);
        render(geo, tex.getTextureData());
        tex.updateDynamicTexture();
        id = Minecraft.getMinecraft().getTextureManager().getDynamicTextureLocation("ntmgunfeel_hud/" + key + (geo.variant > 0 ? "_" + geo.variant : ""), tex);
        TEXTURES.put(geo, id);
        return id;
    }

    // ARGB into out (W x H)
    static void render(GunGeometry geo, int[] out) {
        int w = W * SS, h = H * SS;
        float[] depth = new float[w * h];
        int[] shade = new int[w * h];
        java.util.Arrays.fill(depth, -Float.MAX_VALUE);
        float[][] quads = new float[geo.model.groupObjects.size()][];
        for (int p = geo.parts.nextSetBit(0); p >= 0; p = geo.parts.nextSetBit(p + 1)) quads[p] = GunGeometry.quads(geo.model.groupObjects.get(p));
        // bounds of the parts as placed
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int p = geo.parts.nextSetBit(0); p >= 0; p = geo.parts.nextSetBit(p + 1)) {
            float[] q = quads[p];
            Matrix4f place = place(geo, p);
            for (int v = 0; v + 2 < q.length; v += 3) {
                Vector3f a = place.transformPosition(new Vector3f(q[v], q[v + 1], q[v + 2]));
                minY = Math.min(minY, a.y);
                maxY = Math.max(maxY, a.y);
                minZ = Math.min(minZ, a.z);
                maxZ = Math.max(maxZ, a.z);
            }
        }
        java.util.Arrays.fill(out, 0);
        if (minY > maxY) return;
        float len = Math.max(maxZ - minZ, 1e-3F), tall = Math.max(maxY - minY, 1e-3F);
        float pad = 2F * SS;
        float scale = Math.min((w - 2 * pad) / len, (h - 2 * pad) / tall);
        float ox = (w - len * scale) / 2F, oy = (h - tall * scale) / 2F;
        // light from the viewer's upper left, slightly toward the screen
        float lx = 0.55F, ly = 0.75F, lz = -0.35F;

        for (int p = geo.parts.nextSetBit(0); p >= 0; p = geo.parts.nextSetBit(p + 1)) {
            float[] q = quads[p];
            Matrix4f place = place(geo, p);
            for (int f = 0; f + 12 <= q.length; f += 12) {
                Vector3f[] c4 = new Vector3f[4];
                for (int c = 0; c < 4; c++) c4[c] = place.transformPosition(new Vector3f(q[f + 3 * c], q[f + 3 * c + 1], q[f + 3 * c + 2]));
                Vector3f n = new Vector3f(c4[2]).sub(c4[0]).cross(new Vector3f(c4[3]).sub(c4[1]));
                if (n.lengthSquared() < 1e-12F) n = new Vector3f(c4[1]).sub(c4[0]).cross(new Vector3f(c4[2]).sub(c4[0]));
                if (n.lengthSquared() > 0F) n.normalize();
                float diffuse = Math.abs(n.x * lx + n.y * ly + n.z * lz);
                int grey = (int) (150 + 105 * Math.min(1F, diffuse));
                float[] sx = new float[4], sy = new float[4], sd = new float[4];
                for (int c = 0; c < 4; c++) {
                    sx[c] = ox + (c4[c].z - minZ) * scale;
                    sy[c] = oy + (maxY - c4[c].y) * scale;
                    sd[c] = c4[c].x;
                }
                tri(sx, sy, sd, 0, 1, 2, grey, depth, shade, w, h);
                tri(sx, sy, sd, 0, 2, 3, grey, depth, shade, w, h);
            }
        }

        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int a = 0, g = 0;
                for (int dy = 0; dy < SS; dy++) {
                    for (int dx = 0; dx < SS; dx++) {
                        int i = (y * SS + dy) * w + x * SS + dx;
                        if (shade[i] != 0) {
                            a++;
                            g += shade[i];
                        }
                    }
                }
                int n = SS * SS;
                int grey = a == 0 ? 0 : g / a;
                int alpha = a * 255 / n;
                out[y * W + x] = alpha << 24 | grey << 16 | grey << 8 | grey;
            }
        }
    }

    // the part where NTM draws it at rest (e.g. a shell sunk into the receiver), in the aligned frame
    private static Matrix4f place(GunGeometry geo, int part) {
        Matrix4f m = new Matrix4f().set(geo.toAligned);
        return geo.partRest[part] != null ? m.mul(geo.partRest[part]) : m;
    }

    private static void tri(float[] x, float[] y, float[] d, int a, int b, int c, int grey, float[] depth, int[] shade, int w, int h) {
        float area = (x[b] - x[a]) * (y[c] - y[a]) - (x[c] - x[a]) * (y[b] - y[a]);
        if (Math.abs(area) < 1e-6F) return;
        int x0 = Math.max(0, (int) Math.floor(Math.min(x[a], Math.min(x[b], x[c]))));
        int x1 = Math.min(w - 1, (int) Math.ceil(Math.max(x[a], Math.max(x[b], x[c]))));
        int y0 = Math.max(0, (int) Math.floor(Math.min(y[a], Math.min(y[b], y[c]))));
        int y1 = Math.min(h - 1, (int) Math.ceil(Math.max(y[a], Math.max(y[b], y[c]))));
        for (int py = y0; py <= y1; py++) {
            for (int px = x0; px <= x1; px++) {
                float cx = px + 0.5F, cy = py + 0.5F;
                float w0 = ((x[b] - cx) * (y[c] - cy) - (x[c] - cx) * (y[b] - cy)) / area;
                float w1 = ((x[c] - cx) * (y[a] - cy) - (x[a] - cx) * (y[c] - cy)) / area;
                float w2 = 1F - w0 - w1;
                if (w0 < 0F || w1 < 0F || w2 < 0F) continue;
                float z = w0 * d[a] + w1 * d[b] + w2 * d[c];
                int i = py * w + px;
                if (z > depth[i]) {
                    depth[i] = z;
                    shade[i] = grey;
                }
            }
        }
    }
}
