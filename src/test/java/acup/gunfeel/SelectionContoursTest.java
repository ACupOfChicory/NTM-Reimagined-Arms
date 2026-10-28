package acup.gunfeel;

import java.util.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SelectionContoursTest {
    @Test void selectedMiddleOfMergedEdgeLeavesOnlyUnselectedEnds() {
        var edges=SelectionContours.remaining(List.<Vec3[]>of(new Vec3[]{Vec3.ZERO,new Vec3(4,0,0)}),
                List.<Vec3[]>of(new Vec3[]{new Vec3(3,0,0),new Vec3(1,0,0)}));
        assertEquals(2,edges.size());assertEquals(new Vec3(1,0,0),edges.getFirst()[1]);assertEquals(new Vec3(3,0,0),edges.getLast()[0]);
    }
    @Test void selectingWholeCompositeBodyLeavesNoOrangeOutline() {
        var a=new ModelBox("body",0,0,0,2,0,0,0,1,0,0,0,1);
        var b=new ModelBox("limb",1.5,.5,.25,1,0,0,0,1,0,0,0,.5);
        var all=ModelContours.edges(List.of(a,b));
        var selected=HeadLabelCuts.edges(List.of(a,b),List.of("0:-1","1:-1"),Map.of());
        assertFalse(all.isEmpty());assertTrue(SelectionContours.remaining(all,selected).isEmpty());
    }
}
