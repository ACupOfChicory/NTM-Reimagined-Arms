package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContinuumTexturesTest {
    @Test
    void aListLineHoldsForContinuumsNames() {
        MachineGlass.use(List.of(
            "hbm:textures/block/models/machines/tower_small.png # grilles",
            "hbm:textures/block/barbed_wire.png # wire",
            "hbm:textures/block/models/radiolysis.png 0,0,3,3 # an area"));
        try {
            assertTrue(MachineGlass.empty("hbm:textures/block/models/machines/tower_small.png"));
            assertTrue(MachineGlass.empty("hbm:textures/models/machines/tower_small.png"));
            assertTrue(MachineGlass.empty("hbm:textures/blocks/barbed_wire.png"));
            assertTrue(MachineGlass.emptyAt("hbm:textures/models/machines/radiolysis.png", 2, 2));
            assertTrue(!MachineGlass.emptyAt("hbm:textures/models/machines/radiolysis.png", 5, 5));
        } finally {
            MachineGlass.use(null);
        }
    }
}
