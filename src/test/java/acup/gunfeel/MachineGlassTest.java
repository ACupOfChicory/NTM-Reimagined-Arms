package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

class MachineGlassTest {
    @AfterEach
    void back() {
        MachineGlass.setting = () -> true;
    }

    // a 4 x 4 texture, its 2 x 2 middle clear (a window in a frame)
    private static final int W = 4, H = 4;
    private static final boolean[] SHOWN = new boolean[W * H];
    private static final int[] SUM = new int[(W + 1) * (H + 1)];
    static {
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) SHOWN[y * W + x] = !(x >= 1 && x <= 2 && y >= 1 && y <= 2);
        for (int y = 0; y < H; y++) {
            int row = 0;
            for (int x = 0; x < W; x++) {
                if (!SHOWN[y * W + x]) row++;
                SUM[(y + 1) * (W + 1) + x + 1] = SUM[y * (W + 1) + x + 1] + row;
            }
        }
    }

    private static boolean covers(float... uv) {
        return MachineGlass.covers(uv, W, H, SHOWN, SUM);
    }

    @Test
    void triangleOnTheFrameOnlyHasNoWindow() {
        // the top row, pixels 0..3 x 0..1
        assertFalse(covers(0F, 0F, 1F, 0F, 0F, .25F));
        // the left column
        assertFalse(covers(0F, 0F, .25F, 0F, 0F, 1F));
    }

    @Test
    void triangleOverTheWindowHasIt() {
        assertTrue(covers(0F, 0F, 1F, 0F, 0F, 1F));
        // a sliver inside one clear pixel, no pixel centre in it
        assertTrue(covers(.30F, .30F, .31F, .30F, .30F, .31F));
    }

    @Test
    void triangleWhoseBoxMeetsTheWindowButItselfDoesNot() {
        // the box covers pixels 0..3 x 0..3, the triangle hugs the top-left corner past the window's corner (1, 1)
        assertFalse(covers(0F, 0F, .5F, 0F, 0F, .5F));
        assertTrue(covers(0F, 0F, .6F, 0F, 0F, .6F));
    }

    @Test
    void repeatedTextureWraps() {
        // the window one texture over
        assertTrue(covers(1.30F, .30F, 1.40F, .30F, 1.30F, .40F));
        assertFalse(covers(1.01F, .01F, 1.2F, .01F, 1.01F, .2F));
    }

    @Test
    void listLines() {
        assertEquals("hbm:textures/a.png", MachineGlass.parse("hbm:textures/a.png # a grate"));
        assertNull(MachineGlass.parse("# comment"));
        assertNull(MachineGlass.parse("   "));
        for (String t : MachineGlass.read()) assertTrue(t.matches("hbm:textures/[a-z0-9_/.-]+\\.png"), t);
    }

    // a face at x = 1/2 facing -x
    private static ModelSurface.Face face(double x, boolean glass) {
        return new ModelSurface.Face(new double[] {x, 0, 0, x, 0, 1, x, 1, 0}, new double[6], 1, 1, new long[] {1}, glass);
    }

    @Test
    void glassFaceKeepsItsMarkAndIsMetAsAWindow() {
        long[] shape = ModelSurface.attach(new long[65], List.of(face(.5, true), face(.8, false)));
        assertTrue(ModelSurface.valid(shape));
        List<ModelSurface.Face> faces = ModelSurface.faces(shape);
        assertTrue(faces.get(0).glass());
        assertFalse(faces.get(1).glass());
        assertEquals(1, faces.get(0).width());
        assertTrue(ModelSurface.faces(ModelSurface.rotate(shape, net.minecraft.world.level.block.Rotation.CLOCKWISE_90)).get(0).glass());
        Vec3 from = new Vec3(0, .1, .2), to = new Vec3(1, .1, .2);
        var hit = ModelSurface.hit(shape, BlockPos.ZERO, from, to, false);
        assertInstanceOf(ModelSurface.GlassHit.class, hit);
        assertEquals(.5, hit.getLocation().x, 1e-9);
        assertFalse(PassableClip.paidOnce(hit));
        // past the window: the casing, not glass
        var next = ModelSurface.hit(shape, BlockPos.ZERO, hit.getLocation().add(.01, 0, 0), to, false);
        assertNotNull(next);
        assertFalse(next instanceof ModelSurface.GlassHit);
        assertEquals(.8, next.getLocation().x, 1e-9);
    }

    @Test
    void windowIsAirWhenTheServerSaysSo() {
        MachineGlass.setting = () -> false;
        long[] shape = ModelSurface.attach(new long[65], List.of(face(.5, true), face(.8, false)));
        var hit = ModelSurface.hit(shape, BlockPos.ZERO, new Vec3(0, .1, .2), new Vec3(1, .1, .2), false);
        assertNotNull(hit);
        assertEquals(.8, hit.getLocation().x, 1e-9);
    }

    @Test
    void windowIsNoWayOut() {
        // leaving the block: a window is not the casing's far side
        long[] shape = ModelSurface.attach(new long[65], List.of(new ModelSurface.Face(new double[] {.5, 0, 0, .5, 1, 0, .5, 0, 1},
                new double[6], 1, 1, new long[] {1}, true)));
        assertNull(ModelSurface.hit(shape, BlockPos.ZERO, new Vec3(0, .1, .2), new Vec3(1, .1, .2), true));
    }
}
