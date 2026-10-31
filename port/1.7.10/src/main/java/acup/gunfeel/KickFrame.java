package acup.gunfeel;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.hbm.render.anim.HbmAnimations;
import com.hbm.render.loader.HFRWavefrontObject;
import com.hbm.render.loader.S_Face;
import com.hbm.render.loader.S_GroupObject;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.model.obj.Vertex;

// Where the gun model kick turns and how long the gun is, in NTM's gun frame (ModelRecoil.apply). 26.2 takes both from
// the arms' capture of the model (Arms.kickFrame, GunGeometry); until the arms are ported this is the part of it the
// kick needs:
// - while NTM draws the first-person gun, every model part drawn and the GL matrix it is drawn with (part);
// - after the frame, the body (the biggest part drawn) relative to the gun frame (end): REL = KICKED^-1 * BODY,
//   the model turned into the gun's own axes by whole quarter turns while no animation plays (GunGeometry.align);
// - the next frame's kick: size = the model's length along the gun, pivot = the right-hand grip (Grips), through REL.
// The grip is found on the cross-section of the model's triangles (26.2: of its quads), so it may sit a little apart.
public final class KickFrame {
    // one variant of a model: the parts NTM draws for it
    private static final class Geometry {
        final HFRWavefrontObject model;
        final BitSet parts;
        final String missing;
        int bodyPart = -1;
        boolean aligned;
        // model space -> aligned frame: a whole-quarter-turn rotation, rows of 3
        final float[] toAligned = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        float minX, minY, minZ, maxX, maxY, maxZ;
        // aligned triangles, 3 vertices x 3 floats each
        float[] tris = new float[0];

        Geometry(HFRWavefrontObject model, BitSet parts) {
            this.model = model;
            this.parts = parts;
            StringBuilder name = new StringBuilder();
            for (int p = 0; p < model.groupObjects.size(); p++) if (!parts.get(p)) name.append('-').append(model.groupObjects.get(p).name);
            missing = name.toString();
            float best = -1F;
            for (int p = parts.nextSetBit(0); p >= 0; p = parts.nextSetBit(p + 1)) {
                float[] b = bounds(model.groupObjects.get(p));
                float v = Math.max(b[3] - b[0], 1e-3F) * Math.max(b[4] - b[1], 1e-3F) * Math.max(b[5] - b[2], 1e-3F);
                if (v > best) {
                    best = v;
                    bodyPart = p;
                }
            }
        }

        // 26.2 GunGeometry.align: each column of the body's turn snapped to its biggest axis
        void align(float[] rel) {
            if (aligned) return;
            float[] r = new float[9];
            for (int c = 0; c < 3; c++) {
                float[] col = Mat4.column(rel, c);
                int k = Math.abs(col[0]) >= Math.abs(col[1]) && Math.abs(col[0]) >= Math.abs(col[2]) ? 0 : Math.abs(col[1]) >= Math.abs(col[2]) ? 1 : 2;
                float sign = Math.signum(col[k]);
                r[k * 3 + c] = sign == 0F ? 1F : sign;
            }
            float det = r[0] * (r[4] * r[8] - r[5] * r[7]) - r[1] * (r[3] * r[8] - r[5] * r[6]) + r[2] * (r[3] * r[7] - r[4] * r[6]);
            if (Math.abs(det) < 0.5F) r = new float[] {1, 0, 0, 0, 1, 0, 0, 0, 1}; // two columns snapped to the same axis
            System.arraycopy(r, 0, toAligned, 0, 9);
            List<float[]> out = new ArrayList<float[]>();
            minX = minY = minZ = Float.MAX_VALUE;
            maxX = maxY = maxZ = -Float.MAX_VALUE;
            for (int p = parts.nextSetBit(0); p >= 0; p = parts.nextSetBit(p + 1)) {
                for (S_Face f : model.groupObjects.get(p).faces) {
                    if (f.vertices == null || f.vertices.length < 3) continue;
                    // a polygon as a fan of triangles
                    for (int i = 1; i + 1 < f.vertices.length; i++) {
                        float[] t = new float[9];
                        Vertex[] v = {f.vertices[0], f.vertices[i], f.vertices[i + 1]};
                        for (int k = 0; k < 3; k++) {
                            float x = v[k].x, y = v[k].y, z = v[k].z;
                            float ax = r[0] * x + r[1] * y + r[2] * z, ay = r[3] * x + r[4] * y + r[5] * z, az = r[6] * x + r[7] * y + r[8] * z;
                            t[k * 3] = ax;
                            t[k * 3 + 1] = ay;
                            t[k * 3 + 2] = az;
                            minX = Math.min(minX, ax);
                            minY = Math.min(minY, ay);
                            minZ = Math.min(minZ, az);
                            maxX = Math.max(maxX, ax);
                            maxY = Math.max(maxY, ay);
                            maxZ = Math.max(maxZ, az);
                        }
                        out.add(t);
                    }
                }
            }
            tris = new float[out.size() * 9];
            for (int i = 0; i < out.size(); i++) System.arraycopy(out.get(i), 0, tris, i * 9, 9);
            aligned = minX <= maxX;
        }

