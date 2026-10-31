package acup.gunfeel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.hbm.blocks.BlockDummyable;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

// Dev (ShapesScenario): the cells of an NTM 1.7.10 machine (MachineCells), made from what its renderer draws now
// (MachineMesh: NTM's OBJ models, ModelRenderer parts, Tessellator draws, each with its GL matrix and texture). A
// triangle fills the cells of the blocks it crosses where its pixels show (alpha over 0.1, as 1.7.10's alpha test);
// where they are clear it fills the machine's glass instead (MachineGlass: not on a texture listed as empty, not on a
// triangle clear at every corner, which is light). The cells are kept per block from the core as the machine stands.
final class MachineCapture {
    private MachineCapture() {
    }

    // how far from its core a machine's blocks may lie
    static final int REACH = 16;

    // the machine whose core is at (x, y, z): its blocks' cells by key (MachineCells.key) and its glass by glassKey;
    // null when its renderer draws nothing
    static Map<String, long[]> capture(Minecraft mc, int x, int y, int z, Map<Integer, int[][]> textures) {
        World w = mc.theWorld;
        TileEntity te = w.getTileEntity(x, y, z);
        if (te == null) return null;
        List<MachineMesh.Part> parts = MachineMesh.drawnNow(te);
        if (parts.isEmpty()) return null;
        Map<Long, long[]> solid = new HashMap<Long, long[]>(), glass = new HashMap<Long, long[]>();
        for (MachineMesh.Part p : parts) {
            final int[][] tex = DoorCapture.texture(p.texture, textures);
            String name = MachineMesh.textureName(p.texture);
            boolean empty = name != null && MachineGlass.empty(name);
            for (float[] f : MachineMesh.faces(p.group)) {
                int corners = f.length / 5;
                for (int i = 1; i + 1 < corners; i++) {
                    int[] k = {0, i, i + 1};
                    double[] tri = new double[9];
                    final double[] u = new double[3], v = new double[3];
                    boolean uv = true;
                    for (int c = 0; c < 3; c++) {
                        double[] t = MachineMesh.apply(p.mv, f[k[c] * 5], f[k[c] * 5 + 1], f[k[c] * 5 + 2]);
                        tri[c * 3] = t[0];
                        tri[c * 3 + 1] = t[1];
                        tri[c * 3 + 2] = t[2];
                        u[c] = f[k[c] * 5 + 3];
                        v[c] = f[k[c] * 5 + 4];
                        uv &= !Double.isNaN(u[c]) && !Double.isNaN(v[c]);
                    }
                    if (!uv) {
                        DoorCapture.fill(solid, tri, (wa, wb, wc) -> true);
                        continue;
                    }
                    DoorCapture.fill(solid, tri, (wa, wb, wc) -> DoorCapture.shown(tex, wa * u[0] + wb * u[1] + wc * u[2], wa * v[0] + wb * v[1] + wc * v[2]));
                    boolean light = !DoorCapture.shown(tex, u[0], v[0]) && !DoorCapture.shown(tex, u[1], v[1]) && !DoorCapture.shown(tex, u[2], v[2]);
                    if (empty || light) continue;
                    DoorCapture.fill(glass, tri, (wa, wb, wc) -> !DoorCapture.shown(tex, wa * u[0] + wb * u[1] + wc * u[2], wa * v[0] + wb * v[1] + wc * v[2]));
                }
            }
        }
        Block b = w.getBlock(x, y, z);
        String id = Block.blockRegistry.getNameForObject(b);
        int meta = w.getBlockMetadata(x, y, z);
        Map<String, long[]> out = new TreeMap<String, long[]>();
        for (int[] o : footprint(w, b, x, y, z)) {
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

    // the blocks of the machine whose core is at (x, y, z)
    static List<int[]> footprint(World w, Block b, int x, int y, int z) {
        List<int[]> out = new java.util.ArrayList<int[]>();
        for (int dx = -REACH; dx <= REACH; dx++) for (int dy = -REACH; dy <= REACH; dy++) for (int dz = -REACH; dz <= REACH; dz++) {
            if (w.getBlock(x + dx, y + dy, z + dz) != b) continue;
            int[] c = ((BlockDummyable) b).findCore(w, x + dx, y + dy, z + dz);
            if (c != null && c[0] == x && c[1] == y && c[2] == z) out.add(new int[] {x + dx, y + dy, z + dz});
        }
        return out;
    }
}
