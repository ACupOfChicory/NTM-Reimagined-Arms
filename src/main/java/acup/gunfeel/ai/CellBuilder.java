package acup.gunfeel.ai;

/**
 * Works out a section's cells from its snapshot and its 26 neighbours' (a cell at the edge looks at the blocks
 * beside, under and over it). Reads nothing but the snapshots, so it runs on the AI's background threads. A missing
 * neighbour (an unloaded chunk) reads as {@link Voxel#UNKNOWN} and marks the cells that looked at it.
 */
public final class CellBuilder {
    /** how far up a roof is looked for, in blocks over the head */
    static final int ROOF_REACH = 4;

    private final SectionSnapshot[] around;
    private boolean unknown;

    /** around: 27 snapshots, index (dy + 1) * 9 + (dz + 1) * 3 + (dx + 1); the section itself is 13 */
    private CellBuilder(SectionSnapshot[] around) {
        this.around = around;
    }

    public static int index(int dx, int dy, int dz) {
        return (dy + 1) * 9 + (dz + 1) * 3 + (dx + 1);
    }

    public static SectionCells build(SectionSnapshot[] around, long key, int stamp) {
        long t0 = System.nanoTime();
        if (around.length != 27) throw new IllegalArgumentException("27 snapshots, not " + around.length);
        CellBuilder b = new CellBuilder(around);
        SectionSnapshot self = around[13];
        SectionSnapshot below = around[index(0, -1, 0)];
        // no cell where there is nothing to stand on: an air section over an air section has none
        boolean empty = self != null && self.allAir() && below != null && below.allAir();
        // nor inside solid stone
        boolean solid = self != null && self.uniform() && !Voxel.open(self.at(0));
        short[] pos = new short[64];
        int[] cover = new int[64];
        byte[] flags = new byte[64];
        short[] colStart = new short[257];
        int n = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                colStart[z << 4 | x] = (short) n;
                if (empty || solid) continue;
                for (int y = 0; y < 16; y++) {
                    b.unknown = false;
                    int feet = b.v(x, y, z);
                    // (a shut door a hand opens is a cell too: AiDoors)
                    if (!Voxel.pass(feet)) continue;
                    int head = b.v(x, y + 1, z);
                    if (!Voxel.pass(head)) continue;
                    int under = b.v(x, y - 1, z);
                    // not on a door's lower half
                    if (Voxel.shutDoor(under)) continue;
                    boolean doorway = Voxel.shutDoor(feet) || Voxel.shutDoor(head);
                    boolean water = (feet & Voxel.WATER) != 0;
                    boolean stand = Voxel.floor(under) || (feet & Voxel.CLIMB) != 0
                            || (water && (head & Voxel.WATER) == 0);
                    if (!stand) continue;
                    if (n == pos.length) {
                        pos = java.util.Arrays.copyOf(pos, n * 2);
                        cover = java.util.Arrays.copyOf(cover, n * 2);
                        flags = java.util.Arrays.copyOf(flags, n * 2);
                    }
                    pos[n] = (short) (y << 8 | z << 4 | x);
                    // a shut door's cell is no place to stand behind anything
                    cover[n] = doorway ? 0 : b.cover(x, y, z);
                    flags[n] = (byte) b.flags(x, y, z, feet);
                    n++;
                }
            }
        }
        colStart[256] = (short) n;
        return new SectionCells(key, stamp, java.util.Arrays.copyOf(pos, n), java.util.Arrays.copyOf(cover, n),
                java.util.Arrays.copyOf(flags, n), colStart, System.nanoTime() - t0);
    }

    /** the voxel at x y z relative to the section's corner, -16..31 on each axis */
    int v(int x, int y, int z) {
        SectionSnapshot s = around[index(x >> 4, y >> 4, z >> 4)];
        if (s == null) {
            unknown = true;
            return Voxel.UNKNOWN;
        }
        return s.at(x & 15, y & 15, z & 15);
    }

    private int cover(int x, int y, int z) {
        int sightFeet = 0, sightHead = 0, hardFeet = 0, hardHead = 0;
        for (int d = 0; d < 8; d++) {
            int dx = Dirs.DX[d], dz = Dirs.DZ[d], bit = 1 << d;
            for (int level = 0; level < 2; level++) {
                int at = v(x + dx, y + level, z + dz);
                boolean sight = Voxel.sight(at), hard = Voxel.hard(at);
                if (Dirs.diagonal(d)) {
                    // a corner covers the diagonal too: both blocks either side of it
                    int a = v(x + dx, y + level, z), b = v(x, y + level, z + dz);
                    sight |= Voxel.sight(a) && Voxel.sight(b);
                    hard |= Voxel.hard(a) && Voxel.hard(b);
                }
                if (level == 0) {
                    if (sight) sightFeet |= bit;
                    if (hard) hardFeet |= bit;
                } else {
                    if (sight) sightHead |= bit;
                    if (hard) hardHead |= bit;
                }
            }
        }
        return sightFeet | sightHead << 8 | hardFeet << 16 | hardHead << 24;
    }

    private int flags(int x, int y, int z, int feet) {
        int f = 0;
        if ((feet & Voxel.WATER) != 0) f |= SectionCells.WATER;
        if ((feet & Voxel.CLIMB) != 0) f |= SectionCells.CLIMB;
        if (Voxel.open(v(x, y + 2, z))) f |= SectionCells.HEAD3;
        for (int up = 2; up < 2 + ROOF_REACH; up++) {
            int at = v(x, y + up, z);
            if (!Voxel.open(at) || Voxel.sight(at)) {
                f |= SectionCells.ROOF;
                break;
            }
        }
        int passable = 0;
        for (int d = 0; d < 8; d += 2) {
            int nx = x + Dirs.DX[d], nz = z + Dirs.DZ[d];
            int nFeet = v(nx, y, nz), nHead = v(nx, y + 1, nz);
            if (((nFeet | nHead) & Voxel.DOOR) != 0) f |= SectionCells.DOOR_NEAR;
            if (Voxel.open(nFeet) && Voxel.open(nHead)) {
                passable++;
                // a drop of two blocks or more beside the cell
                if (Voxel.open(v(nx, y - 1, nz)) && Voxel.open(v(nx, y - 2, nz))
                        && (nFeet & Voxel.WATER) == 0) f |= SectionCells.EDGE;
            }
        }
        if (passable <= 2) f |= SectionCells.NARROW;
        if (unknown) f |= SectionCells.UNKNOWN;
        return f;
    }
}
