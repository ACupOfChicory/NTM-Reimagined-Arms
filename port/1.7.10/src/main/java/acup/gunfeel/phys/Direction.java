package acup.gunfeel.phys;

// 26.2's net.minecraft.core.Direction as far as the water code uses it (acup.gunfeel.water). The 3D data values are
// 1.7.10's side numbers too: 0 down, 1 up, 2 north (-z), 3 south (+z), 4 west (-x), 5 east (+x).
public enum Direction {
    DOWN(0, -1, 0), UP(0, 1, 0), NORTH(0, 0, -1), SOUTH(0, 0, 1), WEST(-1, 0, 0), EAST(1, 0, 0);

    private static final Direction[] BY_3D = values();
    private final int x, y, z;

    Direction(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static Direction from3DDataValue(int v) {
        return BY_3D[Math.abs(v % BY_3D.length)];
    }

    public int get3DDataValue() {
        return ordinal();
    }

    public int getStepX() {
        return x;
    }

    public int getStepY() {
        return y;
    }

    public int getStepZ() {
        return z;
    }

    public Direction getOpposite() {
        return BY_3D[ordinal() ^ 1];
    }
}
