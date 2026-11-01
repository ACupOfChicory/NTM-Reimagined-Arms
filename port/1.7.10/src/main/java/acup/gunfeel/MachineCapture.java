package acup.gunfeel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.hbm.blocks.BlockDummyable;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

// Dev (ShapesScenario): the cells of an NTM 1.7.10 machine (MachineCells) and its triangles (MachineFaces), made from
// what its renderer draws now: its tile entity's renderer (MachineMesh: NTM's OBJ models, ModelRenderer parts,
// Tessellator draws, each with its GL matrix and texture), or, when that draws nothing, what its blocks draw into the
// chunk (RenderBlocks: NTM's ISBRH machines, rails, RBMK columns), on the block atlas, each icon its own texture. A
// triangle fills the cells of the blocks it crosses where its pixels show (alpha over 0.1, as 1.7.10's alpha test);
// where they are clear it fills the machine's glass instead (MachineGlass: not on a texture listed as empty, not on a
// triangle clear at every corner, which is light). The cells are kept per block from the core as the machine stands.
final class MachineCapture {
    private MachineCapture() {
    }

    // how far from its core a machine's blocks may lie
    static final int REACH = 16;

    // exact faces (MachineFaces): the textures met so far (by GL id or icon name: index) and the last machine's triangles
    static final List<MachineFaces.Tex> TEXTURES = MachineFaces.TAKEN_TEX;
    private static final Map<Object, Integer> TEX_INDEX = new HashMap<Object, Integer>();
    static MachineFaces.Model last;

    // how the last machine was drawn: "tesr", "chunk" (RenderBlocks) or null
    static String how;

    // (MachineScan's thread, or the game's in ShapesScenario)
    static synchronized int texIndex(Object key, String name, int[][] tex) {
        Integer i = TEX_INDEX.get(key);
        // a texture kept from an earlier game (MachineScan's file) under its name
        if (i == null && name != null) synchronized (TEXTURES) {
            for (int k = 0; k < TEXTURES.size() && i == null; k++) if (name.equals(TEXTURES.get(k).name)) i = k;
        }
        if (i != null) TEX_INDEX.put(key, i);
        if (i == null) synchronized (TEXTURES) {
            i = TEXTURES.size();
            TEXTURES.add(MachineFaces.Tex.of(name, tex));
            TEX_INDEX.put(key, i);
        }
        return i;
    }

    // a machine's triangles as they come, into its model; into its cells and glass a few at a time (step), so a game
    // can spread the work over its frames (MachineScan)
    static final class Collect {
        final Map<Long, long[]> solid = new HashMap<Long, long[]>(), glass = new HashMap<Long, long[]>();
        final List<Integer> tex = new ArrayList<Integer>();
        final List<double[]> p = new ArrayList<double[]>(), uv = new ArrayList<double[]>();
        // blocks to keep besides the machine's (the elevator's section above its base, not placed)
        final List<int[]> extra = new ArrayList<int[]>();

        // each triangle's texture and whether its clear pixels are air, for step
        private final List<int[][]> texOf = new ArrayList<int[][]>();
        private final List<Boolean> emptyOf = new ArrayList<Boolean>();
        // each triangle's texture name (MachineGlass: areas of it listed as empty), null: none
        private final List<String> nameOf = new ArrayList<String>();
        private int filled;

        // tri: corners from the core block's corner; t: the texture ({{w, h}, argb}), null: untextured
        void add(double[] tri, int[][] t, int index, final double[] u, final double[] v, boolean empty) {
            add(tri, t, index, u, v, empty, null);
        }

        void add(double[] tri, int[][] t, int index, final double[] u, final double[] v, boolean empty, String name) {
            nameOf.add(MachineGlass.hasAreas(name) ? name : null);
            tex.add(t == null ? -1 : index);
            p.add(tri);
            uv.add(t == null ? new double[6] : new double[] {u[0], v[0], u[1], v[1], u[2], v[2]});
            texOf.add(t);
            emptyOf.add(empty);
        }

