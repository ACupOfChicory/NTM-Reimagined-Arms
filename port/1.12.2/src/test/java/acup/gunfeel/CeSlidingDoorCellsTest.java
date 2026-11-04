package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

// port: NTM CE's sliding blast doors (BlockSlidingBlastDoor, drawn as Collada display lists: DoorCapture) are in the
// table shut and open, all 28 blocks (7 wide, 4 high; sliding_blast_door_2's two keypad blocks among them, under the
// core's id). Shut, a ray across the door's thin axis meets the model in every block; open, the leaves are drawn slid
// aside and rays go through the passage, the frame still met.
class CeSlidingDoorCellsTest {
    @Test
    void slidingBlastDoorsAreMetByTheirModelShutAndOpen() throws Exception {
        Map<String, long[]> t = ModelCells.table();
        StringBuilder diag = new StringBuilder();
        for (String id : new String[] {"hbm:sliding_blast_door_legacy", "hbm:sliding_blast_door_2"}) {
            for (boolean open : new boolean[] {false, true}) {
                String head = id + "#door/" + (open ? "open" : "closed") + "/";
                int blocks = 0, thinX = 0, thinZ = 0;
                for (String k : t.keySet()) {
                    if (!k.startsWith(head)) continue;
                    blocks++;
                    String[] d = k.substring(head.length()).split(",");
                    if (!d[0].equals("0")) thinZ++;
                    if (!d[2].equals("0")) thinX++;
                }
                assertEquals(28, blocks, head);
                // the door spans one horizontal axis; rays go along the other
                boolean alongZ = thinZ > 0;
                assertTrue(alongZ != (thinX > 0), head + " spans one axis");
                int rays = 0, through = 0, emptyBlocks = 0;
                for (Map.Entry<String, long[]> e : t.entrySet()) {
                    if (!e.getKey().startsWith(head)) continue;
                    if (ModelCells.empty(e.getValue())) emptyBlocks++;
                    for (int i = 0; i < 16; i++) for (int j = 0; j < 16; j++) {
                        double a = (i + .5) / 16, b = (j + .5) / 16;
                        BlockRay.Hit in = alongZ ? BlockRay.clip(new double[] {0, 0, 0, 1, 1, 1}, 1, a, b, -1, a, b, 2, 0, 0, 0)
                                : BlockRay.clip(new double[] {0, 0, 0, 1, 1, 1}, 1, -1, b, a, 2, b, a, 0, 0, 0);
                        rays++;
                        if (FenceCells.march(e.getValue(), in, alongZ ? 0 : 3, 0, alongZ ? 3 : 0, 64) == null) through++;
                    }
                }
                diag.append(head).append(" blocks ").append(blocks).append(" empty ").append(emptyBlocks).append(" rays through ").append(through).append('/').append(rays).append('\n');
                if (open) assertTrue(through > rays / 4 && emptyBlocks < blocks, head + ": " + through + "/" + rays + " through, " + emptyBlocks + " empty blocks");
                else assertTrue(through == 0 && emptyBlocks == 0, head + ": " + through + "/" + rays + " through, " + emptyBlocks + " empty blocks");
            }
        }
        java.nio.file.Files.write(java.nio.file.Paths.get("build/sliding-door-cells-diag.txt"), diag.toString().getBytes("UTF-8"));
    }
}
