package acup.gunfeel;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.hbm.blocks.BlockDummyable;
import com.hbm.render.loader.HFRWavefrontObject;
import com.hbm.render.loader.Face;
import com.hbm.render.loader.GroupObject;
import com.hbm.render.loader.Vertex;
import com.hbm.tileentity.TileEntityDoorGeneric;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

// Dev (ShapesScenario): the cells of NTM 1.7.10's big doors (DoorCells), made from what RenderDoorGeneric draws. The door's
// renderer runs with the modelview at the core's corner; each part of an NTM model it draws (MixinWavefrontObject,
// MixinWavefrontObjectVBO) is taken with the GL matrix and the bound texture then. Its faces fill the 16^3 cells of the
// blocks they cross where their pixels pass the alpha test, kept per block in the model's frame (the door's turn
// undone). Not seen: clip planes some doors cut their parts with (the cells are then a little fuller than drawn),
// doors drawn without NTM's OBJ or Collada models: those keep NTM's boxes.
// port: NTM CE's sliding blast doors (BlockSlidingBlastDoor: _legacy, _2 with its two keypad blocks) are not
// BlockDoorGeneric: RenderSlidingBlastDoorLegacy draws their Collada model (com.hbm.animloader, door0/door0_1) as
// display lists, its leaves moved by the animation's transforms on the GL matrix. The lists' triangles are taken by
// loading the same models again (MixinTessellator, each draw into the list GL_LIST_INDEX names) and matched to the
// loaded models' lists node by node; each list called while a door is drawn (MixinCallList) is a part.
public final class DoorCapture {
    private DoorCapture() {
    }

    private static boolean active;
    private static final List<Part> PARTS = new ArrayList<Part>();
    private static final FloatBuffer MV = BufferUtils.createFloatBuffer(16);

    private static final class Part {
        final HFRWavefrontObject model;
        final List<GroupObject> groups;
        // or a display list's triangles: x, y, z, u, v a corner
        final float[] tris;
        final float[] mv;
        final int texture;

        Part(HFRWavefrontObject model, List<GroupObject> groups, float[] tris, float[] mv, int texture) {
            this.model = model;
            this.groups = groups;
            this.tris = tris;
            this.mv = mv;
            this.texture = texture;
        }
    }

    // ---- the Collada models' display lists (NTM CE's sliding blast doors)

    private static boolean loading;
    // the triangles of the lists drawn into while loading, by list
    private static final Map<Integer, List<float[]>> LOADED = new HashMap<Integer, List<float[]>>();
    // the triangles of NTM's own lists, by list
    private static final Map<Integer, float[]> LISTS = new HashMap<Integer, float[]>();
    private static boolean listsMade;

    public static boolean loading() {
        return loading;
    }

    // a draw while loading (MixinTessellator): raw vertices of 8 ints (x, y, z, u, v float bits), in the list compiled
    public static void tess(int[] raw, int count, int mode) {
        int list = GL11.glGetInteger(GL11.GL_LIST_INDEX);
        if (list == 0) return;
        int[][] corners;
        if (mode == GL11.GL_TRIANGLES) {
            corners = new int[count / 3][];
            for (int i = 0; i < corners.length; i++) corners[i] = new int[] {i * 3, i * 3 + 1, i * 3 + 2};
        } else if (mode == GL11.GL_QUADS) {
            corners = new int[count / 4 * 2][];
            for (int i = 0; i < count / 4; i++) {
                corners[i * 2] = new int[] {i * 4, i * 4 + 1, i * 4 + 2};
                corners[i * 2 + 1] = new int[] {i * 4, i * 4 + 2, i * 4 + 3};
            }
        } else {
            return;
        }
        float[] t = new float[corners.length * 15];
        for (int i = 0; i < corners.length; i++) for (int c = 0; c < 3; c++) for (int k = 0; k < 5; k++)
            t[i * 15 + c * 5 + k] = Float.intBitsToFloat(raw[corners[i][c] * 8 + k]);
        List<float[]> l = LOADED.get(list);
        if (l == null) LOADED.put(list, l = new ArrayList<float[]>());
        l.add(t);
    }