        // fills the cells of the triangles not filled yet until `deadline` (System.nanoTime); true: all are
        boolean step(long deadline) {
            while (filled < p.size()) {
                int i = filled++;
                fill(p.get(i), texOf.get(i), uv.get(i), emptyOf.get(i), nameOf.get(i));
                if ((filled & 7) == 0 && System.nanoTime() > deadline) break;
            }
            return filled >= p.size();
        }

        private void fill(double[] tri, final int[][] t, double[] q, boolean empty, final String areas) {
            if (t == null) {
                DoorCapture.fill(solid, tri, (wa, wb, wc) -> true);
                return;
            }
            final double[] u = {q[0], q[2], q[4]}, v = {q[1], q[3], q[5]};
            DoorCapture.fill(solid, tri, (wa, wb, wc) -> DoorCapture.shown(t, wa * u[0] + wb * u[1] + wc * u[2], wa * v[0] + wb * v[1] + wc * v[2]));
            boolean light = !DoorCapture.shown(t, u[0], v[0]) && !DoorCapture.shown(t, u[1], v[1]) && !DoorCapture.shown(t, u[2], v[2]);
            if (empty || light) return;
            // its clear pixels, but those in an area listed as empty
            DoorCapture.fill(glass, tri, (wa, wb, wc) -> {
                double pu = wa * u[0] + wb * u[1] + wc * u[2], pv = wa * v[0] + wb * v[1] + wc * v[2];
                return !DoorCapture.shown(t, pu, pv) && (areas == null || !MachineGlass.emptyAt(areas, pu, pv, t[0][0], t[0][1]));
            });
        }

        boolean isEmpty() {
            return p.isEmpty();
        }

        int size() {
            return p.size();
        }

        // its triangles, after finish
        MachineFaces.Model model;

        // the machine's cells and glass by key (all its triangles filled: step); its model in `last`
        Map<String, long[]> finish(World w, int x, int y, int z) {
            Block b = w.getBlock(x, y, z);
            Map<String, long[]> out = finish(Block.blockRegistry.getNameForObject(b), w.getBlockMetadata(x, y, z), footprint(w, b, x, y, z), x, y, z);
            last = model;
            return out;
        }

        // the same, the machine's block id, its core's meta and its blocks given: on any thread
        Map<String, long[]> finish(String id, int meta, List<int[]> footprint, int x, int y, int z) {
            step(Long.MAX_VALUE);
            int[] t = new int[tex.size()];
            float[] fp = new float[t.length * 9], fuv = new float[t.length * 6];
            for (int i = 0; i < t.length; i++) {
                t[i] = tex.get(i);
                for (int k = 0; k < 9; k++) fp[i * 9 + k] = (float) p.get(i)[k];
                for (int k = 0; k < 6; k++) fuv[i * 6 + k] = (float) uv.get(i)[k];
            }
            model = new MachineFaces.Model(t, fp, fuv);
            Map<String, long[]> out = new TreeMap<String, long[]>();
            List<int[]> blocks = new ArrayList<int[]>(footprint);
            blocks.addAll(extra);
            for (int[] o : blocks) {
                long at = DoorCapture.pack(o[0] - x, o[1] - y, o[2] - z);
                long[] bits = solid.get(at);
                if (bits == null) bits = new long[65];
                String key = MachineCells.key(id, meta, o[0] - x, o[1] - y, o[2] - z);
                out.put(key, bits);
                long[] g = glass.get(at);
                if (g == null) continue;
                long[] only = new long[65];
                boolean any = false;
                for (int i = 0; i < 64; i++) any |= (only[i] = g[i] & ~bits[i]) != 0L;
                if (any) out.put(MachineCells.glassKey(key), only);
            }
            return out;
        }
    }

