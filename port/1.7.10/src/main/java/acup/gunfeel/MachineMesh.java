package acup.gunfeel;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.hbm.blocks.BlockDummyable;
import com.hbm.render.loader.HFRWavefrontObject;
import com.hbm.render.loader.S_Face;
import com.hbm.render.loader.S_GroupObject;

import net.minecraftforge.client.model.obj.Face;
import net.minecraftforge.client.model.obj.GroupObject;
import net.minecraftforge.client.model.obj.TextureCoordinate;
import net.minecraftforge.client.model.obj.Vertex;
import net.minecraftforge.client.model.obj.WavefrontObject;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

// port: new for 1.7.10 (26.2: RenderedMesh and the holes that follow a part, Tracers.meshHole / Follow). NTM's
// machines and doors are invisible blocks whose tile entity renderer draws OBJ models. Their renderer runs here with the
// modelview at the tile entity's corner and nothing written to the screen; each group of an NTM model it draws
// (NTM's loader: MixinWavefrontObject, MixinWavefrontObjectVBO; Forge's, which NTM loads 131 of its models with:
// MixinForgeWavefrontObject) is taken with the GL matrix then (a Part). A bullet hitting such a
// block meets the nearest drawn triangle along its way whose pixel passes the alpha test; its hole is kept in that
// part's own model space and drawn where the part is now (a door's leaf, a press head), the parts read again at most
// every PARTS_MS. Also taken: ModelRenderer parts (NTM's ModelBase models: the satellite dish on a pole, the statues;
// MixinModelRenderer) and what the renderer draws straight with the Tessellator (pylon wires, the RBMK display, molten
// metal, fluids and screens over an OBJ body, items it shows; MixinTessellator), a draw call being a part. Not seen (no
// hole there): Collada, and clip planes some doors cut their parts with.
public final class MachineMesh {
    private MachineMesh() {
    }

    static final long PARTS_MS = 100;
    // how far around the hit block the drawn triangle may lie (a model a little bigger or smaller than its blocks)
    static final double REACH = 0.35;

    // a group of an NTM model as drawn: the model, the group, which time this group was drawn in this frame (a part
    // drawn twice: a pair of wheels), the modelview (model space -> the tile entity's corner) and the bound texture
    static final class Part {
        final Object model, group;
        final int n;
        final float[] mv;
        final int texture;

        Part(Object model, Object group, int n, float[] mv, int texture) {
            this.model = model;
            this.group = group;
            this.n = n;
            this.mv = mv;
            this.texture = texture;
        }

        boolean same(Anchor a) {
            return model == a.model && (group == a.group || group.equals(a.group)) && n == a.n;
        }
    }

    // where a hole sits: the tile entity, the part, and the point, normal and texture-u axis in the part's model space
    static final class Anchor {
        final World world;
        final int x, y, z;
        final Object model, group;
        final int n;
        final double[] local, normal, tangent;

