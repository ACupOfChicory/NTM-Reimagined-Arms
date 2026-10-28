package acup.gunfeel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArmTimelineTest {
    @Test void editingReloadKeepsOtherAnimationsAndResolvesNamedTimes() {
        String source="INSPECT 0:grip 200:Magazine@0,1,2; RELOAD 0:grip release:Handle!@1,2,3/1,0,0>~0.4,-0.6,0.6^20";
        var keys=ArmTimeline.read(source,"RELOAD","release=510",0);
        assertEquals(510,keys.get(1).time);
        assertTrue(keys.get(1).corner);assertTrue(keys.get(1).follow);
        assertEquals(20,keys.get(1).roll);
        keys.get(1).time=620;
        String result=ArmTimeline.write(source,"RELOAD",keys);
        assertTrue(result.startsWith("INSPECT 0:grip 200:Magazine@0,1,2;"));
        var reopened=ArmTimeline.read(result,"RELOAD","",0);
        assertEquals(620,reopened.get(1).time);assertArrayEquals(keys.get(1).shoulder,reopened.get(1).shoulder);
        assertEquals(result,ArmTimeline.write(result,"RELOAD",reopened));
    }
    @Test void repeatedPartsAndHandRollSurviveSaving() {
        var keys=ArmTimeline.read("RELOAD 0:Belt#last@0,1,2/0,-1,0 100:Magazine@0,0,0","RELOAD","",-35);
        assertEquals("Belt#last",keys.getFirst().part);assertEquals(-35,keys.getLast().roll);
        var copy=keys.getFirst().copy();copy.point[0]=5;
        assertEquals(0,keys.getFirst().point[0]);
        assertThrows(IllegalArgumentException.class,()->ArmTimeline.vector("NaN,0,0"));
    }
}
