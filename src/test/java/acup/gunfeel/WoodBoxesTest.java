package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

import com.hbm.client.model.NtBoxModel;

// NTM's wooden trench blocks: the cells bullets meet (WoodBoxes) are the boxes NTM's model draws, for every
// neighbour key. NTM's model is client code, so the server keeps its own copy of the boxes; this keeps them the same.
class WoodBoxesTest {
    @Test
    void sameAsNtmModel() {
        NtBoxModel.Shapes[] kinds = {NtBoxModel.Shapes.WOOD_BARRIER, NtBoxModel.Shapes.WOOD_ROOF, NtBoxModel.Shapes.WOOD_SCAFFOLD,
                NtBoxModel.Shapes.WOOD_CEILING};
        for (int kind = 0; kind < kinds.length; kind++) {
            for (int key = 0; key < 32; key++) {
                long[] want = new long[65];
                kinds[kind].boxes(key, (x0, y0, z0, x1, y1, z1) -> {
                    for (int z = Math.round(z0); z < Math.round(z1); z++) for (int y = Math.round(y0); y < Math.round(y1); y++)
                        for (int x = Math.round(x0); x < Math.round(x1); x++) {
                            int k = x + 16 * (y + 16 * z);
                            want[k >> 6] |= 1L << (k & 63);
                        }
                });
                assertArrayEquals(want, WoodBoxes.make(kind, key), kinds[kind] + " key " + key);
            }
        }
    }
}
