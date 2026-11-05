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
    // DE draws every vanilla mob cube by cube, as 26.2 without Flywheel: main's unite (X463) puts the cubes of a name the
    // profile holds once into one box. The port's profiles are made from DE's cubes, so a name repeats on DE only when a
    // layer draws the model again (the villager's jacket, its hat): the copies unite into the profile's own box, and the
    // label binds whole, with a zone on every labelled part (Flywheel's meshes, mesh/..., unite leaves as they are)
    @Test void uniteKeepsThePortsOwnCubes() {
        int profiles=0,copied=0;
        for(var resource:List.of("/ntmgunfeel-heads-1.21.1.json","/ntmgunfeel-limbs-1.21.1.json")) {
            var bank=HeadProfiles.loadBank(resource,java.nio.file.Path.of("build/no-local-port.json"),true);
            for(var entry:bank.entrySet()) {
                var profile=entry.getValue();if(profile.none()) continue;
                var own=profile.parts().stream().map(PortLabelsTest::box).toList();
                assertEquals(own,HeadProfiles.unite(profile,own),resource+" "+entry.getKey());
                var counts=new HashMap<String,Integer>();for(var b:own) counts.merge(b.part(),1,Integer::sum);
                var drawn=new ArrayList<>(own);for(var b:own) if(counts.get(b.part())==1 && !b.part().startsWith("mesh/") && !b.part().equals("submitted")) { drawn.add(b);copied++; }
                var united=HeadProfiles.unite(profile,drawn);var what=resource+" "+entry.getKey()+", части слоя дважды";
                assertEquals(own.size(),united.size(),what);
                for(int i=0;i<own.size();i++) assertBox(own.get(i),united.get(i),what);
                var binding=HeadProfiles.bind(profile,united,0);
                assertEquals(0,binding.missing(),what);
                var zoned=profile.zones().stream().map(z->own.get(z.part()).part()).collect(java.util.stream.Collectors.toSet());
                assertEquals(zoned,binding.zones().stream().map(z->united.get(z.part()).part()).collect(java.util.stream.Collectors.toSet()),what+": зоны");
                profiles++;
            }
        }
        assertEquals(96,profiles,"профили порта");assertTrue(copied>500,"скопировано частей: "+copied);
        // main's withoutFlywheelThePartsCubesAreMatchedAsOnePart (off in rewrite.txt: it resolves 26.2's llama and goat
        // through the bank) on the port's llama: Flywheel's mesh/ boxes and a cube turned to the other are not united
        var llama=HeadProfiles.get("minecraft:llama");
        var cubes=new ArrayList<>(llama.parts().stream().map(PortLabelsTest::box).toList());
        // a part the llama's profile holds once (its head and body are twice: the model's and the carpet's)
        int head=-1;for(int i=0;i<cubes.size() && head<0;i++) { var n=cubes.get(i).part();if(cubes.stream().filter(b->b.part().equals(n)).count()==1) head=i; }
        var c=cubes.get(head);var once=c.part();double q=Math.sqrt(.5);
        var turned=new ArrayList<>(cubes);turned.add(new ModelBox(c.part(),c.x(),c.y(),c.z(),c.ux()*q,0,c.ux()*q,0,c.vy(),0,-c.wz()*q,0,c.wz()*q));
        assertEquals(cubes.size(),HeadProfiles.unite(llama,new ArrayList<>(List.of(cubes.toArray(ModelBox[]::new))){{add(c);}}).size(),"копия "+once+" склеивается");
        // (a group unite leaves stays together, in the place of its first box)
        var kept=HeadProfiles.unite(llama,turned);
        assertEquals(turned.size(),kept.size(),"повёрнутый куб");assertEquals(new HashSet<>(turned),new HashSet<>(kept),"повёрнутый куб");
        var mesh=turned.stream().map(b->b.part().equals(once)?new ModelBox("mesh/"+b.part(),b.x(),b.y(),b.z(),b.ux(),b.uy(),b.uz(),b.vx(),b.vy(),b.vz(),b.wx(),b.wy(),b.wz()):b).toList();
        var doubled=new ArrayList<>(mesh);doubled.add(mesh.get(head));
        assertEquals(doubled,HeadProfiles.unite(llama,doubled),"коробки Flywheel");
    }
    private static void assertBox(ModelBox a,ModelBox b,String what) {
        assertEquals(a.part(),b.part(),what);
        double[] x={a.x(),a.y(),a.z(),a.ux(),a.uy(),a.uz(),a.vx(),a.vy(),a.vz(),a.wx(),a.wy(),a.wz()},y={b.x(),b.y(),b.z(),b.ux(),b.uy(),b.uz(),b.vx(),b.vy(),b.vz(),b.wx(),b.wy(),b.wz()};
        for(int i=0;i<x.length;i++) assertEquals(x[i],y[i],1e-9,what+" "+a.part());
    }
    // 1.21.1 draws the villager's type, profession and level layers with the whole model: the villager's jacket (the
    // zombie villager's robe) once or twice, the hat none to two times, as the type and the profession wear one (the
    // stand port-villagers457: 45 states of type and profession). Every state keeps the whole label, and each robe its zone
    @Test void everyVillagerLayerStateKeepsThePortLabel() {
        var limbs=HeadProfiles.loadBank("/ntmgunfeel-limbs.json",java.nio.file.Path.of("build/no-local-limbs.json"),false);
        record Case(String type,String robe,String hat,int robes) {}
        for(var c:List.of(new Case("minecraft:villager","root/body/jacket","root/head/hat",2),new Case("minecraft:villager#baby","root/body/jacket","root/head/hat",1),
                new Case("minecraft:zombie_villager","root/body","root/hat",2),new Case("minecraft:zombie_villager#baby","root/body","root/hat",1))) {
            var profile=HeadProfiles.get(limbs,c.type());var parts=profile.parts();
            // the robe is the long one of its name (the zombie villager's torso is root/body too)
            java.util.function.IntPredicate robe=i->parts.get(i).name().equals(c.robe()) && parts.get(i).size()[1]>parts.get(i).size()[0]*2;
            var head=parts.stream().filter(r->r.name().equals("root/head")).max(Comparator.comparingDouble(r->r.size()[0])).orElseThrow();
            var robeRef=parts.get(java.util.stream.IntStream.range(0,parts.size()).filter(robe).findFirst().orElseThrow());
            long robeZones=profile.zones().stream().filter(z->robe.test(z.part())).count();
            assertTrue(robeZones>0,c+": мантия размечена");
            var base=new ArrayList<ModelBox>();
            for(int i=0;i<parts.size();i++) if(!robe.test(i) && !parts.get(i).name().equals(c.hat())) base.add(box(parts.get(i)));
            var expected=new ArrayList<String>();
            for(var z:profile.zones()) if(!robe.test(z.part())) expected.add(parts.get(z.part()).name());
            for(int robes=1;robes<=c.robes();robes++) for(int hats=0;hats<=2;hats++) {
                var boxes=new ArrayList<>(base);
                for(int i=0;i<robes;i++) boxes.add(box(robeRef));
                var s=head.size();var o=head.center();
                for(int i=0;i<hats;i++) boxes.add(box(c.hat(),o.x-s[0]*.5625,o.y-s[1]*.5625,o.z-s[2]*.5625,s[0]*1.125,s[1]*1.125,s[2]*1.125));
                var binding=HeadProfiles.bind(profile,boxes,0);var state=c.type()+", мантий "+robes+", шляп "+hats;
                assertEquals(0,binding.missing(),state);
                var names=new ArrayList<>(expected);for(long i=0;i<robeZones*robes;i++) names.add(c.robe());
                var bound=binding.zones().stream().map(z->boxes.get(z.part()).part()).sorted().toList();
                assertEquals(names.stream().sorted().toList(),bound,state+": зоны на своих частях");
                for(int i=base.size();i<base.size()+robes;i++) { int b=i;assertEquals(robeZones,binding.zones().stream().filter(z->z.part()==b).count(),state+": зона на каждой мантии"); }
            }
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
