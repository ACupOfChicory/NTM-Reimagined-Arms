package acup.gunfeel.ai;

/** The eight directions a cell is covered in, clockwise from north (-z): N, NE, E, SE, S, SW, W, NW. */
public final class Dirs {
    private Dirs() {
    }

    public static final int N = 0, NE = 1, E = 2, SE = 3, S = 4, SW = 5, W = 6, NW = 7;
    public static final int[] DX = {0, 1, 1, 1, 0, -1, -1, -1};
    public static final int[] DZ = {-1, -1, 0, 1, 1, 1, 0, -1};
    public static final String[] NAMES = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    public static boolean diagonal(int d) {
        return (d & 1) != 0;
    }

    /** the direction nearest to (dx, dz); (0, 0) gives north */
    public static int of(double dx, double dz) {
        double a = Math.atan2(dx, -dz); // 0 north, +90 east
        int d = (int) Math.round(a / (Math.PI / 4));
        return Math.floorMod(d, 8);
    }

    public static int opposite(int d) {
        return (d + 4) & 7;
    }

    /** the bit of d and of its two neighbours: cover against a threat about d */
    public static int fan(int d) {
        return 1 << d | 1 << ((d + 1) & 7) | 1 << ((d + 7) & 7);
    }
}
