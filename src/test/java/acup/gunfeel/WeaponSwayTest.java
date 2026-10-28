package acup.gunfeel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WeaponSwayTest {
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
