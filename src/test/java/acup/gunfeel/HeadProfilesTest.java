package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class HeadProfilesTest {
    @Test void babyWithDifferentProportionsNeedsItsOwnVerifiedProfile() {
        var body=new HeadProfiles.Ref("root/body",new double[]{1,2,1},0,new Vec3(.5,1,.5));
        var head=new HeadProfiles.Ref("root/head",new double[]{1,1,1},0,new Vec3(.5,2.5,.5));
        var leg=new HeadProfiles.Ref("root/leg",new double[]{1,1,1},0,new Vec3(.5,-.5,.5));
        var profile=new HeadProfiles.Profile(false,List.of(body,head,leg),List.of(new HeadProfiles.Zone(1,1,List.of(),0)));
        var smallBody=new ModelBox("mesh/root/body",0,0,0,.5,0,0,0,1,0,0,0,.5);
        var largeHead=new ModelBox("mesh/root/root/head",0,1,0,.75,0,0,0,.75,0,0,0,.75);
        var smallLeg=new ModelBox("mesh/root/leg",0,-.5,0,.5,0,0,0,.5,0,0,0,.5);
        var boxes=List.of(smallBody,largeHead,smallLeg);
        assertEquals(1,HeadProfiles.bind(profile,boxes,0).missing(),"Старый общий масштаб терял голову");
        var verifiedParts=boxes.stream().map(b->new HeadProfiles.Ref(b.part(),HeadProfiles.size(b),0,ModelContours.point(b,.5,.5,.5))).toList();
        var binding=HeadProfiles.bind(new HeadProfiles.Profile(false,verifiedParts,profile.zones()),boxes,0);
        assertEquals(0,binding.missing());
        assertTrue(HeadZone.contains(HeadProfiles.zones(binding,boxes),new Vec3(.4,1.4,.4)));
        assertFalse(HeadZone.contains(HeadProfiles.zones(binding,boxes),new Vec3(.2,.5,.2)));
        assertNull(HeadProfiles.get("naturalist:bear#baby"),"Непроверенный детёныш не наследует разметку взрослого по ID");
    }
    private ModelBox box(String name,double y) { return new ModelBox(name,0,y,0,1,0,0,0,1,0,0,0,1); }
    @Test void allManualTypesArePackagedAndNoHeadIsAuthoritative() {
        assertEquals(264,HeadProfiles.count());
        for(String type:List.of("minecraft:slime","naturalist:piranha","alexscaves:deep_one_mage")) {
            var result=ModelHeads.resolve(type,List.of(box("root/head",0)),0);
            assertEquals("dataset",result.source());assertTrue(result.zones().isEmpty());
        }
    }
    @Test void unmatchedKnownModelCannotBeReplacedByProceduralGuess() {
        var result=ModelHeads.resolve("minecraft:skeleton",List.of(box("invented/head",0)),0);
        assertEquals("height",result.source());assertTrue(result.zones().isEmpty());
    }
    @Test void unknownNamedModelCannotInventAHeadFromItsName() {
        var result=ModelHeads.resolve("foreign:creature",List.of(box("root/body",0),box("root/head",1)),0);
        assertEquals("height",result.source());assertTrue(result.zones().isEmpty());
    }
    @Test void customMobWithTheWholeCheckedShapeInheritsItsManualHead() {
        var profile=HeadProfiles.get("minecraft:zombie");
        var boxes=new ArrayList<ModelBox>();int i=0;
        for(var ref:profile.parts()) {
            assertEquals(0,ref.shape());var s=ref.size();var c=ref.center();
            boxes.add(new ModelBox("foreign/part"+i++,c.x-s[0]/2,c.y-s[1]/2,c.z-s[2]/2,s[0],0,0,0,s[1],0,0,0,s[2]));
        }
        var result=ModelHeads.resolve("foreign:custom_zombie",boxes,0);
        assertEquals("template",result.source());
        assertTrue(HeadZone.contains(result.zones(),profile.parts().get(profile.zones().getFirst().part()).center()));
        var changed=new ArrayList<>(boxes);changed.removeLast();
        assertEquals("height",ModelHeads.resolve("foreign:missing_limb",changed,0).source());
    }
    @Test void zoneMultipliersAndSmallSpeciesPolicy() {
        assertEquals(1.75F,ModelHeads.multiplier(true,false));
        assertEquals(1F,ModelHeads.multiplier(false,false));
        assertEquals(.5F,ModelHeads.multiplier(false,true));
        assertEquals(1.75F,ModelHeads.multiplier(true,true));
        assertFalse(LimbPolicy.eligible(.35F,.6F)); // Эллей.
        assertFalse(LimbPolicy.eligible(.5F,.5F)); // Лягушка.
        assertTrue(LimbPolicy.eligible(.6F,1.95F)); // Взрослый тип зомби и жителя: включает их детёнышей.
    }
    @Test void conflictingCheckedShapesDoNotAssignAnArbitraryHead() {
        var boxes=List.of(new ModelBox("a",0,0,0,2,0,0,0,2,0,0,0,2),box("b",3));
        var refs=boxes.stream().map(b->new HeadProfiles.Ref(b.part(),HeadProfiles.size(b),0,ModelContours.point(b,.5,.5,.5))).toList();
        var head=new HeadProfiles.Profile(false,refs,List.of(new HeadProfiles.Zone(1,1,List.of(),0)));
        var other=new HeadProfiles.Profile(false,refs,List.of(new HeadProfiles.Zone(0,1,List.of(),0)));
        assertNotNull(ZoneTemplates.find(Map.of("first",head),boxes,0));
        assertNull(ZoneTemplates.find(Map.of("first",head,"second",other),boxes,0));
    }
    @Test void impactOnRotatedHeadSurfaceSurvivesCoordinateRoundTrip() {
        double a=.731,c=Math.cos(a),s=Math.sin(a);
        var box=new ModelBox("head",0,1.5,0,.5*c,0,.5*s,0,.5,0,-.5*s,0,.5*c);
        var surface=ModelContours.point(box,0,.5,.5);
        // Вычитание большого мирового начала и обратный поворот в игре
        // оставляют точку на несколько ulp снаружи поверхности.
        var rounded=surface.add(-c*1e-14,0,-s*1e-14);
        assertTrue(new HeadZone(box,List.of()).contains(rounded));
        assertFalse(new HeadZone(box,List.of()).contains(surface.add(-c*.001,0,-s*.001)));
    }
    @Test void localCutFollowsRotationShearMovementAndScale() {
        var local=List.of(new HeadZone.Plane(0,1,0,-.7));
        var moved=new ModelBox("head",8,20,-3,0,0,-2,.3,2,0,2,0,0);
        var zone=HeadZone.local(moved,local);
        assertTrue(zone.contains(ModelContours.point(moved,.5,.85,.5)));
        assertFalse(zone.contains(ModelContours.point(moved,.5,.3,.5)));
        var restored=HeadZone.localPlane(moved,zone.planes().getFirst());
        assertEquals(.7,-restored.d()/restored.y(),1e-8);
    }
    @Test void reorderingNamedPartsAndUniformGrowthKeepTheirIdentity() {
        var a=new HeadProfiles.Ref("root/body",new double[]{1,1,1},0,new Vec3(.5,.5,.5));
        var b=new HeadProfiles.Ref("root/head",new double[]{1,1,1},0,new Vec3(.5,1.5,.5));
        var profile=new HeadProfiles.Profile(false,List.of(a,b),List.of(new HeadProfiles.Zone(1,1,List.of(new HeadZone.Plane(0,1,0,-.7)),0)));
        var head=new ModelBox("root/head",0,2,0,2,0,0,0,2,0,0,0,2);
        var body=new ModelBox("root/body",0,0,0,2,0,0,0,2,0,0,0,2);
        var binding=HeadProfiles.bind(profile,List.of(head,body),0);
        assertEquals(0,binding.missing());
        var zones=HeadProfiles.zones(binding,List.of(head,body));
        assertTrue(HeadZone.contains(zones,new Vec3(1,3.8,1)));
        assertFalse(HeadZone.contains(zones,new Vec3(1,2.4,1)));
    }
    // Mask Man с половины здоровья: NTM рисует череп (1x1x1, захват зовёт его mesh/root/6) и записку IOU вместо головы
    @Test void maskManHeadIsFoundOnTheSkullItWearsWhenWounded() {
        var profile=HeadProfiles.get("hbm:entity_mob_mask_man");
        // порт 1.7.10 находит голову Mask Man по именам частей (своя разметка, HeadsPortTest)
        org.junit.jupiter.api.Assumptions.assumeTrue(profile.parts().size()>6,"разметка по формам 26.2");
        var healthy=new java.util.ArrayList<ModelBox>();
        for(int i=0;i<6;i++) healthy.add(box(profile.parts().get(i)));
        var wounded=new java.util.ArrayList<>(healthy.subList(0,5));
        var skull=box(profile.parts().get(6));wounded.add(skull);
        wounded.add(new ModelBox("mesh/root/7",-.125,4.375,.1875,.25,0,0,0,.25,0,0,0,.01));
        for(var boxes:List.of(healthy,wounded)) {
            var binding=HeadProfiles.bind(profile,boxes,0);
            assertEquals(0,binding.missing());
            assertEquals(1,binding.zones().size());
            assertEquals(5,binding.zones().getFirst().part(),"голова или череп, но не записка");
            assertEquals(boxes==healthy?"mesh/root/5":"mesh/root/6",boxes.get(binding.zones().getFirst().part()).part());
        }
        var zones=HeadProfiles.zones(HeadProfiles.bind(profile,wounded,0),wounded);
        assertTrue(HeadZone.contains(zones,new Vec3(0,4.5,.5)));
        var healthyZones=HeadProfiles.zones(HeadProfiles.bind(profile,healthy,0),healthy);
        assertTrue(HeadZone.contains(healthyZones,ModelContours.point(healthy.get(5),.5,.5,.5)));
    }
    // Части, которых модель может не нарисовать: шерсть стриженой овцы, рог козы, тыква снеговика, ковёр ламы,
    // скрещённые руки разбойника (в атаке и колдовстве модель рисует right_arm/left_arm).
    @Test void partsTheModelMayNotDrawDoNotRejectTheWholeLabel() {
        var limbs=HeadProfiles.loadBank("/ntmgunfeel-limbs.json",java.nio.file.Path.of("build/no-local-limbs.json"),false);
        record Case(boolean limb,String type,String drop,int zones) {}
        for(var c:List.of(new Case(false,"minecraft:sheep","/wool",1),new Case(true,"minecraft:sheep","/wool",4),
                new Case(true,"minecraft:sheep#baby","/wool",4),new Case(false,"minecraft:goat","/left_horn",3),
                new Case(false,"minecraft:goat","_horn",2),new Case(false,"minecraft:goat#baby","/left_horn",2),
                new Case(false,"minecraft:goat#baby","_horn",1),new Case(false,"minecraft:sheep#baby","/wool",1),new Case(false,"minecraft:snow_golem","block_layer",2),
                new Case(false,"minecraft:snow_golem","/blockDecos/",2),new Case(false,"minecraft:snow_golem","block",1),
                new Case(false,"minecraft:trader_llama","/bodyEquip/",1),
                new Case(false,"minecraft:llama","/bodyEquip/",1),new Case(false,"minecraft:llama","-",2),
                new Case(true,"minecraft:vindicator","_arm",4),new Case(true,"minecraft:vindicator","/arms",4),
                new Case(true,"minecraft:evoker","_arm",5),new Case(true,"minecraft:evoker","/arms",5),
                new Case(true,"minecraft:illusioner","_arm",5),new Case(true,"minecraft:illusioner","/arms",5))) {
            var profile=c.limb()?HeadProfiles.get(limbs,c.type()):HeadProfiles.get(c.type());
            var boxes=profile.parts().stream().filter(r->!r.name().contains(c.drop())).map(HeadProfilesTest::box).toList();
            assertTrue(boxes.size()<profile.parts().size() || c.drop().equals("-"),c+": нечего убирать");
            var binding=HeadProfiles.bind(profile,boxes,0);
            assertEquals(0,binding.missing(),c.toString());
            assertEquals(c.zones(),binding.zones().size(),c.toString());
        }
    }
    // CrankShaft держит все слои одежды вида в одном массиве draws, показан не каждый: у жителя тип со шляпой
    // (draws/0) или без (draws/1), профессия (draws/2) есть не у всех. Номер в имени части разметку не ломает.
    @Test void anOverlayDrawnUnderAnotherDrawNumberKeepsTheLabel() {
        var limbs=HeadProfiles.loadBank("/ntmgunfeel-limbs.json",java.nio.file.Path.of("build/no-local-limbs.json"),false);
        record Case(String type,String from,String to,String drop,int zones,int missing) {}
        for(var c:List.of(new Case("minecraft:zombie_villager#baby","/draws/1/","/draws/0/","-",4,0),
                new Case("minecraft:zombie_villager","/draws/0/","/draws/1/","-",8,0),
                new Case("minecraft:zombie_villager","/draws/0/","/draws/1/","/draws/2/",7,0),
                new Case("minecraft:zombie_villager","-","-","/draws/2/",7,0),
                new Case("minecraft:villager","/draws/0/","/draws/1/","-",4,0),
                new Case("minecraft:villager#baby","/draws/0/","/draws/1/","-",4,0),
                // у вардена оба draw рисуются сразу: пропавший draws/1 не подменяется своим же draws/0
                new Case("minecraft:warden","-","-","/draws/1/",2,2))) {
            var profile=HeadProfiles.get(limbs,c.type());
            var boxes=profile.parts().stream().filter(r->!r.name().contains(c.drop()))
                    .map(r->box(r,r.name().replace(c.from(),c.to()))).toList();
            assertTrue(boxes.stream().anyMatch(b->b.part().contains(c.to())) || c.to().equals("-"),c+": нечего переименовать");
            var binding=HeadProfiles.bind(profile,boxes,0);
            assertEquals(c.missing(),binding.missing(),c.toString());
            assertEquals(c.zones(),binding.zones().size(),c.toString());
            var used=new HashSet<Integer>();for(var z:binding.zones()) assertTrue(used.add(z.part()),c+": одна коробка в двух зонах");
        }
    }
    // Стенд, житель-фермер из пустыни: тип без шляпы (draws/1) и руки профессии (draws/2) того же размера — берётся
    // ближайший номер. Пиджак типа обрезан по текстуре (1,23 → 0,68) и не сходится: пропадает только его зона.
    @Test void theNearestDrawWinsAndATrimmedRobeDropsOnlyItsZone() {
        var limbs=HeadProfiles.loadBank("/ntmgunfeel-limbs.json",java.nio.file.Path.of("build/no-local-limbs.json"),false);
        var profile=HeadProfiles.get(limbs,"minecraft:villager");
        var boxes=new ArrayList<ModelBox>();
        for(var r:profile.parts()) boxes.add(box(r,r.name().replace("/draws/0/","/draws/1/")));
        var arms=profile.parts().stream().filter(r->r.name().endsWith("/draws/0/tree/arms")).findFirst().orElseThrow();
        boxes.add(box(arms,arms.name().replace("/draws/0/","/draws/2/")));
        var binding=HeadProfiles.bind(profile,boxes,0);
        assertEquals(0,binding.missing());assertEquals(4,binding.zones().size());
        var zoneBoxes=binding.zones().stream().map(z->boxes.get(z.part()).part()).toList();
        assertTrue(zoneBoxes.contains("mesh/root/overlayLayer/draws/1/tree/arms"),zoneBoxes.toString());
        int jacket=-1;for(int i=0;i<boxes.size();i++) if(boxes.get(i).part().endsWith("/body/jacket")) jacket=i;
        var j=boxes.get(jacket);var s=HeadProfiles.size(j);
        boxes.set(jacket,new ModelBox(j.part(),j.x(),j.y(),j.z(),s[0],0,0,0,s[1]*.68/1.23,0,0,0,s[2]));
        binding=HeadProfiles.bind(profile,boxes,0);
        assertEquals(0,binding.missing());assertEquals(3,binding.zones().size());
    }
    // Без Flywheel (его backend выключен) часть приходит по кубам: несколько коробок с одним именем без "mesh/".
    // Кубы одной части сопоставляются как одна коробка вокруг них, как её рисует Flywheel.
    @Test void withoutFlywheelThePartsCubesAreMatchedAsOnePart() {
        var llama=HeadProfiles.get("minecraft:llama");
        var whole=new ArrayList<ModelBox>();var cubes=new ArrayList<ModelBox>();
        for(var r:llama.parts()) {
            if(r.name().contains("/bodyEquip/")) continue;
            var name=r.name().substring("mesh/".length());whole.add(box(r,name));
            if(!name.equals("root/head")) { cubes.add(box(r,name));continue; }
            // голова ламы — шея и морда: нижний и верхний куб, верхний уже по x и z
            var c=r.center();var s=r.size();double low=s[1]*.6;
            cubes.add(new ModelBox(name,c.x-s[0]/2,c.y-s[1]/2,c.z-s[2]/2,s[0],0,0,0,low,0,0,0,s[2]*.6));
            cubes.add(new ModelBox(name,c.x-s[0]/4,c.y-s[1]/2+low,c.z-s[2]/2,s[0]/2,0,0,0,s[1]-low,0,0,0,s[2]));
        }
        var expected=ModelHeads.resolve("minecraft:llama",whole,0);
        assertEquals("dataset",expected.source());assertEquals(1,expected.zones().size());
        var resolved=ModelHeads.resolve("minecraft:llama",cubes,0);
        assertEquals("dataset",resolved.source(),resolved.reason());assertEquals(0,resolved.missing());
        int head=0;var b=whole.stream().filter(x->x.part().equals("root/head")).findFirst().orElseThrow();
        for(int i=0;i<=10;i++) for(int j=0;j<=10;j++) for(int k=0;k<=10;k++) {
            var point=ModelContours.point(b,i/10.,j/10.,k/10.);
            boolean in=HeadZone.contains(expected.zones(),point);if(in) head++;
            assertEquals(in,HeadZone.contains(resolved.zones(),point),point.toString());
        }
        assertTrue(head>100,"в голове есть точки зоны: "+head);
        // коза без рогов: голова без Flywheel — только уши (бородка плоская), головой остаётся нос
        var goat=HeadProfiles.get("minecraft:goat");var ears=new ArrayList<ModelBox>();
        for(var r:goat.parts()) {
            var name=r.name().substring("mesh/".length());
            if(name.endsWith("_horn")) continue;
            if(!name.equals("root/head")) { ears.add(box(r,name));continue; }
            var c=r.center();var s=r.size();
            for(double x:new double[]{c.x-s[0]/2,c.x+s[0]/2-.1875}) ears.add(new ModelBox(name,x,c.y+s[1]/2-.125,c.z,.1875,0,0,0,.125,0,0,0,.0625));
        }
        var horns=ModelHeads.resolve("minecraft:goat",ears,0);
        assertEquals("dataset",horns.source(),horns.reason());assertEquals(1,horns.zones().size());
        // Flywheel даёт часть целиком; коробки с "mesh/" не склеиваются
        var flywheel=cubes.stream().map(x->x.part().startsWith("root/head")?new ModelBox("mesh/"+x.part(),x.x(),x.y(),x.z(),x.ux(),x.uy(),x.uz(),x.vx(),x.vy(),x.vz(),x.wx(),x.wy(),x.wz()):x).toList();
        assertEquals(flywheel,HeadProfiles.unite(llama,flywheel));
        // кубы, повёрнутые друг к другу, — не одна рамка: не склеиваются
        var turned=new ArrayList<>(cubes);int at=-1;for(int i=0;i<turned.size();i++) if(turned.get(i).part().equals("root/head")) at=i;
        var t=turned.get(at);double q=Math.sqrt(.5);
        turned.set(at,new ModelBox(t.part(),t.x(),t.y(),t.z(),t.ux()*q,0,t.ux()*q,0,t.vy(),0,-t.wz()*q,0,t.wz()*q));
        assertEquals(turned,HeadProfiles.unite(llama,turned));
        // профиль из таких же кубов (имя в нём не одно) их не склеивает
        var bank=HeadProfiles.all();
        var repeated=bank.keySet().stream().filter(type->{
            var names=new HashMap<String,Integer>();for(var r:bank.get(type).parts()) names.merge(r.name(),1,Integer::sum);
            return names.values().stream().anyMatch(n->n>1);
        }).sorted().toList();
        assertFalse(repeated.isEmpty());
        for(var type:repeated) {
            var profile=bank.get(type);
            var own=profile.parts().stream().map(r->box(r,r.name().replaceFirst("^mesh/",""))).toList();
            assertEquals(own,HeadProfiles.unite(profile,own),type);
        }
    }
    private static ModelBox box(HeadProfiles.Ref ref) { return box(ref,ref.name()); }
    private static ModelBox box(HeadProfiles.Ref ref,String name) {
        var c=ref.center();var s=ref.size();
        return new ModelBox(name,c.x-s[0]/2,c.y-s[1]/2,c.z-s[2]/2,s[0],0,0,0,s[1],0,0,0,s[2]);
    }
}
