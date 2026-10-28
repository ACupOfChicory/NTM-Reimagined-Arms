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
        assertNull(HeadProfiles.get("minecraft:zombie#baby"),"Непроверенный детёныш не наследует разметку взрослого по ID");
    }
    private ModelBox box(String name,double y) { return new ModelBox(name,0,y,0,1,0,0,0,1,0,0,0,1); }
    @Test void allManualTypesArePackagedAndNoHeadIsAuthoritative() {
        assertEquals(218,HeadProfiles.count());
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
}
