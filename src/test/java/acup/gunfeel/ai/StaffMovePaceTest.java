package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StaffMovePaceTest {
    @Test
    void pacesMatchTheSoldier() {
        assertEquals(AiSoldier.RUN, AiCommander.STAFF.speed(StaffMoveBase.Pace.RUN));
        assertEquals(AiSoldier.MARCH, AiCommander.STAFF.speed(StaffMoveBase.Pace.MARCH));
        assertEquals(AiSoldier.WALK, AiCommander.STAFF.speed(StaffMoveBase.Pace.WALK));
        assertEquals(AiSoldier.CREEP, AiCommander.STAFF.speed(StaffMoveBase.Pace.CREEP));
    }
}