    // a list called while a door is drawn (MixinCallList)
    public static void list(int list) {
        float[] t = LISTS.get(list);
        if (t == null) return;
        MV.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MV);
        float[] mv = new float[16];
        MV.get(mv);
        PARTS.add(new Part(null, null, t, mv, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)));
    }

    // a display list of NTM's Collada models as a part for the holes on it (MachineMesh): its faces, each 3 corners of
    // x, y, z, u, v; one object a list
    public static final class ListGroup {
        final int list;
        final List<float[]> faces;

        ListGroup(int list, List<float[]> faces) {
            this.list = list;
            this.faces = faces;
        }
    }

    private static final Map<Integer, ListGroup> GROUPS = new HashMap<Integer, ListGroup>();

    // the part for a list called, or null (not one of the Collada models' lists, or those not made yet: makeLists)
    static ListGroup group(int list) {
        ListGroup g = GROUPS.get(list);
        if (g != null) return g;
        float[] t = LISTS.get(list);
        if (t == null) return null;
        List<float[]> faces = new ArrayList<float[]>();
        for (int i = 0; i + 15 <= t.length; i += 15) faces.add(java.util.Arrays.copyOfRange(t, i, i + 15));
        GROUPS.put(list, g = new ListGroup(list, java.util.Collections.unmodifiableList(faces)));
        return g;
    }

    // NTM's door models loaded again, their lists' triangles matched to the loaded models' lists; once
    static void makeLists() {
        if (listsMade) return;
        listsMade = true;
        Object[][] models = {{com.hbm.main.ResourceManager.door0, "models/anim/door0.dae"}, {com.hbm.main.ResourceManager.door0_1, "models/anim/door0_1.dae"}};
        for (Object[] m : models) {
            if (m[0] == null) continue;
            com.hbm.animloader.AnimatedModel again;
            LOADED.clear();
            loading = true;
            try {
                again = com.hbm.animloader.ColladaLoader.load(new net.minecraft.util.ResourceLocation("hbm", (String) m[1]));
            } catch (Throwable e) {
                GunFeel.LOGGER.warn("Door capture: {} could not be loaded again: {}", m[1], e.toString());
                continue;
            } finally {
                loading = false;
            }
            int[] n = {0, 0};
            match((com.hbm.animloader.AnimatedModel) m[0], again, n);
            GunFeel.LOGGER.info("Door capture: {}: {} lists matched, {} triangles", m[1], n[0], n[1]);
        }
        LOADED.clear();
    }

    private static void match(com.hbm.animloader.AnimatedModel mine, com.hbm.animloader.AnimatedModel again, int[] n) {
        if (mine == null || again == null) return;
        try {
            java.lang.reflect.Field geo = com.hbm.animloader.AnimatedModel.class.getDeclaredField("hasGeometry");
            java.lang.reflect.Field list = com.hbm.animloader.AnimatedModel.class.getDeclaredField("callList");
            geo.setAccessible(true);
            list.setAccessible(true);
            if (geo.getBoolean(mine) && geo.getBoolean(again) && String.valueOf(mine.name).equals(String.valueOf(again.name))) {
                int fresh = list.getInt(again);
                List<float[]> tris = LOADED.get(fresh);
                if (tris != null) {
                    int size = 0;
                    for (float[] t : tris) size += t.length;
                    float[] all = new float[size];
                    int at = 0;
                    for (float[] t : tris) {
                        System.arraycopy(t, 0, all, at, t.length);
                        at += t.length;
                    }
                    LISTS.put(list.getInt(mine), all);
                    n[0]++;
                    n[1] += size / 15;
                }
                GL11.glDeleteLists(fresh, 1);
            }
        } catch (ReflectiveOperationException e) {
            GunFeel.LOGGER.warn("Door capture: Collada model not read: {}", e.toString());
            return;
        }
        int k = Math.min(mine.children.size(), again.children.size());
        for (int i = 0; i < k; i++) match(mine.children.get(i), again.children.get(i), n);
    }

    public static boolean active() {
        return active;
    }

    // a part of an NTM model is drawn: mode 0 all groups, 1 only the named, 2 all but the named
    public static void parts(HFRWavefrontObject model, int mode, String... names) {
        if (!active || model == null) return;
        List<GroupObject> groups = new ArrayList<GroupObject>();
        for (GroupObject g : model.groupObjects) {
            boolean named = false;
            for (String n : names) named |= n.equalsIgnoreCase(g.name);
            if (mode == 0 || mode == 1 && named || mode == 2 && !named) groups.add(g);
        }
        MV.clear();
        GL11.glGetFloat(GL11.GL_MODELVIEW_MATRIX, MV);
        float[] mv = new float[16];
        MV.get(mv);
        PARTS.add(new Part(model, groups, null, mv, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)));
    }

    // the door whose core is at (x, y, z), shut or open: its blocks' cells by key (DoorCells.key), in the model's frame;
    // null when nothing of NTM's models was drawn
    // the last door drawn's triangles in the model's frame (MachineFaces), null when none
    static MachineFaces.Model lastDoor;

    static Map<String, long[]> capture(Minecraft mc, int x, int y, int z, boolean open, Map<Integer, int[][]> textures) {
        lastDoor = null;
        World w = mc.world;
        TileEntity te = Mc.te(w, x, y, z);
        if (DoorCells.state(te) == null) return null;
        Block b = Mc.block(w, x, y, z);
        int quarter = DoorCells.quarter(b, Mc.meta(w, x, y, z) - BlockDummyable.offset);
        if (quarter < 0) return null;
        if (te instanceof TileEntityDoorGeneric) ((TileEntityDoorGeneric) te).currentAnimation = null;
        else makeLists();
        com.hbm.interfaces.IDoor.DoorState state = DoorCells.state(te, open ? com.hbm.interfaces.IDoor.DoorState.OPEN : com.hbm.interfaces.IDoor.DoorState.CLOSED);
        PARTS.clear();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glColorMask(false, false, false, false);
        GL11.glDepthMask(false);
        active = true;
        try {
            TileEntityRendererDispatcher.instance.render(te, 0, 0, 0, 0F);
        } finally {
            active = false;
            GL11.glDepthMask(true);
            GL11.glColorMask(true, true, true, true);
            GL11.glPopMatrix();
            DoorCells.state(te, state);
        }
        if (PARTS.isEmpty()) return null;
        // the cells in the world's frame, by block from the core
        Map<Long, long[]> blocks = new HashMap<Long, long[]>();
        List<Integer> triTex = new ArrayList<Integer>();
        List<double[]> triP = new ArrayList<double[]>(), triUv = new ArrayList<double[]>();
        for (Part p : PARTS) {
            int[][] tex = texture(p.texture, textures);
            int index = MachineCapture.texIndex(p.texture, MachineMesh.textureName(p.texture, true), tex);
            if (p.tris != null) {
                for (int i = 0; i + 15 <= p.tris.length; i += 15) {
                    final double[] tri = new double[9];
                    final double[] u = new double[3], v = new double[3];
                    float[] m = p.mv;
                    for (int c = 0; c < 3; c++) {
                        float vx = p.tris[i + c * 5], vy = p.tris[i + c * 5 + 1], vz = p.tris[i + c * 5 + 2];
                        tri[c * 3] = m[0] * vx + m[4] * vy + m[8] * vz + m[12];
                        tri[c * 3 + 1] = m[1] * vx + m[5] * vy + m[9] * vz + m[13];
                        tri[c * 3 + 2] = m[2] * vx + m[6] * vy + m[10] * vz + m[14];
                        u[c] = p.tris[i + c * 5 + 3];
                        v[c] = p.tris[i + c * 5 + 4];
                    }
                    final int[][] t = tex;
                    fill(blocks, tri, (wa, wb, wc) -> shown(t, wa * u[0] + wb * u[1] + wc * u[2], wa * v[0] + wb * v[1] + wc * v[2]));
                    triTex.add(index);
                    triP.add(tri);
                    triUv.add(new double[] {u[0], v[0], u[1], v[1], u[2], v[2]});
                }
                continue;
            }
            for (GroupObject g : p.groups) {
                for (Face f : g.faces) {
                    if (f.vertices == null || f.vertices.length < 3) continue;
                    for (int i = 1; i + 1 < f.vertices.length; i++) {
                        int[] k = {0, i, i + 1};
                        final double[] tri = new double[9];
                        final double[] u = new double[3], v = new double[3];
                        for (int c = 0; c < 3; c++) {
                            Vertex vx = f.vertices[k[c]];
                            float[] m = p.mv;
                            tri[c * 3] = m[0] * vx.x + m[4] * vx.y + m[8] * vx.z + m[12];
                            tri[c * 3 + 1] = m[1] * vx.x + m[5] * vx.y + m[9] * vx.z + m[13];
                            tri[c * 3 + 2] = m[2] * vx.x + m[6] * vx.y + m[10] * vx.z + m[14];
                            if (f.textureCoordinates != null && f.textureCoordinates.length > k[c]) {
                                u[c] = f.textureCoordinates[k[c]].u;
                                v[c] = f.textureCoordinates[k[c]].v;
                            }
                        }
                        final int[][] t = tex;
                        fill(blocks, tri, (wa, wb, wc) -> shown(t, wa * u[0] + wb * u[1] + wc * u[2], wa * v[0] + wb * v[1] + wc * v[2]));
                        triTex.add(index);
                        triP.add(tri);
                        triUv.add(new double[] {u[0], v[0], u[1], v[1], u[2], v[2]});
                    }
                }
            }
        }
        // its triangles for exact faces (MachineFaces), in the model's frame
        int[] tt = new int[triTex.size()];
        float[] fp = new float[tt.length * 9], fuv = new float[tt.length * 6];
        for (int i = 0; i < tt.length; i++) {
            tt[i] = triTex.get(i);
            for (int k = 0; k < 9; k++) fp[i * 9 + k] = (float) triP.get(i)[k];
            for (int k = 0; k < 6; k++) fuv[i * 6 + k] = (float) triUv.get(i)[k];
        }
        lastDoor = new MachineFaces.Model(tt, fp, fuv).turned(quarter & 3);
        // the door's blocks, each in the model's frame (empty where nothing is drawn: a bullet flies through)
        String id = Mc.name(b);
        Map<String, long[]> out = new TreeMap<String, long[]>();
        for (int[] o : footprint(w, b, x, y, z)) {
            long[] bits = blocks.get(pack(o[0] - x, o[1] - y, o[2] - z));
            if (bits == null) bits = new long[65];
            int[] m = DoorCells.turn(o[0] - x, o[2] - z, -quarter);
            out.put(DoorCells.key(id, open, m[0], o[1] - y, m[1]), DoorCells.rotate(bits, -quarter));
        }
        return out;
    }

    // the blocks of the door whose core is at (x, y, z) (with a sliding blast door's keypad blocks)
    static List<int[]> footprint(World w, Block b, int x, int y, int z) {
        List<int[]> out = new ArrayList<int[]>();
        for (int dx = -8; dx <= 8; dx++) for (int dy = -8; dy <= 8; dy++) for (int dz = -8; dz <= 8; dz++) {
            Block at = Mc.block(w, x + dx, y + dy, z + dz);
            if (at != b && !(at instanceof com.hbm.blocks.machine.BlockSlidingBlastDoor && b instanceof com.hbm.blocks.machine.BlockSlidingBlastDoor)) continue;
            int[] c;
            try {
                c = ((BlockDummyable) at).findCore(w, x + dx, y + dy, z + dz);
            } catch (RuntimeException e) {
                continue;
            }
            if (c != null && c[0] == x && c[1] == y && c[2] == z) out.add(new int[] {x + dx, y + dy, z + dz});
        }
        return out;
    }

    static long pack(int dx, int dy, int dz) {
        return ((long) (dx + 512) << 20) | ((long) (dy + 512) << 10) | (dz + 512);
    }

    // ModelCells.fill over the blocks a triangle crosses: each point goes to the block it lies in
    static void fill(Map<Long, long[]> blocks, double[] p, ModelCells.PixelShown shown) {
        double ux = p[3] - p[0], uy = p[4] - p[1], uz = p[5] - p[2];
        double vx = p[6] - p[0], vy = p[7] - p[1], vz = p[8] - p[2];
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1e-12) return;
        double back = 1e-3 / nl;
        nx *= back;
        ny *= back;
        nz *= back;
        double mx = (p[0] + p[3] + p[6]) / 3, my = (p[1] + p[4] + p[7]) / 3, mz = (p[2] + p[5] + p[8]) / 3;
        double edge = Math.max(Math.sqrt(ux * ux + uy * uy + uz * uz), Math.max(Math.sqrt(vx * vx + vy * vy + vz * vz),
                Math.sqrt((p[6] - p[3]) * (p[6] - p[3]) + (p[7] - p[4]) * (p[7] - p[4]) + (p[8] - p[5]) * (p[8] - p[5]))));
        int n = Math.max(2, (int) Math.ceil(edge * 64));
        final double in = 2e-3;
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= n - i; j++) {
                double wa = (double) i / n, wb = (double) j / n, wc = 1 - wa - wb;
                if (!shown.at(wa, wb, wc)) continue;
                double x = ModelCells.grid(wa * p[0] + wb * p[3] + wc * p[6]), y = ModelCells.grid(wa * p[1] + wb * p[4] + wc * p[7]),
                        z = ModelCells.grid(wa * p[2] + wb * p[5] + wc * p[8]);
                x += (mx - x) * in - nx;
                y += (my - y) * in - ny;
                z += (mz - z) * in - nz;
                int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
                int cx = Math.min(15, (int) Math.floor((x - bx) * 16)), cy = Math.min(15, (int) Math.floor((y - by) * 16)),
                        cz = Math.min(15, (int) Math.floor((z - bz) * 16));
                long key = pack(bx, by, bz);
                long[] bits = blocks.get(key);
                if (bits == null) blocks.put(key, bits = new long[65]);
                int k = cx + 16 * (cy + 16 * cz);
                bits[k >> 6] |= 1L << (k & 63);
            }
        }
    }

    // a texture as the graphics card has it: {width, height}, then its ARGB pixels
    static int[][] texture(int id, Map<Integer, int[][]> cache) {
        int[][] t = cache.get(id);
        if (t != null) return t;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (w <= 0 || h <= 0) {
            t = new int[][] {{0, 0}, {}};
        } else {
            IntBuffer buf = BufferUtils.createIntBuffer(w * h);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
            int[] px = new int[w * h];
            buf.get(px);
            t = new int[][] {{w, h}, px};
        }
        cache.put(id, t);
        return t;
    }

    // the pixel at (u, v), the texture repeated, passes the alpha test (alpha over 0.1); no texture: drawn
    static boolean shown(int[][] t, double u, double v) {
        int w = t[0][0], h = t[0][1];
        if (w == 0) return true;
        int x = Math.floorMod((int) Math.floor(u * w), w), y = Math.floorMod((int) Math.floor(v * h), h);
        return (t[1][x + w * y] >>> 24) > 25;
    }
}
