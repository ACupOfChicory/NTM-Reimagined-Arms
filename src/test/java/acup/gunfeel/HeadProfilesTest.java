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
        // коза без рогов: голова без Flywheel — только уши (бородка плоская): голова — уши и нос, как под Flywheel
        var goat=HeadProfiles.get("minecraft:goat");var ears=new ArrayList<ModelBox>();
        for(var r:goat.parts()) {
            var name=r.name().substring("mesh/".length());
            if(name.endsWith("_horn")) continue;
            if(!name.equals("root/head")) { ears.add(box(r,name));continue; }
            var c=r.center();var s=r.size();
            for(double x:new double[]{c.x-s[0]/2,c.x+s[0]/2-.1875}) ears.add(new ModelBox(name,x,c.y+s[1]/2-.125,c.z,.1875,0,0,0,.125,0,0,0,.0625));
        }
        var horns=ModelHeads.resolve("minecraft:goat",ears,0);
        assertEquals("dataset",horns.source(),horns.reason());assertEquals(2,horns.zones().size());
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
    // Без Flywheel слой поверх моба приходит под именами самой модели (root/<часть>): копией модели (утопленник,
    // страй, болотник) или никак, когда текстура слоя ничего не добавляет (глаза паука, свечение вардена). Часть слоя
    // (".../draws/N/tree/<часть>") берёт коробку root/<часть>.
    @Test void withoutFlywheelALayerTakesTheModelsBoxOfItsName() {
        var limbs=HeadProfiles.loadBank("/ntmgunfeel-limbs.json",java.nio.file.Path.of("build/no-local-limbs.json"),false);
        record Case(boolean limb,String type,boolean copy,int zones) {}
        for(var c:List.of(new Case(false,"minecraft:drowned",true,2),new Case(true,"minecraft:drowned",true,8),
                new Case(false,"minecraft:stray",true,2),new Case(false,"minecraft:bogged",true,2),
                new Case(false,"minecraft:spider",false,1),new Case(true,"minecraft:spider",false,15),
                new Case(false,"minecraft:warden",false,1),new Case(true,"minecraft:warden",false,4),
                new Case(false,"minecraft:enderman",false,2),new Case(true,"minecraft:enderman",false,6))) {
            var profile=c.limb()?HeadProfiles.get(limbs,c.type()):HeadProfiles.get(c.type());
            var vanilla=new ArrayList<ModelBox>();var flywheel=new ArrayList<ModelBox>();
            for(var r:profile.parts()) {
                var tree=r.name().indexOf("/tree/");
                if(tree<0) { vanilla.add(box(r,r.name().substring("mesh/".length())));flywheel.add(box(r)); }
                else if(c.copy()) vanilla.add(box(r,"root/"+r.name().substring(tree+"/tree/".length())));
            }
            var cpu=HeadProfiles.unite(profile,vanilla);
            var binding=HeadProfiles.bind(profile,cpu,0);
            assertEquals(0,binding.missing(),c.toString());assertEquals(c.zones(),binding.zones().size(),c.toString());
            // с Flywheel пропавший слой остаётся пропавшим
            assertTrue(HeadProfiles.bind(profile,flywheel,0).missing()>0,c+": Flywheel");
        }
        // свинозомби: штаны без Flywheel — целый куб (под Flywheel обрезаны по текстуре): он и есть штаны, ноги — свои
        var piglin=HeadProfiles.get(limbs,"minecraft:zombified_piglin");var boxes=new ArrayList<ModelBox>();
        for(var r:piglin.parts()) {
            var name=r.name().substring("mesh/".length());
            if(!name.endsWith("_pants")) { boxes.add(box(r,name));continue; }
            var cc=r.center();var size=r.size();
            boxes.add(new ModelBox(name,cc.x-size[0]/2,cc.y+size[1]/2-.781,cc.z-size[2]/2,size[0],0,0,0,.781,0,0,0,size[2]));
        }
        var binding=HeadProfiles.bind(piglin,HeadProfiles.unite(piglin,boxes),0);
        assertEquals(0,binding.missing());assertEquals(6,binding.zones().size());
    }
    // Кость скелета и одежда поверх неё без Flywheel — одна коробка root/<часть>: у страя она по размеру одежды
    // (кость её берёт тоже), у болотника одежда закрывает полкости (под Flywheel обрезана по текстуре), и коробка не
    // подходит ни к одной из двух частей — сопоставляется как коробка вокруг обеих. Позы — снимок стенда без Flywheel
    // (центр и размер кубов).
    @Test void aBoneAndTheClothesOverItShareOneBoxWithoutFlywheel() {
        var limbs=HeadProfiles.loadBank("/ntmgunfeel-limbs.json",java.nio.file.Path.of("build/no-local-limbs.json"),false);
        var base=List.of("root/head 0 1.751 0 .5 .5 .5","root/right_arm -.3346 1.1272 .0106 .125 .75 .125",
                "root/left_leg .1269 .376 0 .125 .75 .125","root/left_arm .3346 1.1272 -.0106 .125 .75 .125",
                "root/right_leg -.1269 .376 0 .125 .75 .125","root/body 0 1.126 0 .5 .75 .25");
        var stray=List.of("root/head 0 1.751 0 .5312 .5312 .5312","root/right_arm -.3968 1.1327 .0106 .2812 .7813 .2812",
                "root/left_leg .1206 .376 0 .2812 .7812 .2812","root/left_arm .3968 1.1327 -.0106 .2812 .7813 .2812",
                "root/right_leg -.1206 .376 0 .2812 .7812 .2812","root/body 0 1.126 0 .5312 .7812 .2812");
        var bogged=List.of("root/head 0 1.751 0 .525 .525 .525","root/right_arm -.3968 1.1327 .0106 .275 .775 .275",
                "root/left_arm .3968 1.1327 -.0106 .275 .775 .275","root/body 0 1.126 0 .525 .775 .275");
        for(var c:List.of(Map.entry("minecraft:stray",stray),Map.entry("minecraft:bogged",bogged))) {
            var profile=HeadProfiles.get(limbs,c.getKey());var boxes=new ArrayList<ModelBox>();
            var lines=new ArrayList<>(base);lines.addAll(c.getValue());
            for(var line:lines) {
                var f=line.split(" ");double[] v=new double[6];for(int i=0;i<6;i++) v[i]=Double.parseDouble(f[i+1]);
                boxes.add(new ModelBox(f[0],v[0]-v[3]/2,v[1]-v[4]/2,v[2]-v[5]/2,v[3],0,0,0,v[4],0,0,0,v[5]));
            }
            var cpu=HeadProfiles.unite(profile,boxes);var binding=HeadProfiles.bind(profile,cpu,0);
            assertEquals(0,binding.missing(),c.getKey());assertEquals(profile.zones().size(),binding.zones().size(),c.getKey());
            // кость и одежда левой руки — одна коробка
            assertEquals(2,binding.zones().stream().filter(z->cpu.get(z.part()).part().equals("root/left_arm")).count(),c.getKey());
        }
        // слой рисуется не всегда (лама без ковра): часть модели нашлась — слой, который на неё не похож, коробку не
        // берёт, даже если коробка вокруг обеих на неё похожа; его зону покрывает instead
        var profile=new HeadProfiles.Profile(false,List.of(
                new HeadProfiles.Ref("mesh/root/head",new double[]{1,1,1},0,new Vec3(0,0,0),-1),
                new HeadProfiles.Ref("mesh/root/overlayLayer/draws/0/tree/head",new double[]{.6,1.1,.6},0,new Vec3(0,.05,0),0)),
                List.of(new HeadProfiles.Zone(0,1,List.of(),0),new HeadProfiles.Zone(1,1,List.of(),0)));
        var binding=HeadProfiles.bind(profile,List.of(new ModelBox("root/head",-.5,-.5,-.5,1,0,0,0,1,0,0,0,1)),0);
        assertEquals(0,binding.missing());assertEquals(1,binding.zones().size());assertEquals(0,binding.zones().getFirst().part());
    }
    // Без Flywheel коробка части — её целые кубы, а профиль снят с сетки Flywheel: она обрезана по непрозрачным пикселям
    // текстуры (шляпа торговца, штаны брутального пиглина) или с плоскими кубами, которые у крупного моба без Flywheel
    // выброшены целиком (PlaneHitPolicy: плавник древнего стража, ветки скрипуна). Единственная коробка с именем части,
    // вложенная в неё или вмещающая её по всем осям, — эта часть. Старые модели NTM под Flywheel — root/tree/<часть>,
    // без него root/<часть>. Позы — снимок стенда без Flywheel (центр и размер кубов).
    @Test void withoutFlywheelAPartsWholeCubesStandForTheMeshFlywheelCuts() {
        var limbs=HeadProfiles.loadBank("/ntmgunfeel-limbs.json",java.nio.file.Path.of("build/no-local-limbs.json"),false);
        var poses=Map.ofEntries(
                Map.entry("minecraft:wandering_trader","""
                        root/head -0 1.7 -0 0.4688 0.5859 0.4688
                        root/head/nose -0 1.466 0.293 0.1172 0.2344 0.1172
                        root/head/hat -0 1.7 -0 0.5285 0.6457 0.5285
                        root/arms -0.3516 1.146 0.1385 0.2344 0.4688 0.2344
                        root/arms 0.3516 1.146 0.1385 0.2344 0.4688 0.2344
                        root/arms 0 1.06 0.2184 0.4688 0.2344 0.2344
                        root/left_leg 0.1172 0.3525 0 0.2344 0.7031 0.2344
                        root/body -0 1.056 -0 0.4688 0.7031 0.3516
                        root/body/jacket -0 0.8213 -0 0.5273 1.23 0.4102
                        root/right_leg -0.1172 0.3525 -0 0.2344 0.7031 0.2344"""),
                Map.entry("minecraft:piglin_brute","""
                        root/head -0 1.751 -0 0.625 0.5 0.5
                        root/head 0 1.626 0.2813 0.25 0.25 0.0625
                        root/head 0.1563 1.563 0.2813 0.0625 0.125 0.0625
                        root/head -0.1562 1.563 0.2813 0.0625 0.125 0.0625
                        root/head/right_ear -0.3911 1.761 -0 0.0625 0.3125 0.25
                        root/head/left_ear 0.3784 1.75 -0 0.0625 0.3125 0.25
                        root/right_arm -0.3968 1.133 0.0107 0.25 0.75 0.25
                        root/left_leg 0.1206 0.376 0 0.25 0.75 0.25
                        root/left_leg/left_pants 0.1206 0.376 0 0.2812 0.7812 0.2812
                        root/left_arm 0.3968 1.133 -0.0107 0.25 0.75 0.25
                        root/right_leg -0.1206 0.376 -0 0.25 0.75 0.25
                        root/right_leg/right_pants -0.1206 0.376 -0 0.2812 0.7812 0.2812
                        root/body -0 1.126 -0 0.5 0.75 0.25
                        root/body/jacket -0 1.126 -0 0.5313 0.7812 0.2813"""),
                Map.entry("minecraft:elder_guardian","""
                        root/head -0 1.177 -0 1.762 1.762 2.35
                        root/head -1.028 1.177 0 0.2937 1.762 1.762
                        root/head 1.028 1.177 -0 0.2937 1.762 1.762
                        root/head -0 2.205 -0 1.762 0.2937 1.762
                        root/head -0 0.1492 -0 1.762 0.2937 1.762
                        root/head/spike10 0.8539 0.3234 -0 0.2937 1.322 0.2937
                        root/head/spike11 -0.8592 0.3182 0 0.2937 1.322 0.2937
                        root/head/spike6 0.8732 1.177 -0.8732 0.2937 1.322 0.2937
                        root/head/spike7 -0.8773 1.177 -0.8773 0.2937 1.322 0.2937
                        root/head/spike8 -0 0.3067 -0.8707 0.2937 1.322 0.2937
                        root/head/spike9 0 0.3179 0.8594 0.2937 1.322 0.2937
                        root/head/eye 0.2729 1.177 1.138 0.2937 0.2937 0.1469
                        root/head/tail0 -0.1438 1.177 -1.609 0.5875 0.5875 1.175
                        root/head/tail0/tail1 -0.3257 1.177 -2.583 0.4406 0.4406 1.028
                        root/head/tail0/tail1/tail2 -0.6625 1.177 -3.352 0.2937 0.2937 0.8812
                        root/head/spike0 -0 2.053 -0.8754 0.2937 1.322 0.2938
                        root/head/spike1 0 2.054 0.8764 0.2937 1.322 0.2937
                        root/head/spike2 0.8675 2.045 -0 0.2937 1.322 0.2937
                        root/head/spike3 -0.8569 2.034 0 0.2938 1.322 0.2937
                        root/head/spike4 -0.8543 1.177 0.8543 0.2937 1.322 0.2937
                        root/head/spike5 0.8622 1.177 0.8622 0.2937 1.322 0.2937"""),
                Map.entry("minecraft:creaking","""
                        root/root/upper_body/head -0.25 2.188 0 0.375 0.625 0.375
                        root/root/upper_body/head -0.25 2.595 0 0.375 0.1875 0.375
                        root/root/upper_body/right_arm -0.5313 1.22 -0.0937 0.1875 1.312 0.1875
                        root/root/upper_body/right_arm -0.5312 0.4385 -0.0937 0.1875 0.25 0.1875
                        root/root/upper_body/left_arm 0.4063 1.313 -0.0313 0.1875 1 0.1875
                        root/root/upper_body/left_arm 0.4062 1.938 -0.0312 0.1875 0.25 0.1875
                        root/root/upper_body/left_arm 0.4063 0.6885 -0.0313 0.1875 0.25 0.1875
                        root/root/upper_body/body 0.125 1.407 -0.0312 0.375 0.8125 0.3125
                        root/root/upper_body/body -0.25 1.657 -0.0313 0.375 0.4375 0.3125
                        root/root/left_leg 0.0938 0.501 -0.0313 0.1875 1 0.1875
                        root/root/right_leg -0.1563 0.5948 -0.0312 0.1875 1.188 0.1875
                        root/root/right_leg -0.1562 1.282 -0.0313 0.1875 0.1875 0.1875"""),
                Map.entry("minecraft:breeze","""
                        root/body/head 0 1.532 0.1375 0.625 0.1875 0.25
                        root/body/head -0 1.563 -0 0.5 0.5 0.5
                        root/body/head/eyes 0 1.532 0.1375 0.625 0.1875 0.25
                        root/body/head/eyes -0 1.563 -0 0.5 0.5 0.5
                        root/body/rods/rod_2 0.0747 0.8517 0.1934 0.125 0.5 0.125
                        root/body/rods/rod_3 0.1301 0.8517 -0.1614 0.125 0.5 0.125
                        root/body/rods/rod_1 -0.2048 0.8517 -0.032 0.125 0.5 0.125
                        root/wind_body/wind_bottom -0 0.2198 -0 0.3125 0.4375 0.3125
                        root/wind_body/wind_bottom/wind_mid -0.0223 0.626 -0.0313 0.75 0.375 0.75
                        root/wind_body/wind_bottom/wind_mid -0.0223 0.626 -0.0313 0.5 0.375 0.5
                        root/wind_body/wind_bottom/wind_mid -0.0223 0.626 -0.0313 0.3125 0.375 0.3125
                        root/wind_body/wind_bottom/wind_mid/wind_top -0.0535 1.063 -0.0402 1.125 0.5 1.125
                        root/wind_body/wind_bottom/wind_mid/wind_top -0.0535 1.063 -0.0402 0.75 0.5 0.75
                        root/wind_body/wind_bottom/wind_mid/wind_top -0.0535 1.063 -0.0402 0.3125 0.5 0.3125"""),
                Map.entry("hbm:entity_mob_gold_creeper","""
                        root/head -0 1.376 -0 0.5 0.5 0.5
                        root/right_front_leg -0.125 0.1885 0.25 0.25 0.375 0.25
                        root/right_hind_leg -0.125 0.1885 -0.25 0.25 0.375 0.25
                        root/left_hind_leg 0.125 0.1885 -0.25 0.25 0.375 0.25
                        root/body 0 0.751 0 0.5 0.75 0.25
                        root/left_front_leg 0.125 0.1885 0.25 0.25 0.375 0.25"""),
                Map.entry("hbm:entity_elder_one","""
                        root/head 0 18.77 7.031 6.25 9.375 4.688
                        root/head/beak 0 18.77 10.94 6.25 3.125 3.125
                        root/left_leg 2.344 3.931 0.7812 4.688 7.812 4.688
                        root/right_leg -2.344 3.931 0.7812 4.688 7.812 4.688
                        root/right_wing -8.485 14.89 0 1.562 6.25 9.375
                        root/left_wing 8.485 14.89 -0 1.562 6.25 9.375
                        root/body 0 12.53 -0 9.375 12.5 9.375"""));
        record Case(String type,boolean limb,int zones) {}
        for(var c:List.of(new Case("minecraft:wandering_trader",false,3),new Case("minecraft:piglin_brute",true,5),
                new Case("minecraft:elder_guardian",true,15),new Case("minecraft:creaking",false,1),new Case("minecraft:creaking",true,4),
                new Case("minecraft:breeze",false,1),new Case("hbm:entity_mob_gold_creeper",false,1),new Case("hbm:entity_mob_gold_creeper",true,4),
                new Case("hbm:entity_elder_one",false,2),new Case("hbm:entity_elder_one",true,4))) {
            var profile=c.limb()?HeadProfiles.get(limbs,c.type()):HeadProfiles.get(c.type());var boxes=new ArrayList<ModelBox>();
            for(var line:poses.get(c.type()).strip().split("\n")) {
                var f=line.strip().split(" ");double[] v=new double[6];for(int i=0;i<6;i++) v[i]=Double.parseDouble(f[i+1]);
                boxes.add(new ModelBox(f[0],v[0]-v[3]/2,v[1]-v[4]/2,v[2]-v[5]/2,v[3],0,0,0,v[4],0,0,0,v[5]));
            }
            var binding=HeadProfiles.bind(profile,HeadProfiles.unite(profile,boxes),0);
            assertEquals(0,binding.missing(),c.toString());assertEquals(c.zones(),binding.zones().size(),c.toString());
        }
        // коробка больше по одной оси и меньше по другой — не та часть; коробки Flywheel (mesh/) не берутся
        var profile=new HeadProfiles.Profile(false,List.of(new HeadProfiles.Ref("mesh/root/head",new double[]{1,1,1},0,new Vec3(0,0,0),-1)),
                List.of(new HeadProfiles.Zone(0,1,List.of(),0)));
        assertEquals(1,HeadProfiles.bind(profile,List.of(new ModelBox("root/head",-1,-.25,-.5,2,0,0,0,.5,0,0,0,1)),0).missing());
        assertEquals(0,HeadProfiles.bind(profile,List.of(new ModelBox("root/head",-1,-1,-.5,2,0,0,0,2,0,0,0,1)),0).missing());
        assertEquals(0,HeadProfiles.bind(profile,List.of(new ModelBox("root/head",-.25,-.25,-.5,.5,0,0,0,.5,0,0,0,1)),0).missing());
        assertEquals(1,HeadProfiles.bind(profile,List.of(new ModelBox("mesh/root/head",-1,-1,-.5,2,0,0,0,2,0,0,0,1)),0).missing());
        // две коробки одного имени (кубы не в одной рамке, не склеены) — какая из них часть, не сказать
        assertEquals(1,HeadProfiles.bind(profile,List.of(new ModelBox("root/head",-1,-1,-.5,2,0,0,0,2,0,0,0,1),
                new ModelBox("root/head",-.25,-.25,-.5,.5,0,0,0,.5,0,0,0,1)),0).missing());
        // слой рисуется не всегда (лама без ковра): коробку модели, в которую он вложен, он так не берёт
        var layered=new HeadProfiles.Profile(false,List.of(
                new HeadProfiles.Ref("mesh/root/head",new double[]{1,1,1},0,new Vec3(0,0,0),-1),
                new HeadProfiles.Ref("mesh/root/overlayLayer/draws/0/tree/head",new double[]{.6,.6,.6},0,new Vec3(0,0,0),0)),
                List.of(new HeadProfiles.Zone(0,1,List.of(),0),new HeadProfiles.Zone(1,1,List.of(),0)));
        assertEquals(1,HeadProfiles.bind(layered,List.of(new ModelBox("root/head",-.5,-.5,-.5,1,0,0,0,1,0,0,0,1)),0).zones().size());
    }
    private static ModelBox box(HeadProfiles.Ref ref) { return box(ref,ref.name()); }
    private static ModelBox box(HeadProfiles.Ref ref,String name) {
        var c=ref.center();var s=ref.size();
        return new ModelBox(name,c.x-s[0]/2,c.y-s[1]/2,c.z-s[2]/2,s[0],0,0,0,s[1],0,0,0,s[2]);
    }
}
