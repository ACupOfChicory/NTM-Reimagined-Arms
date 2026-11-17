package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

// A shaft's foot (AiHatch): a way to a foe beside it ends a rung or two up its column; the squad goes down to its floor
// and holds a cell beside it, off the column the next comes down.
class ShaftFootTest {
    /** a ladder at 10 _ 10 from y 3 up to 20, its floor at 3 */
    static boolean rung(long c) {
        return BlockPos.getX(c) == 10 && BlockPos.getZ(c) == 10 && BlockPos.getY(c) >= 3 && BlockPos.getY(c) <= 20;
    }

    static long at(int x, int y, int z) {
        return BlockPos.asLong(x, y, z);
    }

    @Test
    void aWayEndingUpTheColumnHasItsFootOnTheFloor() {
        Set<Long> cells = Set.of(at(10, 3, 10), at(10, 4, 10), at(10, 5, 10), at(10, 6, 10));
        assertEquals(at(10, 3, 10), AiHatch.floor(at(10, 5, 10), ShaftFootTest::rung, cells::contains), "two rungs up: the floor");
        assertEquals(at(10, 3, 10), AiHatch.floor(at(10, 3, 10), ShaftFootTest::rung, cells::contains), "on it already");
        // no cell to stand in at the floor (the map not read there): as the way had it
        assertEquals(at(10, 5, 10), AiHatch.floor(at(10, 5, 10), ShaftFootTest::rung, Set.of(at(10, 5, 10))::contains));
        // a cell not on the ladder: as it was
        assertEquals(at(11, 5, 10), AiHatch.floor(at(11, 5, 10), ShaftFootTest::rung, cells::contains));
    }

    @Test
    void itsWayOutIsBesideTheFloorNearestTheFoe() {
        // open on the floor east and south, a step up north
        Set<Long> cells = Set.of(at(10, 3, 10), at(11, 3, 10), at(10, 3, 11), at(10, 4, 9));
        assertEquals(at(10, 3, 11), AiHatch.besideFoot(at(10, 3, 10), cells::contains, at(9, 4, 14)), "the side nearer the foe");
        assertEquals(at(11, 3, 10), AiHatch.besideFoot(at(10, 3, 10), cells::contains, at(16, 3, 10)));
        // a step up only when none on the floor
        assertEquals(at(10, 4, 9), AiHatch.besideFoot(at(10, 3, 10), Set.of(at(10, 4, 9), at(10, 3, 10))::contains, at(10, 3, 20)));
        assertEquals(Long.MIN_VALUE, AiHatch.besideFoot(at(10, 3, 10), Set.of(at(10, 3, 10))::contains, Long.MIN_VALUE), "walled in: none");
    }
}
