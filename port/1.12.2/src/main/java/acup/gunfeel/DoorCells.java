package acup.gunfeel;

import java.util.Map;

import com.hbm.blocks.BlockDummyable;
import com.hbm.blocks.generic.BlockDoorGeneric;
import com.hbm.tileentity.TileEntityDoorGeneric;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.IBlockAccess;

// port: 26.2's Structures for NTM 1.7.10's big doors (BlockDoorGeneric: the cargo door, the vault door, the sliding
// blast door...). Each block of such a door is met by the cells its drawn model fills there, not by its collision
// box: between a door's leaves, through its windows. The cells come from what the door's renderer draws, shut and
// open (DoorCapture, in ShapesScenario), kept in ModelCells' table under
//   <door id>#door/<closed|open>/<dx>,<dy>,<dz>
// with the block's place from the core and the cells in the model's own frame (the frame RenderDoorGeneric turns by the
// core's facing). A door on its way, or a block the table does not have, keeps NTM's box.
public final class DoorCells {
    private DoorCells() {
    }

    // the cells of a block of a big door at rest, turned as the door stands, or null
    static long[] of(Map<String, long[]> table, IBlockAccess world, Block b, int x, int y, int z) {
        if (!(b instanceof BlockDoorGeneric)) return null;
        int[] core;
        try {
            core = ((BlockDummyable) b).findCore(world, x, y, z);
        } catch (RuntimeException e) {
            return null;
        }
        if (core == null) return null;
        TileEntity te = Mc.te(world, core[0], core[1], core[2]);
        if (!(te instanceof TileEntityDoorGeneric)) return null;
        com.hbm.interfaces.IDoor.DoorState state = ((TileEntityDoorGeneric) te).state;
        if (state != com.hbm.interfaces.IDoor.DoorState.CLOSED && state != com.hbm.interfaces.IDoor.DoorState.OPEN) return null;
        int quarter = quarter(Mc.meta(world, core[0], core[1], core[2]) - BlockDummyable.offset);
        if (quarter < 0) return null;
        int[] o = turn(x - core[0], z - core[2], -quarter);
        String id = Mc.name(b);
        boolean open = state == com.hbm.interfaces.IDoor.DoorState.OPEN;
        long[] model = table.get(key(id, open, o[0], y - core[1], o[1]));
        // with the door's exact faces (MachineFaces), turned as it stands
        return model == null ? null : MachineFaces.door(rotate(model, quarter), id, open, quarter, x - core[0], y - core[1], z - core[2]);
    }

    static String key(String id, boolean open, int dx, int dy, int dz) {
        return id + "#door/" + (open ? "open" : "closed") + "/" + dx + "," + dy + "," + dz;
    }

    // RenderDoorGeneric's turn for the core's facing (its meta less BlockDummyable.offset), in quarters of
    // glRotatef(90, 0, 1, 0); -1 for a facing it does not turn to
    static int quarter(int dir) {
        switch (dir) {
            case 5: return 0;
            case 2: return 1;
            case 4: return 2;
            case 3: return 3;
            default: return -1;
        }
    }

    // (x, z) turned by quarters of glRotatef(90, 0, 1, 0): x' = x cos + z sin, z' = -x sin + z cos
    static int[] turn(int x, int z, int quarters) {
        int q = ((quarters % 4) + 4) % 4;
        for (int i = 0; i < q; i++) {
            int nx = z, nz = -x;
            x = nx;
            z = nz;
        }
        return new int[] {x, z};
    }

    // the cells turned about the block's vertical middle the same way
    static long[] rotate(long[] bits, int quarters) {
        int q = ((quarters % 4) + 4) % 4;
        if (q == 0) return bits;
        long[] out = new long[bits.length];
        if (bits.length > 64) out[64] = bits[64];
        for (int k = 0; k < 4096; k++) {
            if ((bits[k >> 6] >>> (k & 63) & 1L) == 0) continue;
            int cx = k & 15, cy = k >> 4 & 15, cz = k >> 8;
            // doubled cell centres about the block's middle: 2c + 1 - 16, odd numbers from -15 to 15
            int[] t = turn(2 * cx - 15, 2 * cz - 15, q);
            int nx = (t[0] + 15) / 2, nz = (t[1] + 15) / 2;
            int n = nx + 16 * (cy + 16 * nz);
            out[n >> 6] |= 1L << (n & 63);
        }
        return out;
    }
}
