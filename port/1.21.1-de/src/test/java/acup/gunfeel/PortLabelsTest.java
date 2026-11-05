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
        assertEquals(264,HeadProfiles.count(),"порт не добавляет типов, только заменяет");
        // 1.21.1's goat head: two ear cubes under root/head, the skull under root/head/nose, two horns
        var goat=HeadProfiles.get("minecraft:goat");
        assertEquals(11,goat.parts().size());
        var zoned=goat.zones().stream().map(z->goat.parts().get(z.part()).name()).sorted().toList();
        assertEquals(List.of("root/head","root/head","root/head/left_horn","root/head/nose","root/head/right_horn"),zoned);
        var limbs=HeadProfiles.loadBank("/ntmgunfeel-limbs.json",java.nio.file.Path.of("config/none.json"),false);
        assertTrue(limbs.containsKey("minecraft:zombie#baby"));
        assertTrue(limbs.get("minecraft:zombie#baby").parts().stream().allMatch(p->!p.name().startsWith("mesh/")),"детёныш 1.21.1 — модель взрослого");
    }
    // main's partsTheModelMayNotDrawDoNotRejectTheWholeLabel on the port's own labels (rewrite.txt turns main's off: it
    // drops parts by 26.2's names). A state of the mob that does not draw labelled boxes (the sheared sheep's wool, the
    // goat's horns, the llama without a carpet, the illager's crossed or free arms) keeps the rest of the label
    private static ModelBox box(HeadProfiles.Ref r) {
        var c=r.center();var s=r.size();
        return new ModelBox(r.name(),c.x-s[0]/2,c.y-s[1]/2,c.z-s[2]/2,s[0],0,0,0,s[1],0,0,0,s[2]);
    }
    @Test void partsTheModelMayNotDrawDoNotRejectThePortLabel() {
        var limbs=HeadProfiles.loadBank("/ntmgunfeel-limbs.json",java.nio.file.Path.of("build/no-local-limbs.json"),false);
        record Case(boolean limb,String type,java.util.function.Predicate<String> drop,String what) {}
        java.util.function.Function<String,java.util.function.Predicate<String>> named=part->n->n.contains(part);
        var cases=List.of(new Case(false,"minecraft:sheep",named.apply("/wool"),"шерсть"),new Case(true,"minecraft:sheep",named.apply("/wool"),"шерсть"),
                new Case(false,"minecraft:sheep#baby",named.apply("/wool"),"шерсть"),new Case(true,"minecraft:sheep#baby",named.apply("/wool"),"шерсть"),
                new Case(false,"minecraft:goat",named.apply("/left_horn"),"левый рог"),new Case(false,"minecraft:goat",named.apply("_horn"),"рога"),
                new Case(true,"minecraft:vindicator",named.apply("_arm"),"свободные руки"),new Case(true,"minecraft:vindicator",named.apply("/arms"),"скрещённые руки"),
                new Case(true,"minecraft:evoker",named.apply("_arm"),"свободные руки"),new Case(true,"minecraft:evoker",named.apply("/arms"),"скрещённые руки"),
                new Case(true,"minecraft:illusioner",named.apply("_arm"),"свободные руки"),new Case(true,"minecraft:illusioner",named.apply("/arms"),"скрещённые руки"));
        var all=new ArrayList<>(cases);
        // the llama's carpet: the decor layer's boxes, named as the model's own (the parts after the model's nine)
        all.add(new Case(false,"minecraft:llama",null,"ковёр"));
        for(var c:all) {
            var profile=c.limb()?HeadProfiles.get(limbs,c.type()):HeadProfiles.get(c.type());
            var parts=profile.parts();var kept=new ArrayList<Integer>();
            for(int i=0;i<parts.size();i++) if(c.drop()==null?i<9:!c.drop().test(parts.get(i).name())) kept.add(i);
            assertTrue(kept.size()<parts.size(),c+": нечего убирать");
            var boxes=kept.stream().map(i->box(parts.get(i))).toList();
            var binding=HeadProfiles.bind(profile,boxes,0);
            assertEquals(0,binding.missing(),c.type()+" без "+c.what());
            // the labelled boxes are those left of the labelled parts (without its carpet the llama's carpet part binds to
            // the head of its name and adds the head a second zone, cut 0.01 lower)
            var expected=profile.zones().stream().map(HeadProfiles.Zone::part).filter(kept::contains).map(kept::indexOf).collect(java.util.stream.Collectors.toSet());
            var bound=binding.zones().stream().map(HeadProfiles.Zone::part).collect(java.util.stream.Collectors.toSet());
            assertEquals(expected,bound,c.type()+" без "+c.what()+": размечены оставшиеся части");
            assertFalse(expected.isEmpty(),c.type()+" без "+c.what()+": разметка осталась");
            // without the links the same state rejects the whole label (the llama's carpet part finds the head of its name)
            if(c.drop()==null) continue;
            var unlinked=new HeadProfiles.Profile(false,parts.stream().map(r->new HeadProfiles.Ref(r.name(),r.size(),r.shape(),r.center(),-1)).toList(),profile.zones());
            assertTrue(HeadProfiles.bind(unlinked,boxes,0).missing()>0,c.type()+" без "+c.what()+": без instead разметка отвергается");
        }
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