        Anchor(World world, int x, int y, int z, Part p, double[] local, double[] normal, double[] tangent) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.model = p.model;
            this.group = p.group;
            this.n = p.n;
            this.local = local;
            this.normal = normal;
            this.tangent = tangent;
        }
    }

    // a hit on a drawn part: the point and the unit normal turned to the bullet (world), and its anchor
    static final class Hit {
        final double[] point, normal;
        final Anchor anchor;
        final Part part;
        // the triangle met, its corners in the part's model space
        final double[] triangle;
        // met on a clear pixel of the machine's model: its glass (MachineGlass), a window
        boolean glass;

        Hit(double[] point, double[] normal, Anchor anchor, Part part, double[] triangle) {
            this.point = point;
            this.normal = normal;
            this.anchor = anchor;
            this.part = part;
            this.triangle = triangle;
        }
    }

    private static boolean active;
    private static final List<Part> PARTS = new ArrayList<Part>();
    private static final Map<Object, Integer> SEEN = new IdentityHashMap<Object, Integer>();
    private static final FloatBuffer MV = BufferUtils.createFloatBuffer(16);
    private static final Map<Integer, int[][]> TEXTURES = new HashMap<Integer, int[][]>();

    // what each tile entity drew, kept PARTS_MS: one burst into a machine reads it once
    private static final class Drawn {
        final TileEntity te;
        final long at;
        final List<Part> parts;

        Drawn(TileEntity te, long at, List<Part> parts) {
            this.te = te;
            this.at = at;
            this.parts = parts;
        }
    }

    private static final Map<String, Drawn> DRAWN = new LinkedHashMap<String, Drawn>(16, 0.75F, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Drawn> e) {
            return size() > 256;
        }
    };

    public static boolean active() {
        return active;
    }

    // inside a draw of an NTM model the parts above already took (its own Tessellator draw is not taken again), or
    // inside ModelRenderer's display list build (drawn later with the part's matrix)
    private static int objDepth, compiling;

    public static void objBegin() {
        if (active) objDepth++;
    }

    public static void objEnd() {
        if (active && objDepth > 0) objDepth--;
    }

    public static void compile(boolean begin) {
        if (active) compiling = Math.max(0, compiling + (begin ? 1 : -1));
    }

    // a ModelRenderer part starts drawing (MixinModelRenderer, render / renderWithRotation HEAD): its cubes in pixels,
    // with the GL matrix times what render applies before its display list, times the display list's scale
    public static void modelPart(net.minecraft.client.model.ModelRenderer r, float scale, boolean withRotation) {
        if (!active || r == null || r.isHidden || !r.showModel || r.cubeList == null || r.cubeList.isEmpty()) return;
        float[] mv = Mat4.mul(Mat4.mul(modelview(), ModelCapture.local(r, scale, withRotation)), ModelCapture.scaling(scale, scale, scale));
        add(r, r, mv, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
    }

    // what a renderer draws straight with the Tessellator: one draw call's faces, kept as drawn this time; the same
    // call (by its order in the renderer and its vertex count) in the next read is the same part
    static final class TessGroup {
        final int index, mode, count;
        final List<float[]> faces;

        TessGroup(int index, int mode, int count, List<float[]> faces) {
            this.index = index;
            this.mode = mode;
            this.count = count;
            this.faces = faces;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof TessGroup)) return false;
            TessGroup t = (TessGroup) o;
            return t.index == index && t.mode == mode && t.count == count;
        }

        @Override
        public int hashCode() {
            return (index * 31 + mode) * 31 + count;
        }
    }

    private static final Object TESS = "tessellator";
    private static int tessIndex;

    // Tessellator.draw HEAD (MixinTessellator): its vertices (8 ints each: x, y, z, u, v as float bits; offsets
    // already added) in quads, triangles, a strip or a fan; lines and points are not a surface
    public static void tess(int[] raw, int count, int mode, boolean textured) {
        if (!active || objDepth > 0 || compiling > 0 || raw == null || count < 3) return;
        // a glow, a beam, a scan line: blended, not a surface a bullet meets
        if (GL11.glIsEnabled(GL11.GL_BLEND)) return;
        int index = tessIndex++;
        List<float[]> faces = new ArrayList<float[]>();
        switch (mode) {
            case GL11.GL_QUADS:
                for (int i = 0; i + 3 < count; i += 4) faces.add(tessFace(raw, textured, i, i + 1, i + 2, i + 3));
                break;
            case GL11.GL_TRIANGLES:
                for (int i = 0; i + 2 < count; i += 3) faces.add(tessFace(raw, textured, i, i + 1, i + 2));
                break;
            case GL11.GL_TRIANGLE_STRIP:
            case GL11.GL_QUAD_STRIP:
                for (int i = 0; i + 2 < count; i++) faces.add(tessFace(raw, textured, i, i + 1, i + 2));
                break;
            case GL11.GL_TRIANGLE_FAN:
            case GL11.GL_POLYGON:
                for (int i = 1; i + 1 < count; i++) faces.add(tessFace(raw, textured, 0, i, i + 1));
                break;
            default:
                return;
        }
        add(TESS, new TessGroup(index, mode, count, faces), modelview(), GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D));
    }

    private static float[] tessFace(int[] raw, boolean textured, int... at) {
        float[] out = new float[at.length * 5];
        for (int c = 0; c < at.length; c++) {
            int b = at[c] * 8;
            out[c * 5] = Float.intBitsToFloat(raw[b]);
            out[c * 5 + 1] = Float.intBitsToFloat(raw[b + 1]);
            out[c * 5 + 2] = Float.intBitsToFloat(raw[b + 2]);
            out[c * 5 + 3] = textured ? Float.intBitsToFloat(raw[b + 3]) : Float.NaN;
            out[c * 5 + 4] = textured ? Float.intBitsToFloat(raw[b + 4]) : Float.NaN;
        }
        return out;
    }

    // a part of an NTM model is drawn: mode 0 all groups, 1 only the named, 2 all but the named (MixinWavefrontObject)
    public static void parts(HFRWavefrontObject model, int mode, String... names) {
        if (!active || model == null) return;
        float[] mv = modelview();
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        for (S_GroupObject g : model.groupObjects) if (drawn(g.name, mode, names)) add(model, g, mv, texture);
    }

    // ... of a model Forge's loader read (MixinForgeWavefrontObject)
    public static void forge(WavefrontObject model, int mode, String... names) {
        if (!active || model == null) return;
        float[] mv = modelview();
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        for (GroupObject g : model.groupObjects) if (drawn(g.name, mode, names)) add(model, g, mv, texture);
    }

    private static boolean drawn(String group, int mode, String[] names) {
        if (mode == 0) return true;
        boolean named = false;
        for (String n : names) named |= n.equalsIgnoreCase(group);
        return mode == 1 ? named : !named;
    }

    private static float[] modelview() {
        MV.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MV);
        float[] mv = new float[16];
        MV.get(mv);
        return mv;
    }

    private static void add(Object model, Object group, float[] mv, int texture) {
        Integer seen = SEEN.get(group);
        int n = seen == null ? 0 : seen;
        SEEN.put(group, n + 1);
        PARTS.add(new Part(model, group, n, mv, texture));
    }

    // a group's faces, each its corners' {x, y, z, u, v} one after another (u, v NaN: no texture coordinates); read once
    private static final Map<Object, List<float[]>> FACES = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<Object, List<float[]>>());

    static List<float[]> faces(Object group) {
        if (group instanceof TessGroup) return ((TessGroup) group).faces;
        List<float[]> out = FACES.get(group);
        if (out != null) return out;
        out = new ArrayList<float[]>();
        if (group instanceof net.minecraft.client.model.ModelRenderer) {
            for (net.minecraft.client.model.ModelBox box : ((net.minecraft.client.model.ModelRenderer) group).cubeList) {
                for (ModelCubeGeometry.Polygon q : ModelCubeGeometry.Cube.of(box).polygons) {
                    ModelCubeGeometry.Vertex[] v = q.vertices();
                    float[] f = new float[v.length * 5];
                    for (int i = 0; i < v.length; i++) {
                        f[i * 5] = v[i].x();
                        f[i * 5 + 1] = v[i].y();
                        f[i * 5 + 2] = v[i].z();
                        f[i * 5 + 3] = v[i].u();
                        f[i * 5 + 4] = v[i].v();
                    }
                    out.add(f);
                }
            }
        } else if (group instanceof S_GroupObject) {
            for (S_Face f : ((S_GroupObject) group).faces) out.add(face(f.vertices, f.textureCoordinates));
        } else if (group instanceof GroupObject) {
            for (Face f : ((GroupObject) group).faces) out.add(face(f.vertices, f.textureCoordinates));
        }
        FACES.put(group, out);
        return out;
    }

    private static float[] face(Vertex[] v, TextureCoordinate[] t) {
        if (v == null) return new float[0];
        float[] out = new float[v.length * 5];
        for (int i = 0; i < v.length; i++) {
            out[i * 5] = v[i].x;
            out[i * 5 + 1] = v[i].y;
            out[i * 5 + 2] = v[i].z;
            boolean uv = t != null && t.length > i && t[i] != null;
            out[i * 5 + 3] = uv ? t[i].u : Float.NaN;
            out[i * 5 + 4] = uv ? t[i].v : Float.NaN;
        }
        return out;
    }

    // the parts the tile entity's renderer draws now (read again after PARTS_MS); empty: no renderer, or nothing of
    // NTM's models
    static List<Part> drawn(TileEntity te) {
        String key = te.getWorldObj().provider.dimensionId + ":" + te.xCoord + "," + te.yCoord + "," + te.zCoord;
        long now = GunClock.nanos() / 1_000_000L;
        Drawn d = DRAWN.get(key);
        if (d != null && d.te == te && now - d.at < PARTS_MS) return d.parts;
        List<Part> parts = capture(te);
        DRAWN.put(key, new Drawn(te, now, parts));
        return parts;
    }

    private static List<Part> capture(TileEntity te) {
        if (!TileEntityRendererDispatcher.instance.hasSpecialRenderer(te)) return new ArrayList<Part>();
        PARTS.clear();
        SEEN.clear();
        objDepth = compiling = tessIndex = 0;
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glLoadIdentity();
        GL11.glColorMask(false, false, false, false);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_BLEND); // the renderer turns it on for what is not a surface (MachineMesh.tess)
        active = true;
        try {
            TileEntityRendererDispatcher.instance.renderTileEntityAt(te, 0, 0, 0, GunFeelClient.partial);
        } catch (RuntimeException e) {
            GunFeel.LOGGER.debug("Machine renderer could not be read at {} {} {}", te.xCoord, te.yCoord, te.zCoord, e);
        } finally {
            active = false;
            GL11.glPopAttrib();
            GL11.glPopMatrix();
        }
        List<Part> out = new ArrayList<Part>(PARTS);
        PARTS.clear();
        SEEN.clear();
        return out;
    }

    // Dev (MachineCapture): what the tile entity's renderer draws now, read afresh
    static List<Part> drawnNow(TileEntity te) {
        return capture(te);
    }

    // the texture a GL texture id was loaded from ("hbm:textures/models/machines/x.png"), or null (the block atlas,
    // a dynamic texture)
    private static Map<Integer, String> names;

    static String textureName(int id) {
        if (names == null || !names.containsKey(id)) {
            names = new HashMap<Integer, String>();
            Map<?, ?> m = cpw.mods.fml.relauncher.ReflectionHelper.getPrivateValue(net.minecraft.client.renderer.texture.TextureManager.class,
                    net.minecraft.client.Minecraft.getMinecraft().getTextureManager(), "mapTextureObjects", "field_110585_a");
            for (Map.Entry<?, ?> e : m.entrySet())
                if (e.getValue() instanceof net.minecraft.client.renderer.texture.ITextureObject)
                    names.put(((net.minecraft.client.renderer.texture.ITextureObject) e.getValue()).getGlTextureId(), String.valueOf(e.getKey()));
        }
        return names.get(id);
    }

    // the tile entities whose renderer may draw the block at pos: its own, and its machine's core (NTM's dummies)
    static List<TileEntity> owners(World w, int x, int y, int z) {
        List<TileEntity> out = new ArrayList<TileEntity>();
        TileEntity own = w.getTileEntity(x, y, z);
        if (own != null) out.add(own);
        Block b = w.getBlock(x, y, z);
        if (b instanceof BlockDummyable) {
            int[] c = ((BlockDummyable) b).findCore(w, x, y, z);
            if (c != null) {
                TileEntity core = w.getTileEntity(c[0], c[1], c[2]);
                if (core != null && core != own) out.add(core);
            }
        }
        return out;
    }

    // the drawn triangle a bullet at `at` going `dir` meets in or around block pos: nearest along its way, its pixel
    // shown, or clear where that is the machine's glass (MachineGlass: not on a texture listed as empty, not on a
    // triangle clear at every corner, which is light; Hit.glass); null = none (the bullet met the block's hitbox where
    // nothing is drawn)
    static Hit hit(World w, int[] pos, double[] at, double[] dir) {
        boolean glassOn = MachineGlass.on();
        double dl = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
        if (dl < 1e-9) return null;
        double[] d = {dir[0] / dl, dir[1] / dl, dir[2] / dl};
        double back = 1.5;
        double[] o = {at[0] - d[0] * back, at[1] - d[1] * back, at[2] - d[2] * back};
        double best = back + 1.5;
        Hit out = null;
        for (TileEntity te : owners(w, pos[0], pos[1], pos[2])) {
            for (Part p : drawn(te)) {
                int[][] tex = DoorCapture.texture(p.texture, TEXTURES);
                String name = glassOn ? textureName(p.texture) : null;
                boolean glassy = glassOn && !(name != null && MachineGlass.empty(name));
                boolean areas = glassy && MachineGlass.hasAreas(name);
                for (float[] f : faces(p.group)) {
                    int corners = f.length / 5;
                    if (corners < 3) continue;
                    for (int i = 1; i + 1 < corners; i++) {
                        int[] k = {0, i, i + 1};
                        double[] m = new double[9], wv = new double[9];
                        for (int c = 0; c < 3; c++) {
                            int at5 = k[c] * 5;
                            m[c * 3] = f[at5];
                            m[c * 3 + 1] = f[at5 + 1];
                            m[c * 3 + 2] = f[at5 + 2];
                            double[] t = apply(p.mv, f[at5], f[at5 + 1], f[at5 + 2]);
                            wv[c * 3] = t[0] + te.xCoord;
                            wv[c * 3 + 1] = t[1] + te.yCoord;
                            wv[c * 3 + 2] = t[2] + te.zCoord;
                        }
                        double[] bt = ray(o, d, wv);
                        if (bt == null || bt[0] >= best) continue;
                        double hx = o[0] + d[0] * bt[0], hy = o[1] + d[1] * bt[0], hz = o[2] + d[2] * bt[0];
                        if (hx < pos[0] - REACH || hx > pos[0] + 1 + REACH || hy < pos[1] - REACH || hy > pos[1] + 1 + REACH || hz < pos[2] - REACH
                                || hz > pos[2] + 1 + REACH) continue;
                        double wa = 1 - bt[1] - bt[2], wb = bt[1], wc = bt[2];
                        double u = wa * f[k[0] * 5 + 3] + wb * f[k[1] * 5 + 3] + wc * f[k[2] * 5 + 3];
                        double v = wa * f[k[0] * 5 + 4] + wb * f[k[1] * 5 + 4] + wc * f[k[2] * 5 + 4];
                        boolean clear = !Double.isNaN(u) && !Double.isNaN(v) && !DoorCapture.shown(tex, u, v);
                        // a clear pixel in an area listed as empty: air
                        if (clear && areas && MachineGlass.emptyAt(name, u, v, tex[0][0], tex[0][1])) continue;
                        if (clear && !(glassy && !(!DoorCapture.shown(tex, f[k[0] * 5 + 3], f[k[0] * 5 + 4]) && !DoorCapture.shown(tex, f[k[1] * 5 + 3], f[k[1] * 5 + 4])
                                && !DoorCapture.shown(tex, f[k[2] * 5 + 3], f[k[2] * 5 + 4])))) continue;
                        double[] wn = normal(wv);
                        double[] ln = normal(m);
                        if (wn == null || ln == null) continue;
                        // turned to the bullet: the side it came from
                        if (wn[0] * d[0] + wn[1] * d[1] + wn[2] * d[2] > 0) {
                            wn = new double[] {-wn[0], -wn[1], -wn[2]};
                            ln = new double[] {-ln[0], -ln[1], -ln[2]};
                        }
                        double[] local = {wa * m[0] + wb * m[3] + wc * m[6], wa * m[1] + wb * m[4] + wc * m[7], wa * m[2] + wb * m[5] + wc * m[8]};
                        double[] tangent = tangent(ln);
                        best = bt[0];
                        out = new Hit(new double[] {hx, hy, hz}, wn, new Anchor(w, te.xCoord, te.yCoord, te.zCoord, p, local, ln, tangent), p, m);
                        out.glass = clear;
                    }
                }
            }
        }
        return out;
    }

    // The hole of half-size `half` (world units) laid over the part's surface around the hit (26.2: connectedPieces,
    // DecalSurface): the part's triangles joined to the hit one by their edges, unfolded into the hole's plane and cut
    // to its square. Each piece: its unit normal in model space (3), then its corners {x, y, z, a, b}: the point in
    // model space and where it falls on the hole's square (0..1 each). Empty: none (the hole is then drawn square).
    static List<float[]> wrap(Hit hit, double half) {
        List<float[]> out = new ArrayList<float[]>();
        Anchor a = hit.anchor;
        // the part's model space is the world's scaled: the hole's size there
        float[] mv = hit.part.mv;
        double scale = Math.sqrt(mv[0] * mv[0] + mv[1] * mv[1] + mv[2] * mv[2]);
        if (!(scale > 1e-9)) return out;
        double h = half / scale, reach = h * 1.5;
        List<DecalSurface.Face> faces = new ArrayList<DecalSurface.Face>();
        faces.add(face(hit.triangle));
        for (float[] f : faces(hit.part.group)) {
            int corners = f.length / 5;
            for (int i = 1; i + 1 < corners; i++) {
                double[] t = new double[9];
                int[] k = {0, i, i + 1};
                boolean near = true;
                for (int c = 0; c < 3; c++) for (int ax = 0; ax < 3; ax++) t[c * 3 + ax] = f[k[c] * 5 + ax];
                for (int ax = 0; ax < 3 && near; ax++) {
                    double lo = Math.min(t[ax], Math.min(t[3 + ax], t[6 + ax])), hi = Math.max(t[ax], Math.max(t[3 + ax], t[6 + ax]));
                    near = lo <= a.local[ax] + reach && hi >= a.local[ax] - reach;
                }
                if (!near || java.util.Arrays.equals(t, hit.triangle)) continue;
                faces.add(face(t));
            }
        }
        org.joml.Vector3d origin = new org.joml.Vector3d(a.local[0], a.local[1], a.local[2]);
        org.joml.Vector3d n = new org.joml.Vector3d(a.normal[0], a.normal[1], a.normal[2]);
        org.joml.Vector3d u = new org.joml.Vector3d(a.tangent[0], a.tangent[1], a.tangent[2]);
        org.joml.Vector3d v = new org.joml.Vector3d(n).cross(u);
        DecalSurface.Frame[] frames = DecalSurface.unfold(faces, 0, new DecalSurface.Frame(origin, u, v, n), h);
        for (int i = 0; i < faces.size(); i++) {
            DecalSurface.Frame fr = frames[i];
            if (fr == null) continue;
            List<double[]> poly = new ArrayList<double[]>();
            for (org.joml.Vector3d p : faces.get(i).points) poly.add(new double[] {fr.x(p), fr.y(p), p.x, p.y, p.z});
            poly = cut(cut(cut(cut(poly, 0, -h, true), 0, h, false), 1, -h, true), 1, h, false);
            if (poly.size() < 3) continue;
            // the piece's normal turned to the side the hole's own face looks
            org.joml.Vector3d pn = new org.joml.Vector3d(faces.get(i).normal);
            if (pn.dot(fr.normal()) < 0) pn.negate();
            float[] piece = new float[3 + poly.size() * 5];
            piece[0] = (float) pn.x;
            piece[1] = (float) pn.y;
            piece[2] = (float) pn.z;
            for (int c = 0; c < poly.size(); c++) {
                double[] q = poly.get(c);
                piece[3 + c * 5] = (float) q[2];
                piece[3 + c * 5 + 1] = (float) q[3];
                piece[3 + c * 5 + 2] = (float) q[4];
                piece[3 + c * 5 + 3] = (float) ((q[0] + h) / (2 * h));
                piece[3 + c * 5 + 4] = (float) ((q[1] + h) / (2 * h));
            }
            out.add(piece);
        }
        return out;
    }

    private static DecalSurface.Face face(double[] t) {
        List<org.joml.Vector3d> points = new ArrayList<org.joml.Vector3d>(3);
        for (int c = 0; c < 3; c++) points.add(new org.joml.Vector3d(t[c * 3], t[c * 3 + 1], t[c * 3 + 2]));
        double[] n = normal(t);
        return new DecalSurface.Face(points, n == null ? new org.joml.Vector3d() : new org.joml.Vector3d(n[0], n[1], n[2]));
    }

    // the polygon's part on one side of the line where coordinate `axis` is `at` (keep: low = the side above it)
    // (Sutherland-Hodgman; corners lerped in all their numbers)
    static List<double[]> cut(List<double[]> poly, int axis, double at, boolean low) {
        List<double[]> out = new ArrayList<double[]>();
        for (int i = 0; i < poly.size(); i++) {
            double[] p = poly.get(i), q = poly.get((i + 1) % poly.size());
            boolean pin = low ? p[axis] >= at : p[axis] <= at, qin = low ? q[axis] >= at : q[axis] <= at;
            if (pin) out.add(p);
            if (pin != qin) {
                double t = (at - p[axis]) / (q[axis] - p[axis]);
                double[] m = new double[p.length];
                for (int k = 0; k < p.length; k++) m[k] = p[k] + (q[k] - p[k]) * t;
                out.add(m);
            }
        }
        return out;
    }

    // the wrapped pieces of a hole where its part is now, in the world: each {unit normal (3), then corners {x, y, z,
    // a, b}}; null = not drawn now
    static List<double[]> placePieces(Anchor a, List<float[]> pieces) {
        TileEntity te = a.world.getTileEntity(a.x, a.y, a.z);
        if (te == null) return null;
        for (Part p : drawn(te)) {
            if (!p.same(a)) continue;
            List<double[]> out = new ArrayList<double[]>(pieces.size());
            for (float[] pc : pieces) {
                double[] n = unit(turn(p.mv, new double[] {pc[0], pc[1], pc[2]}));
                if (n == null) continue;
                double[] w = new double[pc.length];
                w[0] = n[0];
                w[1] = n[1];
                w[2] = n[2];
                for (int c = 3; c + 4 < pc.length; c += 5) {
                    double[] q = apply(p.mv, pc[c], pc[c + 1], pc[c + 2]);
                    w[c] = q[0] + a.x;
                    w[c + 1] = q[1] + a.y;
                    w[c + 2] = q[2] + a.z;
                    w[c + 3] = pc[c + 3];
                    w[c + 4] = pc[c + 4];
                }
                out.add(w);
            }
            return out;
        }
        return null;
    }

    // where the anchor's hole is now: {point, unit normal, unit texture-u axis, unit texture-v axis} in the world;
    // null = its tile entity or its part is not drawn now (the hole is not drawn, and goes when the tile entity does)
    static double[][] place(Anchor a) {
        TileEntity te = a.world.getTileEntity(a.x, a.y, a.z);
        if (te == null) return null;
        for (Part p : drawn(te)) {
            if (!p.same(a)) continue;
            double[] c = apply(p.mv, a.local[0], a.local[1], a.local[2]);
            double[] pt = {c[0] + a.x, c[1] + a.y, c[2] + a.z};
            double[] n = unit(turn(p.mv, a.normal));
            double[] u = turn(p.mv, a.tangent);
            if (n == null || u == null) return null;
            double dot = u[0] * n[0] + u[1] * n[1] + u[2] * n[2];
            u = unit(new double[] {u[0] - n[0] * dot, u[1] - n[1] * dot, u[2] - n[2] * dot});
            if (u == null) return null;
            double[] v = {n[1] * u[2] - n[2] * u[1], n[2] * u[0] - n[0] * u[2], n[0] * u[1] - n[1] * u[0]};
            return new double[][] {pt, n, u, v};
        }
        return null;
    }

    // how the part a hole is on was drawn: "obj", "model" (ModelRenderer) or "tess" (the Tessellator)
    static String kind(Anchor a) {
        return a.model == TESS ? "tess" : a.model instanceof net.minecraft.client.model.ModelRenderer ? "model" : "obj";
    }

    // the tile entity at the anchor is gone (broken, replaced)
    static boolean gone(Anchor a) {
        return a.world.getTileEntity(a.x, a.y, a.z) == null;
    }

    // ---- maths

    // a column-major GL matrix on a point
    static double[] apply(float[] m, double x, double y, double z) {
        return new double[] {m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14]};
    }

    // ... on a direction (normals: the model's matrices turn and scale evenly)
    static double[] turn(float[] m, double[] v) {
        return new double[] {m[0] * v[0] + m[4] * v[1] + m[8] * v[2], m[1] * v[0] + m[5] * v[1] + m[9] * v[2], m[2] * v[0] + m[6] * v[1] + m[10] * v[2]};
    }

    static double[] unit(double[] v) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return l < 1e-12 ? null : new double[] {v[0] / l, v[1] / l, v[2] / l};
    }

    // a triangle's unit normal (corners one after another), null when it has no area
    static double[] normal(double[] p) {
        double ux = p[3] - p[0], uy = p[4] - p[1], uz = p[5] - p[2];
        double vx = p[6] - p[0], vy = p[7] - p[1], vz = p[8] - p[2];
        return unit(new double[] {uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx});
    }

    // a unit axis across the normal: along the world axis least like it, flattened onto the plane
    static double[] tangent(double[] n) {
        double ax = Math.abs(n[0]), ay = Math.abs(n[1]), az = Math.abs(n[2]);
        double[] a = ax <= ay && ax <= az ? new double[] {1, 0, 0} : ay <= az ? new double[] {0, 1, 0} : new double[] {0, 0, 1};
        double dot = a[0] * n[0] + a[1] * n[1] + a[2] * n[2];
        return unit(new double[] {a[0] - n[0] * dot, a[1] - n[1] * dot, a[2] - n[2] * dot});
    }

    // where the line from o along unit d meets the triangle (both sides): {distance, b, c} with the point
    // (1 - b - c) p0 + b p1 + c p2; null = it does not (Moller-Trumbore)
    static double[] ray(double[] o, double[] d, double[] p) {
        double e1x = p[3] - p[0], e1y = p[4] - p[1], e1z = p[5] - p[2];
        double e2x = p[6] - p[0], e2y = p[7] - p[1], e2z = p[8] - p[2];
        double hx = d[1] * e2z - d[2] * e2y, hy = d[2] * e2x - d[0] * e2z, hz = d[0] * e2y - d[1] * e2x;
        double a = e1x * hx + e1y * hy + e1z * hz;
        if (Math.abs(a) < 1e-12) return null;
        double f = 1.0 / a;
        double sx = o[0] - p[0], sy = o[1] - p[1], sz = o[2] - p[2];
        double b = f * (sx * hx + sy * hy + sz * hz);
        if (b < 0 || b > 1) return null;
        double qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
        double c = f * (d[0] * qx + d[1] * qy + d[2] * qz);
        if (c < 0 || b + c > 1) return null;
        double t = f * (e2x * qx + e2y * qy + e2z * qz);
        return t < 0 ? null : new double[] {t, b, c};
    }
}