        // aligned frame -> model space (the transpose of a quarter-turn rotation), as a GL matrix
        float[] fromAligned() {
            float[] r = toAligned;
            return new float[] {r[0], r[1], r[2], 0, r[3], r[4], r[5], 0, r[6], r[7], r[8], 0, 0, 0, 0, 1};
        }

        float length() {
            return maxZ - minZ;
        }

        // 26.2 GunGeometry.grip: z = fraction of the length; x, y = fractions of the cross-section there
        float[] grip(float[] f) {
            float z = minZ + length() * f[2];
            float[] s = section(z, Math.max(length() * 0.04F, 1e-3F));
            if (s == null) return new float[] {minX + (maxX - minX) * f[0], minY + (maxY - minY) * f[1], z};
            return new float[] {s[0] + (s[1] - s[0]) * f[0], s[2] + (s[3] - s[2]) * f[1], z};
        }

        // {minX, maxX, minY, maxY} of the vertices within band of z and of the edges crossing z; null = none
        private float[] section(float z, float band) {
            float lx = Float.MAX_VALUE, hx = -Float.MAX_VALUE, ly = Float.MAX_VALUE, hy = -Float.MAX_VALUE;
            float[] v = tris;
            for (int i = 0; i + 8 < v.length; i += 9) {
                for (int k = 0; k < 3; k++) {
                    int a = i + k * 3, b = i + (k + 1) % 3 * 3;
                    float za = v[a + 2], zb = v[b + 2];
                    if (Math.abs(za - z) <= band) {
                        lx = Math.min(lx, v[a]);
                        hx = Math.max(hx, v[a]);
                        ly = Math.min(ly, v[a + 1]);
                        hy = Math.max(hy, v[a + 1]);
                    }
                    if ((za - z) * (zb - z) < 0F) {
                        float t = (z - za) / (zb - za);
                        float x = v[a] + (v[b] - v[a]) * t, y = v[a + 1] + (v[b + 1] - v[a + 1]) * t;
                        lx = Math.min(lx, x);
                        hx = Math.max(hx, x);
                        ly = Math.min(ly, y);
                        hy = Math.max(hy, y);
                    }
                }
            }
            return lx > hx ? null : new float[] {lx, hx, ly, hy};
        }
    }

    static float[] bounds(S_GroupObject g) {
        float[] b = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (S_Face f : g.faces) {
            if (f.vertices == null) continue;
            for (Vertex v : f.vertices) {
                b[0] = Math.min(b[0], v.x);
                b[1] = Math.min(b[1], v.y);
                b[2] = Math.min(b[2], v.z);
                b[3] = Math.max(b[3], v.x);
                b[4] = Math.max(b[4], v.y);
                b[5] = Math.max(b[5], v.z);
            }
        }
        return b;
    }

    private static final Map<HFRWavefrontObject, Map<String, Geometry>> GEOMETRY = new IdentityHashMap<HFRWavefrontObject, Map<String, Geometry>>();
    // this frame: the model drawn, its parts drawn and the matrix of each part's first draw
    private static HFRWavefrontObject frameModel;
    private static final BitSet FRAME_PARTS = new BitSet();
    private static final Map<Integer, float[]> FRAME_POSE = new HashMap<Integer, float[]>();
    private static boolean capturing;
    private static float[] kicked;
    // the last frame's result
    private static Item relItem;
    private static float[] rel;
    private static Geometry relGeometry;
    private static int gripVersion = -1;
    private static float[] rightGrip;

