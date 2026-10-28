package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class AvatarHitPolicyTest {
    private List<ModelBox> player(boolean slim,float armAngle) {
        var root=LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE,slim),64,64).bakeRoot();
        root.getChild("right_arm").xRot=armAngle;
        return ModelPoseCapture.collect(root,new PoseStack(),0,0,0,ModelPoseCaptureTest.PARTS).boxes();
    }
    private List<ModelBox> filter(String id,List<ModelBox> boxes) { return AvatarHitPolicy.filter(Identifier.parse(id),boxes); }
    @Test void classicAndSlimKeepTheirRealArmWidthAndVisibleSkinLayer() {
        for(boolean slim:new boolean[]{false,true}) for(String type:List.of("minecraft:player","minecraft:mannequin")) {
            var original=player(slim,0);var body=filter(type,original);
            assertEquals(original,body);assertTrue(body.size()>6);
            var arm=body.stream().filter(b->b.part().equals("root/right_arm")).findFirst().orElseThrow();
            var sleeve=original.stream().filter(b->b.part().endsWith("right_sleeve")).findFirst().orElseThrow();
            assertEquals((slim?3:4)/16.0,Math.sqrt(arm.ux()*arm.ux()+arm.uy()*arm.uy()+arm.uz()*arm.uz()),1e-7);
            double outside=arm.x()-.125/16;
            double middleY=arm.y()+arm.vy()/2;
            assertTrue(sleeve.clip(outside,middleY,-2,outside,middleY,2)>=0,"до исправления край декоративного рукава ловил пулю");
            assertTrue(body.stream().anyMatch(b->b.clip(outside,middleY,-2,outside,middleY,2)>=0),"видимый второй слой тоже является геометрией попадания");
            assertTrue(body.stream().anyMatch(b->b.clip(arm.x()+arm.ux()/2,middleY,-2,arm.x()+arm.ux()/2,middleY,2)>=0));
        }
    }
    @Test void posedArmsAndMeshCaptureNamesKeepTheSamePhysicalBody() {
        var standing=filter("minecraft:player",player(true,0));
        var posed=filter("minecraft:player",player(true,.7F));
        assertNotEquals(standing,posed);
        var meshNames=player(true,.7F).stream().map(b->new ModelBox("mesh/"+b.part(),b.x(),b.y(),b.z(),b.ux(),b.uy(),b.uz(),b.vx(),b.vy(),b.vz(),b.wx(),b.wy(),b.wz(),b.mesh())).toList();
        assertEquals(meshNames,filter("minecraft:mannequin",meshNames));
    }
    @Test void OtherHumanoidsAndUnknownPlayerLayoutsRemainUntouched() {
        var boxes=player(false,0);
        for(String type:List.of("minecraft:zombie","minecraft:husk","minecraft:giant","minecraft:skeleton","hbm:entity_mob_mask_man","foreign:player"))
            assertSame(boxes,filter(type,boxes),type);
        var unknown=List.of(boxes.getFirst());
        assertSame(unknown,filter("minecraft:player",unknown));
        var withExtraLimb=new ArrayList<>(boxes);
        withExtraLimb.add(new ModelBox("root/tail",0,0,0,1,0,0,0,1,0,0,0,1));
        assertSame(withExtraLimb,filter("minecraft:player",withExtraLimb),"нестандартное тело игрока не должно лишиться дополнительных частей");
    }
}
