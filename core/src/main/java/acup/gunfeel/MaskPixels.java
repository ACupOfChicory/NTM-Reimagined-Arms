package acup.gunfeel;

import org.jspecify.annotations.Nullable;

/**
 * A see-through block's pixel mask as a bullet meets it (PassableClip: glass panes, bars, copper grates): which of the
 * mask's 16 x 16 pixels a hit on a face lands on, and whether the hit lies on the face's own plane. Faces by the
 * game's Direction order (DOWN, UP, NORTH, SOUTH, WEST, EAST); a mask {@code long[4]} per axis (x, y, z) and a last
 * one for any, each 256 bits row by row from the top, the high bit first. The same operations in the same order as
 * 26.2's PassableClip had them.
 */
public final class MaskPixels {
    public static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;

    // a face's axis (x 0, y 1, z 2) and whether it looks the axis' way
    private static final int[] AXIS = {1, 1, 2, 2, 0, 0};
    private static final boolean[] POSITIVE = {false, true, false, true, false, true};

    private MaskPixels() {
    }

    /** the face's axis: x 0, y 1, z 2 */
    public static int axis(int face) {
        return AXIS[face];
    }

    /** the mask a hit on this face goes by: its axis' own, else the one for any; null: none, the block is solid there */
    public static long @Nullable [] side(long[][] mask, int face) {
        int axis = AXIS[face];
        return mask[axis] != null ? mask[axis] : mask[3];
    }

    /**
     * the pixel a hit at ({@code lx}, {@code ly}, {@code lz}) inside its block (0..1) on {@code face} lands on is set;
     * {@code fullCube}: the block's collision shape is a whole block (a copper grate), which shows the texture upright
     * and unmirrored on each of its six faces
     */
    public static boolean shown(long[] bits, int face, double lx, double ly, double lz, boolean fullCube) {
        int axis = AXIS[face];
        // the texture across the face: u along it, v up it (top and bottom faces: x and z)
        double u = axis == 0 ? lz : lx;
        double v = axis == 1 ? lz : 1.0 - ly;
        if (fullCube) {
            switch (face) {
                case EAST -> u = 1.0 - lz;
                case NORTH -> u = 1.0 - lx;
                case DOWN -> v = 1.0 - lz;
                default -> { }
            }
        }
        int col = Math.clamp((int) Math.floor(u * 16.0), 0, 15), row = Math.clamp((int) Math.floor(v * 16.0), 0, 15);
        int bit = row * 16 + col;
        return (bits[bit >> 6] >>> (63 - (bit & 63)) & 1L) != 0L;
    }

    /** a hit at ({@code lx}, {@code ly}, {@code lz}) inside its block lies on {@code face}'s own plane, within 1e-5 (NaN: it does) */
    public static boolean onFace(int face, double lx, double ly, double lz) {
        double at = switch (AXIS[face]) {
            case 0 -> lx;
            case 1 -> ly;
            default -> lz;
        };
        double edge = POSITIVE[face] ? 1.0 : 0.0;
        return !(Math.abs(at - edge) > 1e-5);
    }
}