    // the machine whose core is at (x, y, z): its blocks' cells by key (MachineCells.key) and its glass by glassKey;
    // null when it draws nothing
    static Map<String, long[]> capture(Minecraft mc, int x, int y, int z, Map<Integer, int[][]> textures, ShapesScenario.Atlas atlas) {
        Collect c = start(mc, x, y, z, textures, () -> atlas);
        return c == null ? null : c.finish(mc.theWorld, x, y, z);
    }

    // the machine's triangles as drawn now, its cells not filled yet (Collect.step), or null when it draws nothing;
    // `how` says how it was drawn. The block atlas is asked for only when its blocks draw it (RenderBlocks)
    static Collect start(Minecraft mc, int x, int y, int z, Map<Integer, int[][]> textures, java.util.function.Supplier<ShapesScenario.Atlas> atlas) {
        how = null;
        Collect out = tesr(mc, x, y, z, textures);
        if (out != null) {
            how = "tesr";
            return out;
        }
        out = chunk(mc, x, y, z, atlas);
        if (out != null) how = "chunk";
        return out;
    }

    private static Collect tesr(Minecraft mc, int x, int y, int z, Map<Integer, int[][]> textures) {
        Drawn d = drawn(mc.theWorld, x, y, z);
        return d == null ? null : d.triangles(textures);
    }

    // what a machine's tile entity renderer drew (the game's thread: GL), to be made into triangles on any thread once
    // its textures are read
    static final class Drawn {
        final List<MachineMesh.Part> parts = new ArrayList<MachineMesh.Part>();
        final List<String> names = new ArrayList<String>();
        final int x, y, z;
        final boolean elevator;

        Drawn(int x, int y, int z, boolean elevator) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.elevator = elevator;
        }

        // the GL textures it was drawn with
        java.util.Set<Integer> textures() {
            java.util.Set<Integer> out = new java.util.LinkedHashSet<Integer>();
            for (MachineMesh.Part p : parts) out.add(p.texture);
            return out;
        }