    private static final FloatBuffer BUF = BufferUtils.createFloatBuffer(16);

    private KickFrame() {
    }

    // the GL modelview matrix now, column by column
    private static float[] modelview() {
        BUF.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, BUF);
        float[] m = new float[16];
        BUF.get(m);
        return m;
    }

    public static boolean capturing() {
        return capturing;
    }

    public static void begin() {
        capturing = true;
        frameModel = null;
        FRAME_PARTS.clear();
        FRAME_POSE.clear();
        kicked = null;
    }

    // the gun frame right after the kick, before NTM draws the model (26.2: Arms.gunFrame)
    public static void gunFrame() {
        if (capturing) kicked = modelview();
    }

    // NTM draws parts of `model`: mode 0 = all, 1 = only these, 2 = all but these
    public static void part(HFRWavefrontObject model, int mode, String... names) {
        if (!capturing || kicked == null || model == null) return;
        if (frameModel != null && frameModel != model) return; // a second model (an attachment): the first one is the gun
        frameModel = model;
        float[] pose = null;
        List<S_GroupObject> groups = model.groupObjects;
        for (int p = 0; p < groups.size(); p++) {
            String n = groups.get(p).name;
            boolean named = false;
            if (names != null) for (String s : names) if (s != null && s.equalsIgnoreCase(n)) named = true;
            if (mode == 1 && !named || mode == 2 && named) continue;
            FRAME_PARTS.set(p);
            if (!FRAME_POSE.containsKey(p)) {
                if (pose == null) pose = modelview();
                FRAME_POSE.put(p, pose);
            }
        }
    }

    // after NTM drew the gun (26.2: Arms.end)
    public static void end(ItemStack stack) {
        if (!capturing) return;
        Item item = stack == null ? null : stack.getItem();
        capturing = false;
        if (frameModel == null || kicked == null || FRAME_PARTS.isEmpty()) {
            rel = null;
            return;
        }
        Map<String, Geometry> variants = GEOMETRY.get(frameModel);
        if (variants == null) GEOMETRY.put(frameModel, variants = new HashMap<String, Geometry>());
        String key = FRAME_PARTS.toString();
        Geometry g = variants.get(key);
        // a new variant only while no animation plays (an animation may hide a part for a moment)
        if (g == null && HbmAnimations.getRelevantAnim(0) == null) variants.put(key, g = new Geometry(frameModel, (BitSet) FRAME_PARTS.clone()));
        if (g == null) g = relItem == item ? relGeometry : null;
        float[] body = g == null ? null : FRAME_POSE.get(g.bodyPart);
        float[] inv = Mat4.invertAffine(kicked);
        if (g == null || body == null || inv == null) {
            rel = null;
            return;
        }
        if (!g.aligned && HbmAnimations.getRelevantAnim(0) == null) g.align(Mat4.mul(inv, body));
        if (!g.aligned) {
            rel = null;
            return;
        }
        rel = Mat4.mul(Mat4.mul(inv, body), g.fromAligned());
        if (relGeometry != g || relItem != item || gripVersion != Grips.version()) {
            String id = GunFeelClient.gunId(item);
            Grips.Grip grip = id == null ? Grips.DEFAULT : Grips.get(id, g.missing);
            rightGrip = grip.rightAt() != null ? grip.rightAt() : g.grip(grip.right());
            gripVersion = Grips.version();
        }
        relItem = item;
        relGeometry = g;
    }

    // the gun model kick for the gun being drawn, in its frame from the last frame (26.2: MixinItemRenderWeaponBase.gunfeel$kick)
    public static void kick(ItemStack stack) {
        float[] pivot = new float[3];
        float size = kickFrame(stack == null ? null : stack.getItem(), pivot);
        if (size > 0F) ModelRecoil.apply(size, pivot);
    }

    // the kick's frame for the gun in hand: its length there (0 = not known yet), the right-hand grip into pivot
    static float kickFrame(Item held, float[] pivot) {
        if (rel == null || relItem != held || relGeometry == null) return 0F;
        float[] p = Mat4.point(rel, rightGrip[0], rightGrip[1], rightGrip[2]);
        System.arraycopy(p, 0, pivot, 0, 3);
        float[] a = Mat4.point(rel, 0F, 0F, relGeometry.minZ), b = Mat4.point(rel, 0F, 0F, relGeometry.maxZ);
        float dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
