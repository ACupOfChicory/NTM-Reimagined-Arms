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
        return b instanceof BlockDummyable && !DoorCells.door(b);
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
        String id = Mc.name(b);
        if (id == null) return null;
        return key(id, Mc.meta(world, core[0], core[1], core[2]), x - core[0], layer(b, y - core[1]), z - core[2]);
    }

    static boolean elevator(Block b) {
        return b instanceof com.hbm.blocks.machine.BlockCargoElevator;
    }

    // 26.2's Structures.layer: the elevator's sections above its base are all alike, one layer stands for them
    static int layer(Block b, int dy) {
        return elevator(b) ? Math.min(dy, 1) : dy;
    }

    // what the bullet meets in this block besides its cells: the cargo elevator's platform and piston where they are
    // now (ElevatorMesh, 26.2's Structures.solid), or null
    static BlockRay.Hit solid(IBlockAccess world, Block b, int x, int y, int z, double fx, double fy, double fz, double tx, double ty, double tz) {
        if (!elevator(b) || !ElevatorMesh.available()) return null;
        int[] core;
        try {
            core = ((BlockDummyable) b).findCore(world, x, y, z);
        } catch (RuntimeException e) {
            return null;
        }
        if (core == null) return null;
        net.minecraft.tileentity.TileEntity te = Mc.te(world, core[0], core[1], core[2]);
        if (!(te instanceof com.hbm.tileentity.machine.TileEntityCargoElevator)) return null;
        com.hbm.tileentity.machine.TileEntityCargoElevator elevator = (com.hbm.tileentity.machine.TileEntityCargoElevator) te;
        return ElevatorMesh.hit(core[0], core[1], core[2], elevator.extension, x, y, z, fx, fy, fz, tx, ty, tz);
    }

    // a machine met with no cells, or with cells from another NTM version: told to the client, which takes it
    // (MachineScan: also those without a tile entity, which its look round does not find, as NTM's rails)
    interface Wanted {
        void at(Block b, int x, int y, int z);
    }

    static volatile Wanted wanted;
    // dev (ScanScenario): every machine met is told
    static volatile boolean wantAll;

    // the cells of the machine's block with its exact faces (MachineFaces), or null
    static long[] of(Map<String, long[]> table, IBlockAccess world, Block b, int x, int y, int z) {
        String k = keyAt(world, b, x, y, z);
        long[] cells = k == null ? null : table.get(k);
        Wanted w = wanted;
        if (w != null && k != null && (cells == null || wantAll || !ModelCells.jarTrusted())) {
            int[] core = ((BlockDummyable) b).findCore(world, x, y, z);
            if (core != null) w.at(b, core[0], core[1], core[2]);
        }
        return cells == null ? null : MachineFaces.with(cells, k);
    }

    // its glass cells (none of them in its cells), or null
    static long[] glass(Map<String, long[]> table, IBlockAccess world, Block b, int x, int y, int z) {
        String k = keyAt(world, b, x, y, z);
        return k == null ? null : table.get(glassKey(k));
    }
}
