package acup.gunfeel.water;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndLightGetter;
import org.junit.jupiter.api.Test;

/** WaterRings' own surface host (not a copy) reads light at the cell the geometry asks for. */
class WaterRingsAdapterTest {
    private static final class Asked extends RuntimeException {
        Asked() { super(null, null, false, false); }
    }

    @Test void lightIsReadAtTheAskedCell() {
        List<BlockPos> asked = new ArrayList<>();
        var getter = (BlockAndLightGetter) Proxy.newProxyInstance(BlockAndLightGetter.class.getClassLoader(),
                new Class<?>[] {BlockAndLightGetter.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getBlockState")) {
                        asked.add(((BlockPos) args[0]).immutable());
                        throw new Asked();
                    }
                    throw new AssertionError("unexpected " + method);
                });
        var host = new WaterRings.SurfaceHost() {
            @Override BlockAndLightGetter lightLevel() { return getter; }
        };
        int[][] cells = {{0, 0, 0}, {-3, 62, 7}, {123456, -64, -987654}, {-1, 319, -1}};
        for (int[] c : cells) assertThrows(Asked.class, () -> host.light(c[0], c[1], c[2]));
        assertEquals(cells.length, asked.size());
        for (int i = 0; i < cells.length; i++)
            assertEquals(new BlockPos(cells[i][0], cells[i][1], cells[i][2]), asked.get(i), "cell " + i);
    }
}
