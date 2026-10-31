package acup.gunfeel;

import java.util.Map;

import com.hbm.blocks.BlockDummyable;

import net.minecraft.block.Block;
import net.minecraft.world.IBlockAccess;

// port: 26.2's Structures for NTM 1.7.10's machines (BlockDummyable, but the big doors: DoorCells). Each block of a
// machine is met by the cells its drawn model fills there, not by NTM's box: through the gaps between its parts, as
// 26.2. The cells come from what the machine's renderer draws (its OBJ models, ModelRenderer parts, Tessellator draws:
// MachineMesh, in ShapesScenario), each facing drawn as it stands, kept in ModelCells' table under
//   <machine id>#machine/<core's meta>/<dx>,<dy>,<dz>
// with the block's place from the core. Its glass (clear pixels of its model, MachineGlass) under the same key with
// "/glass". A machine the table does not have (no renderer, nothing drawn) keeps NTM's box.
public final class MachineCells {
    private MachineCells() {
    }

    static String key(String id, int coreMeta, int dx, int dy, int dz) {
        return id + "#machine/" + coreMeta + "/" + dx + "," + dy + "," + dz;
    }

    static String glassKey(String key) {
        return key + "/glass";
    }

    static boolean machine(Block b) {
        return b instanceof BlockDummyable && !(b instanceof com.hbm.blocks.generic.BlockDoorGeneric);
    }

    // the key of the machine's block at (x, y, z), or null (not a machine, no core)
    static String keyAt(IBlockAccess world, Block b, int x, int y, int z) {
        if (!machine(b)) return null;
        int[] core;
        try {
            core = ((BlockDummyable) b).findCore(world, x, y, z);
        } catch (RuntimeException e) {
            return null;
        }
        if (core == null) return null;
        String id = Block.blockRegistry.getNameForObject(b);
        if (id == null) return null;
        return key(id, world.getBlockMetadata(core[0], core[1], core[2]), x - core[0], y - core[1], z - core[2]);
    }

    // the cells of the machine's block with its exact faces (MachineFaces), or null
    static long[] of(Map<String, long[]> table, IBlockAccess world, Block b, int x, int y, int z) {
        String k = keyAt(world, b, x, y, z);
        long[] cells = k == null ? null : table.get(k);
        return cells == null ? null : MachineFaces.with(cells, k);
    }

    // its glass cells (none of them in its cells), or null
    static long[] glass(Map<String, long[]> table, IBlockAccess world, Block b, int x, int y, int z) {
        String k = keyAt(world, b, x, y, z);
        return k == null ? null : table.get(glassKey(k));
    }
}
