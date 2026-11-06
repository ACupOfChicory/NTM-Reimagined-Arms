package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

// A face in a side of the block that its neighbour hides (the game does not draw it) stops no bullet: two iron bars
// side by side are one grate, the ends of their bars at the seam are not there.
class HiddenSideTest {
    private static final int EAST = 1 << Direction.EAST.ordinal(), WEST = 1 << Direction.WEST.ordinal();

    // a solid face in the block's east side, toward +x; another inside the block at x = 1/2
    private static long[] model() {
        long[] cells = new long[65];
        var side = new ModelSurface.Face(new double[] {1, 0, 0, 1, 1, 0, 1, 0, 1}, new double[6], 1, 1, new long[] {1});
        var mid = new ModelSurface.Face(new double[] {.5, 0, 0, .5, 1, 0, .5, 0, 1}, new double[6], 1, 1, new long[] {1});
        return ModelSurface.attach(cells, List.of(side, mid));
    }

    @Test
    void aFaceInAHiddenSideIsPassed() {
        long[] m = model();
        Vec3 from = new Vec3(2, .3, .4), to = new Vec3(0, .3, .4);
        BlockHitResult shown = ModelSurface.hit(m, BlockPos.ZERO, from, to, false, false, 0);
        assertNotNull(shown);
        assertEquals(1, shown.getLocation().x, 1e-9);
        BlockHitResult hidden = ModelSurface.hit(m, BlockPos.ZERO, from, to, false, false, EAST);
        assertNotNull(hidden, "the face inside the block still stops it");
        assertEquals(.5, hidden.getLocation().x, 1e-9);
        // another side hidden: the east face is drawn
        assertEquals(1, ModelSurface.hit(m, BlockPos.ZERO, from, to, false, false, WEST).getLocation().x, 1e-9);
    }

    private static long[] bars(Properties props, String key) {
        String v = props.getProperty(key);
        assertNotNull(v, key);
        if (v.startsWith("@")) v = props.getProperty(v);
        long[] shape = PassableClip.unpack(v);
        assertTrue(ModelSurface.present(shape), key);
        return shape;
    }

    // bars of each kind at x = 0 and x = 1, joined: rays across the seam meet nothing at it once its sides are
    // hidden, and the bars still where they are drawn
    @Test
    void barsPairHasNoWallAtTheSeam() throws Exception {
        var props = new Properties();
        try (var r = Files.newBufferedReader(Path.of("src/main/resources/ntmgunfeel-bullet-shapes.properties"))) {
            props.load(r);
        }
        String east = "[east=true,north=false,south=false,waterlogged=false,west=false]";
        int kinds = 0;
        for (String key : props.stringPropertyNames()) {
            if (!key.startsWith("minecraft:") || !key.endsWith("_bars" + east)) continue;
            kinds++;
            String id = key.substring(0, key.length() - east.length());
            seam(id, bars(props, key), bars(props, id + "[east=false,north=false,south=false,waterlogged=false,west=true]"));
        }
        assertTrue(kinds >= 9, "kinds of bars: " + kinds);
    }

    private static void seam(String id, long[] a, long[] b) {
        BlockPos pa = BlockPos.ZERO, pb = new BlockPos(1, 0, 0);
        var random = new java.util.Random(3L);
        int seamBefore = 0, seamAfter = 0, met = 0;
        for (int i = 0; i < 20000; i++) {
            // through a point near the seam, along the bars more or less
            Vec3 at = new Vec3(1 + (random.nextDouble() - .5) * .2, random.nextDouble(), .5 + (random.nextDouble() - .5) * .2);
            Vec3 way = new Vec3(random.nextBoolean() ? 1 : -1, (random.nextDouble() - .5) * .6, (random.nextDouble() - .5) * .6).normalize();
            Vec3 from = at.subtract(way.scale(3)), to = at.add(way.scale(3));
            for (boolean hide : new boolean[] {false, true}) {
                BlockHitResult first = null;
                for (BlockHitResult h : new BlockHitResult[] {ModelSurface.hit(a, pa, from, to, false, false, hide ? EAST : 0),
                        ModelSurface.hit(b, pb, from, to, false, false, hide ? WEST : 0)})
                    if (h != null && (first == null || h.getLocation().distanceToSqr(from) < first.getLocation().distanceToSqr(from))) first = h;
                if (first == null) continue;
                boolean seam = Math.abs(first.getLocation().x - 1) < 1e-6 && first.getDirection().getAxis() == Direction.Axis.X;
                if (hide) {
                    met++;
                    if (seam) seamAfter++;
                } else if (seam) seamBefore++;
            }
        }
        assertTrue(seamBefore > 0, id + ": the seam's ends are in the shapes: the check would see the bug");
        assertEquals(0, seamAfter, id + ": stopped at the seam");
        assertTrue(met > 1000, id + ": the bars are still met: " + met);
    }
}
