package acup.gunfeel.ai;

import java.util.Arrays;

/**
 * One chunk section (16 x 16 x 16) as {@link Voxel} bytes, copied on the server thread and never changed after:
 * the background threads read it while the world goes on changing. Index as Minecraft's: (y &lt;&lt; 8) | (z &lt;&lt; 4) | x.
 */
public final class SectionSnapshot {
    public static final int SIZE = 4096;
    private static final byte[] AIR = new byte[SIZE];

    public final long key;
    /** counts the section's changes seen by the map; a snapshot keeps the count it was taken at */
    public final int version;
    private final byte[] voxels;
    private final boolean uniform;

    private SectionSnapshot(long key, int version, byte[] voxels, boolean uniform) {
        this.key = key;
        this.version = version;
        this.voxels = voxels;
        this.uniform = uniform;
    }

    /** voxels: 4096 bytes, owned by the snapshot from here on */
    public static SectionSnapshot of(long key, int version, byte[] voxels) {
        if (voxels.length != SIZE) throw new IllegalArgumentException("a section has 4096 blocks, not " + voxels.length);
        byte first = voxels[0];
        for (int i = 1; i < SIZE; i++) if (voxels[i] != first) return new SectionSnapshot(key, version, voxels, false);
        return filled(key, version, first);
    }

    public static SectionSnapshot air(long key, int version) {
        return new SectionSnapshot(key, version, AIR, true);
    }

    public static SectionSnapshot filled(long key, int version, byte v) {
        if (v == 0) return air(key, version);
        byte[] one = new byte[SIZE];
        Arrays.fill(one, v);
        return new SectionSnapshot(key, version, one, true);
    }

    public byte at(int x, int y, int z) {
        return voxels[(y << 8) | (z << 4) | x];
    }

    public byte at(int index) {
        return voxels[index];
    }

    /** every block the same (all air, all stone) */
    public boolean uniform() {
        return uniform;
    }

    public boolean allAir() {
        return uniform && voxels[0] == 0;
    }
}
