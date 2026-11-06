package acup.gunfeel.phys;

// 26.2's net.minecraft.core.BlockPos as far as the water code uses it: an immutable cell (acup.gunfeel.water).
public final class BlockPos {
    private final int x, y, z;

    public BlockPos(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public BlockPos above() {
        return new BlockPos(x, y + 1, z);
    }

    public BlockPos below() {
        return new BlockPos(x, y - 1, z);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BlockPos p && p.x == x && p.y == y && p.z == z;
    }

    @Override
    public int hashCode() {
        return (y + z * 31) * 31 + x;
    }

    @Override
    public String toString() {
        return "BlockPos{x=" + x + ", y=" + y + ", z=" + z + "}";
    }
}
