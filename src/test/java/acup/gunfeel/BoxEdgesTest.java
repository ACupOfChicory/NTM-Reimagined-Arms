package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

// the thermal outline's box gives the same edges, in the same order, as the game's shape for it
class BoxEdgesTest {
    @Test
    void sameEdgesAsTheGame() {
        double[][] boxes = {{-0.3, 0, -0.3, 0.3, 1.95, 0.3}, {-0.7, 0.1, -0.35, 0.7, 0.9, 0.35}, {-1.03, 0, -1.03, 1.03, 2.9, 1.03},
                {-0.125, 0.33, -0.2, 0.37, 0.61, 0.019}, {-3.1, 0, -2.2, 3.1, 5.7, 2.2}};
        for (double[] b : boxes) {
            VoxelShape real = Shapes.create(new AABB(b[0], b[1], b[2], b[3], b[4], b[5]));
            VoxelShape box = BoxEdges.of(real);
            assertNotSame(real, box);
            assertArrayEquals(edges(real), edges(box));
            // what else a shape is asked: the same answers as the game's own
            org.junit.jupiter.api.Assertions.assertEquals(real.bounds(), box.bounds());
            org.junit.jupiter.api.Assertions.assertEquals(real.isEmpty(), box.isEmpty());
            org.junit.jupiter.api.Assertions.assertEquals(real.toAabbs(), box.toAabbs());
            org.junit.jupiter.api.Assertions.assertEquals(real.move(1.5, -2, 0.25).toAabbs(), box.move(1.5, -2, 0.25).toAabbs());
            org.junit.jupiter.api.Assertions.assertEquals(real.optimize().toAabbs(), box.optimize().toAabbs());
        }
    }

    @Test
    void otherShapesStayTheGames() {
        VoxelShape block = Shapes.create(new AABB(0, 0, 0, 1, 1, 1)), half = Shapes.create(new AABB(0, 0, 0, 0.5, 1, 0.5));
        assertSame(block, BoxEdges.of(block));
        assertSame(half, BoxEdges.of(half));
        assertSame(Shapes.empty(), BoxEdges.of(Shapes.empty()));
    }

    private static double[] edges(VoxelShape shape) {
        List<Double> out = new ArrayList<>();
        shape.forAllEdges((a, b, c, d, e, f) -> {
            out.add(a);
            out.add(b);
            out.add(c);
            out.add(d);
            out.add(e);
            out.add(f);
        });
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }
}
