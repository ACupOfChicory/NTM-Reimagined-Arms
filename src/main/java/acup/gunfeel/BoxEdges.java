package acup.gunfeel;

import java.util.Arrays;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A one-cell box shape that hands out its 12 edges at once, in the order the game's own walk gives them: NTM's thermal
 * outlines (ArmorOverheadRenderer) make one such shape an entity a frame, and the game's walk over its cells costs more
 * than the lines. The order and the values are the game's: the order is taken from the first real shape, the first
 * shapes are checked edge by edge against the real ones, and on any difference the real shapes are used again.
 */
public final class BoxEdges extends VoxelShape {
    private static final BitSetDiscreteVoxelShape CELL = BitSetDiscreteVoxelShape.withFilledBounds(1, 1, 1, 0, 0, 0, 1, 1, 1);
    private static final int CHECKED = 64;
    /** each edge's six values as 0 (the axis's first coordinate) or 1 (its second) */
    private static volatile int[] order;
    private static int checked;
    private static volatile boolean on = true;
    /** the stand's switch */
    public static volatile boolean use = true;

    private final VoxelShape real;
    private final double x0, x1, y0, y1, z0, z1;

    private BoxEdges(VoxelShape real) {
        super(CELL);
        this.real = real;
        DoubleList xs = real.getCoords(Direction.Axis.X), ys = real.getCoords(Direction.Axis.Y), zs = real.getCoords(Direction.Axis.Z);
        x0 = xs.getDouble(0);
        x1 = xs.getDouble(1);
        y0 = ys.getDouble(0);
        y1 = ys.getDouble(1);
        z0 = zs.getDouble(0);
        z1 = zs.getDouble(1);
    }

    /** the shape to outline in place of {@code real}: this one for a one-cell box, else {@code real} itself */
    public static VoxelShape of(VoxelShape real) {
        if (!on || !use || !(real instanceof ArrayVoxelShape) || real.getCoords(Direction.Axis.X).size() != 2
                || real.getCoords(Direction.Axis.Y).size() != 2 || real.getCoords(Direction.Axis.Z).size() != 2) return real;
        BoxEdges box = new BoxEdges(real);
        if (order == null && !learn(box)) return real;
        if (checked < CHECKED) {
            if (!Arrays.equals(edges(real), edges(box))) {
                off("an edge differs from the game's");
                return real;
            }
            checked++;
        }
        return box;
    }

    private static boolean learn(BoxEdges box) {
        double[] e = edges(box.real);
        if (e.length != 72) {
            off("the game's box has " + e.length / 6 + " edges");
            return false;
        }
        int[] o = new int[72];
        for (int i = 0; i < 72; i++) {
            double lo = i % 3 == 0 ? box.x0 : i % 3 == 1 ? box.y0 : box.z0, hi = i % 3 == 0 ? box.x1 : i % 3 == 1 ? box.y1 : box.z1;
            if (Double.compare(e[i], lo) == 0) o[i] = 0;
            else if (Double.compare(e[i], hi) == 0) o[i] = 1;
            else {
                off("an edge of the game's box is not on its corners");
                return false;
            }
        }
        order = o;
        return true;
    }

    private static double[] edges(VoxelShape shape) {
        double[][] out = {new double[72]};
        int[] n = {0};
        shape.forAllEdges((a, b, c, d, e, f) -> {
            if (n[0] + 6 > out[0].length) out[0] = Arrays.copyOf(out[0], out[0].length * 2);
            double[] o = out[0];
            o[n[0]++] = a;
            o[n[0]++] = b;
            o[n[0]++] = c;
            o[n[0]++] = d;
            o[n[0]++] = e;
            o[n[0]++] = f;
        });
        return Arrays.copyOf(out[0], n[0]);
    }

    static void off(String why) {
        if (!on) return;
        on = false;
        DebugLog.LOGGER.info("[thermal] outline shortcut off, NTM's outlines are drawn the game's way: {}", why);
    }

    @Override
    public DoubleList getCoords(Direction.Axis axis) {
        return real.getCoords(axis);
    }

    @Override
    public void forAllEdges(Shapes.DoubleLineConsumer consumer) {
        int[] o = order;
        for (int i = 0; i < 72; i += 6)
            consumer.consume(o[i] == 0 ? x0 : x1, o[i + 1] == 0 ? y0 : y1, o[i + 2] == 0 ? z0 : z1,
                    o[i + 3] == 0 ? x0 : x1, o[i + 4] == 0 ? y0 : y1, o[i + 5] == 0 ? z0 : z1);
    }
}