        // its triangles; `textures` has its textures' pixels (else they are read from GL: the game's thread only)
        Collect triangles(Map<Integer, int[][]> textures) {
            Collect c = new Collect();
            if (elevator) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.extra.add(new int[] {x + dx, y + 1, z + dz});
            for (int pi = 0; pi < parts.size(); pi++) {
                MachineMesh.Part p = parts.get(pi);
                final int[][] tex = textures.containsKey(p.texture) ? textures.get(p.texture) : DoorCapture.texture(p.texture, textures);
                String name = names.get(pi);
                boolean empty = name != null && MachineGlass.empty(name);
                int index = -1;
                for (float[] f : MachineMesh.faces(p.group)) {
                    int corners = f.length / 5;
                    for (int i = 1; i + 1 < corners; i++) {
                        int[] k = {0, i, i + 1};
                        double[] tri = new double[9];
                        double[] u = new double[3], v = new double[3];
                        boolean uv = true;
                        for (int n = 0; n < 3; n++) {
                            double[] t = MachineMesh.apply(p.mv, f[k[n] * 5], f[k[n] * 5 + 1], f[k[n] * 5 + 2]);
                            tri[n * 3] = t[0];
                            tri[n * 3 + 1] = t[1];
                            tri[n * 3 + 2] = t[2];
                            u[n] = f[k[n] * 5 + 3];
                            v[n] = f[k[n] * 5 + 4];
                            uv &= !Double.isNaN(u[n]) && !Double.isNaN(v[n]);
                        }
                        if (uv && index < 0) index = texIndex(p.texture, name, tex);
                        c.add(tri, uv ? tex : null, index, u, v, empty, name);
                    }
                }
            }
            return c;
        }
    }

    // what the machine's tile entity renderer draws now (the game's thread), or null when it draws nothing
    static Drawn drawn(World w, int x, int y, int z) {
        TileEntity te = w.getTileEntity(x, y, z);
        if (te == null) return null;
        List<MachineMesh.Part> parts;
        // the cargo elevator (26.2's Structures): its base and the guides of one section above it, drawn with its
        // platform down; the platform and piston move, bullets meet them where they are (ElevatorMesh)
        boolean elevator = te instanceof com.hbm.tileentity.machine.TileEntityCargoElevator;
        if (elevator) {
            com.hbm.tileentity.machine.TileEntityCargoElevator e = (com.hbm.tileentity.machine.TileEntityCargoElevator) te;
            int height = e.height;
            boolean platform = e.renderPlatform;
            e.height = 1;
            e.renderPlatform = true;
            try {
                parts = MachineMesh.drawnNow(te);
            } finally {
                e.height = height;
                e.renderPlatform = platform;
            }
        } else {
            parts = MachineMesh.drawnNow(te);
        }
        if (parts.isEmpty()) return null;
        Drawn d = new Drawn(x, y, z, elevator);
        for (MachineMesh.Part p : parts) {
            if (elevator && moving(p.group)) continue;
            d.parts.add(p);
            d.names.add(MachineMesh.textureName(p.texture));
        }
        return d;
    }

    // what the machine's blocks draw into the chunk (RenderBlocks), each quad on its icon (found by its UV on the atlas)
    private static Collect chunk(Minecraft mc, int x, int y, int z, java.util.function.Supplier<ShapesScenario.Atlas> atlases) {
        World w = mc.theWorld;
        Block b = w.getBlock(x, y, z);
        RenderBlocks rb = new RenderBlocks(w);
        List<int[]> blocks = footprint(w, b, x, y, z);
        List<float[]> quads = new ArrayList<float[]>();
        for (int[] o : blocks) quads.add(drawBlock(rb, b, o));
        initIcons(mc);
        return chunkTriangles(blocks, quads, x, y, z, atlases);
    }

    // what one of the machine's blocks draws into the chunk (the game's thread), or null
    static float[] drawBlock(RenderBlocks rb, Block b, int[] o) {
        try {
            return ShapesScenario.draw(rb, b, o[0], o[1], o[2]);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // the triangles of what its blocks drew (drawBlock: quads[i] at blocks[i]), on any thread once initIcons has run
    static Collect chunkTriangles(List<int[]> blocks, List<float[]> quads, int x, int y, int z, java.util.function.Supplier<ShapesScenario.Atlas> atlases) {
        Collect c = new Collect();
        ShapesScenario.Atlas atlas = null;
        for (int bi = 0; bi < blocks.size(); bi++) {
            int[] o = blocks.get(bi);
            float[] q = quads.get(bi);
            if (q == null) continue;
            for (int quad = 0; quad < q.length / 20; quad++) {
                double cu = 0, cv = 0;
                for (int n = 0; n < 4; n++) {
                    cu += q[(quad * 4 + n) * 5 + 3] / 4;
                    cv += q[(quad * 4 + n) * 5 + 4] / 4;
                }
                TextureAtlasSprite icon = icon(cu, cv);
                int[][] tex = null;
                int index = -1;
                boolean empty = false;
                String iconName = null;
                double u0 = 0, v0 = 0, du = 1, dv = 1;
                if (icon != null) {
                    u0 = icon.getMinU();
                    v0 = icon.getMinV();
                    du = icon.getMaxU() - u0;
                    dv = icon.getMaxV() - v0;
                    if (atlas == null) atlas = atlases.get();
                    tex = iconPixels(atlas, icon);
                    String name = path(icon.getIconName());
                    empty = MachineGlass.empty(name);
                    iconName = name;
                    index = texIndex("icon:" + icon.getIconName(), name, tex);
                }
                for (int t = 0; t < 2; t++) {
                    int[] k = {0, t == 0 ? 1 : 2, t == 0 ? 2 : 3};
                    double[] tri = new double[9], u = new double[3], v = new double[3];
                    for (int n = 0; n < 3; n++) {
                        int at = (quad * 4 + k[n]) * 5;
                        tri[n * 3] = q[at] + o[0] - x;
                        tri[n * 3 + 1] = q[at + 1] + o[1] - y;
                        tri[n * 3 + 2] = q[at + 2] + o[2] - z;
                        u[n] = (q[at + 3] - u0) / du;
                        v[n] = (q[at + 4] - v0) / dv;
                    }
                    c.add(tri, tex, index, u, v, empty, iconName);
                }
            }
        }
        return c.isEmpty() ? null : c;
    }

    private static volatile List<TextureAtlasSprite> icons;

    // the block atlas' icons, read once (the game's thread)
    static void initIcons(Minecraft mc) {
        if (icons != null) return;
        TextureMap map = (TextureMap) mc.getTextureManager().getTexture(TextureMap.locationBlocksTexture);
        Map<?, ?> m = cpw.mods.fml.relauncher.ReflectionHelper.getPrivateValue(TextureMap.class, map, "mapUploadedSprites", "field_94252_e");
        List<TextureAtlasSprite> out = new ArrayList<TextureAtlasSprite>();
        for (Object s : m.values()) if (s instanceof TextureAtlasSprite) out.add((TextureAtlasSprite) s);
        icons = out;
    }

    // the block atlas' icon at (u, v) (initIcons first)
    private static TextureAtlasSprite icon(double u, double v) {
        for (TextureAtlasSprite s : icons)
            if (u >= s.getMinU() && u <= s.getMaxU() && v >= s.getMinV() && v <= s.getMaxV()) return s;
        return null;
    }

    // the icon's pixels cut out of the atlas
    private static int[][] iconPixels(ShapesScenario.Atlas atlas, TextureAtlasSprite s) {
        int x0 = (int) Math.round(s.getMinU() * atlas.w), y0 = (int) Math.round(s.getMinV() * atlas.h);
        int w = Math.max(1, (int) Math.round(s.getMaxU() * atlas.w) - x0), h = Math.max(1, (int) Math.round(s.getMaxV() * atlas.h) - y0);
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) px[x + w * y] = atlas.argb[Math.min(atlas.w - 1, x0 + x) + atlas.w * Math.min(atlas.h - 1, y0 + y)];
        return new int[][] {{w, h}, px};
    }

    // an icon's name as its texture's path ("hbm:rbmk_blank" -> "hbm:textures/blocks/rbmk_blank.png")
    static String path(String icon) {
        int c = icon.indexOf(':');
        String domain = c < 0 ? "minecraft" : icon.substring(0, c), name = c < 0 ? icon : icon.substring(c + 1);
        return domain + ":textures/blocks/" + name + ".png";
    }

    // the cargo elevator's platform and piston (RenderCargoElevator's parts that move)
    private static boolean moving(Object group) {
        String n = group instanceof com.hbm.render.loader.S_GroupObject ? ((com.hbm.render.loader.S_GroupObject) group).name
                : group instanceof net.minecraftforge.client.model.obj.GroupObject ? ((net.minecraftforge.client.model.obj.GroupObject) group).name : null;
        return "Platform".equals(n) || "Piston".equals(n);
    }

    // the blocks of the machine whose core is at (x, y, z)
    static List<int[]> footprint(World w, Block b, int x, int y, int z) {
        List<int[]> out = new ArrayList<int[]>();
        for (int dx = -REACH; dx <= REACH; dx++) for (int dy = -REACH; dy <= REACH; dy++) for (int dz = -REACH; dz <= REACH; dz++) {
            if (w.getBlock(x + dx, y + dy, z + dz) != b) continue;
            int[] c = ((BlockDummyable) b).findCore(w, x + dx, y + dy, z + dz);
            if (c != null && c[0] == x && c[1] == y && c[2] == z) out.add(new int[] {x + dx, y + dy, z + dz});
        }
        return out;
    }
}
