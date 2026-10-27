package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class HeadProfilesTest {
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
        assertEquals("dataset",result.source());assertTrue(result.zones().isEmpty());assertTrue(result.missing()>0);
    }
    @Test void unknownNamedModelUsesProceduralLayerInstead() {
        var result=ModelHeads.resolve("foreign:creature",List.of(box("root/body",0),box("root/head",1)),0);
        assertEquals("procedural",result.source());
        assertTrue(HeadZone.contains(result.zones(),new Vec3(.5,1.5,.5)));
        assertFalse(HeadZone.contains(result.zones(),new Vec3(.5,.5,.5)));
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
