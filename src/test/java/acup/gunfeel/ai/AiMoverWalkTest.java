package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// core's AiMoverBase runs and wants a run above AiSoldier's walk: the two are kept the same number
class AiMoverWalkTest {
    @Test
    void theSoldiersWalk() {
        assertEquals(AiSoldier.WALK, AiMoverBase.WALK);
    }
}
