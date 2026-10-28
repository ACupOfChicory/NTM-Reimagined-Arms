package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

// Сравнение с прежним непрерывным растеризатором: разбиение по кадрам не меняет ни одной клетки.
class ShapeSamplingTest {
    private static Map<BlockPos,long[]> cells() {
        var cells=new HashMap<BlockPos,long[]>();
        for(int x=-1;x<4;x++)for(int y=0;y<3;y++)for(int z=-1;z<3;z++)cells.put(new BlockPos(x,y,z),new long[65]);
        return cells;
    }
    @Test void interruptedTriangleKeepsExactCellsAndTransparentPixels() {
        double[][] triangles={
            {0,.5,0,2.2,.5,0,0,.5,1.3},
            {1,0,0,1,2,0,1,0,2},
            {-.1,.2,-.1,2.7,1.8,.2,.1,.9,1.9},
            {0,0,0,0,0,0,0,0,0}};
        for(double[] p:triangles) {
            var expected=cells();var actual=cells();
            float[] uv={0,0,1,0,0,1};
            MeshShapes.Shown shown=(u,v)->((int)Math.floor(u*16)+(int)Math.floor(v*16))%3!=0;
            reference(p,shown,uv,expected);
            var sampler=new MeshShapes.Sample(p,shown,uv,actual);
            assertFalse(sampler.step(0));
            assertTrue(actual.values().stream().allMatch(a->Arrays.stream(a).allMatch(x->x==0)));
            int slices=0;
            while(!sampler.step(System.nanoTime()+30_000L))assertTrue(++slices<100_000);
            for(var entry:expected.entrySet())assertArrayEquals(entry.getValue(),actual.get(entry.getKey()),entry.getKey().toString());
        }
    }
    private static double sq(double d){return d*d;}
    private static void reference(double[] p, MeshShapes.Shown shown, float[] uv, Map<BlockPos, long[]> bits) {
        double edge = 0;
        for (int i = 0; i < 3; i++) {
            int j = (i + 1) % 3;
            edge = Math.max(edge, Math.sqrt(sq(p[i * 3] - p[j * 3]) + sq(p[i * 3 + 1] - p[j * 3 + 1]) + sq(p[i * 3 + 2] - p[j * 3 + 2])));
        }
        int n = Math.max(2, (int) Math.ceil(edge * 64));
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= n - i; j++) {
                double wa = (double) i / n, wb = (double) j / n, wc = 1 - wa - wb;
                double x = wa * p[0] + wb * p[3] + wc * p[6];
                double y = wa * p[1] + wb * p[4] + wc * p[7];
                double z = wa * p[2] + wb * p[5] + wc * p[8];
                float u = (float) (wa * uv[0] + wb * uv[2] + wc * uv[4]);
                float v = (float) (wa * uv[1] + wb * uv[3] + wc * uv[5]);
                long[] in = null;
                double bx = 0, by = 0, bz = 0;
                for (int nudge = 0; nudge < 8 && in == null; nudge++) {
                    bx = x - ((nudge & 1) != 0 ? 1e-4 : 0);
                    by = y - ((nudge & 2) != 0 ? 1e-4 : 0);
                    bz = z - ((nudge & 4) != 0 ? 1e-4 : 0);
                    in = bits.get(at.set(Math.floor(bx), Math.floor(by), Math.floor(bz)));
                }
                if (in == null || !shown.at(u, v)) continue;
                int cx = Math.clamp((int) Math.floor((bx - at.getX()) * 16), 0, 15);
                int cy = Math.clamp((int) Math.floor((by - at.getY()) * 16), 0, 15);
                int cz = Math.clamp((int) Math.floor((bz - at.getZ()) * 16), 0, 15);
                int k = cx + 16 * (cy + 16 * cz);
                in[k >> 6] |= 1L << (k & 63);
            }
        }
    }

}
