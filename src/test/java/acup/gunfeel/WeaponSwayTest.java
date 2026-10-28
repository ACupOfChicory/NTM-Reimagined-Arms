package acup.gunfeel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WeaponSwayTest {
    @Test void fastTurnsAcrossHalfAndMultipleRevolutionsNeverReverseTheWeapon() {
        for(float aiming:new float[]{0,1})for(float direction:new float[]{-1,1}) {
            for(float base:new float[]{-1080,0,1080}) {
                float previous=0;
                for(float difference:new float[]{90,179,180,181,220,360,540,720}) {
                    var turn=WeaponSway.fromAngles(0,base+direction*difference,0,base,aiming);
                    assertTrue(turn.yaw()*direction>0,"резкий поворот "+difference+" не меняет сторону оружия");
                    assertTrue(turn.roll()*direction>0);
                    assertTrue(turn.x()*direction>0);
                    assertTrue(Math.abs(turn.yaw())>=previous);previous=Math.abs(turn.yaw());
                }
                float view=base+direction*440,arm=base;
                for(int tick=0;tick<24;tick++) {
                    var turn=WeaponSway.fromAngles(0,view,0,arm,aiming);
                    assertTrue(turn.yaw()*direction>=0,"догоняющая рука не перескакивает через камеру");
                    arm+=(view-arm)*.5F;
                }
            }
        }
    }
    @Test void aimingUsesSuperbWarfareTurnInsteadOfFiveTimesStrongerNtmPitch() {
        var hip=WeaponSway.sample(10,20,0);
        var ads=WeaponSway.sample(10,20,1);
        assertEquals(.5F,hip.pitch(),1e-6);assertEquals(.5F,hip.yaw(),1e-6);assertEquals(1F,hip.roll(),1e-6);
        assertEquals(.475F,ads.pitch(),1e-6);assertEquals(.475F,ads.yaw(),1e-6);assertEquals(.5F,ads.roll(),1e-6);
        assertEquals(.015625F,hip.x(),1e-6);assertEquals(.007421875F,ads.x(),1e-6);
        assertTrue(ads.pitch()<10*.1F*2.5F/5);
    }
    @Test void abruptTurnHasNoAccumulationAndSettlesWithThePlayersArmFilter() {
        for(float aiming:new float[]{0,.25F,.5F,.75F,1}) {
            float peak=0;
            for(int tick=0;tick<40;tick++) {
                // Резкий поворот камеры на 90°, затем штатное сглаживание рук Minecraft.
                float difference=90*(float)Math.pow(.5,tick);
                var turn=WeaponSway.sample(0,difference,aiming);
                assertTrue(Math.abs(turn.yaw())<=2.25F);
                if(tick==0)peak=turn.yaw();
                if(tick>=8)assertTrue(Math.abs(turn.yaw())<peak/100);
            }
            var stopped=WeaponSway.sample(0,0,aiming);
            assertEquals(new WeaponSway.Turn(0,0,0,0),stopped);
        }
        var extreme=WeaponSway.sample(10000,-10000,0);
        assertEquals(20,extreme.pitch());assertEquals(-20,extreme.yaw());assertEquals(-20,extreme.roll());
    }
}
