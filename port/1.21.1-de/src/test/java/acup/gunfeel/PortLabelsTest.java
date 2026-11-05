package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

// The port's own head and limb labels for the models 1.21.1 draws otherwise (resources/ntmgunfeel-*-1.21.1.json,
// tools/port_labels.py) and the twin rule rewrite.txt adds to HeadProfiles.bind (port/LabelTwins).
class PortLabelsTest {
    private static ModelBox box(String name,double x,double y,double z,double sx,double sy,double sz) {
        return new ModelBox(name,x,y,z,sx,0,0,0,sy,0,0,0,sz);
    }
    private static HeadProfiles.Ref ref(ModelBox b) {
        return new HeadProfiles.Ref(b.part(),HeadProfiles.size(b),0,ModelContours.point(b,.5,.5,.5));
    }
    @Test void portBankReplacesTheSharedProfileOfItsTypes() {
        assertEquals(218,HeadProfiles.count(),"порт не добавляет типов, только заменяет");
        // 1.21.1's goat head: two ear cubes under root/head, the skull under root/head/nose, two horns
        var goat=HeadProfiles.get("minecraft:goat");
        assertEquals(11,goat.parts().size());
        var zoned=goat.zones().stream().map(z->goat.parts().get(z.part()).name()).sorted().toList();
        assertEquals(List.of("root/head","root/head","root/head/left_horn","root/head/nose","root/head/right_horn"),zoned);
        var limbs=HeadProfiles.loadBank("/ntmgunfeel-limbs.json",java.nio.file.Path.of("config/none.json"),false);
        assertTrue(limbs.containsKey("minecraft:zombie#baby"));
        assertTrue(limbs.get("minecraft:zombie#baby").parts().stream().allMatch(p->!p.name().startsWith("mesh/")),"детёныш 1.21.1 — модель взрослого");
    }
    // the zombie villager's type and profession layers draw one robe twice: its zone binds with one, two or three robes,
    // and goes on each of them
    @Test void identicalLayerBoxesDoNotMakeTheZoneAmbiguous() {
        var leg=box("root/leg",0,0,0,.25,.75,.25);var robe=box("root/body",-.1,.25,-.1,.5,1.25,.4);
        var body=box("root/body",0,.75,0,.5,.75,.375);
        var planes=List.of(new HeadZone.Plane(0,-1,0,.5));
        var profile=new HeadProfiles.Profile(false,List.of(ref(leg),ref(body),ref(robe),ref(robe)),
                List.of(new HeadProfiles.Zone(0,1,List.of(),0),new HeadProfiles.Zone(2,1,planes,0),new HeadProfiles.Zone(3,1,planes,0)));
        for(int robes=1;robes<=3;robes++) {
            var boxes=new ArrayList<>(List.of(leg,body));for(int i=0;i<robes;i++) boxes.add(robe);
            var binding=HeadProfiles.bind(profile,boxes,0);
            assertEquals(0,binding.missing(),robes+" robes");
            var robed=binding.zones().stream().map(HeadProfiles.Zone::part).filter(i->i>=2).distinct().count();
            assertEquals(robes,robed,"зона на каждой мантии: у каждой своя маска");
            var zones=HeadProfiles.zones(binding,boxes);
            assertTrue(HeadZone.contains(zones,new Vec3(.38,.3,.28)),"нижняя часть мантии");
            assertFalse(HeadZone.contains(zones,new Vec3(.38,1.2,.28)),"верх мантии не конечность");
        }
        // another robe in another place is a rival: still no guess
        var moved=box("root/body",-.1,.25,-.08,.5,1.25,.4);
        var rival=new HeadProfiles.Profile(false,List.of(ref(leg),ref(robe)),List.of(new HeadProfiles.Zone(1,1,planes,0)));
        assertEquals(1,HeadProfiles.bind(rival,List.of(leg,body,robe,moved),0).missing());
    }
}
