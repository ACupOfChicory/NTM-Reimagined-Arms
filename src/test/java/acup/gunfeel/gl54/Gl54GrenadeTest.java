package acup.gunfeel.gl54;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

class Gl54GrenadeTest {
    @Test
    void areaDamageIsEighteenInTheMiddleTwelveAtABlockSixAtTwo() {
        assertEquals(18F, Gl54Grenade.areaDamageAt(0), 1e-4);
        assertEquals(12F, Gl54Grenade.areaDamageAt(1), 1e-4);
        assertEquals(6F, Gl54Grenade.areaDamageAt(2), 1e-4);
        assertEquals(0F, Gl54Grenade.areaDamageAt(3), 1e-4);
        assertEquals(0F, Gl54Grenade.areaDamageAt(5), 1e-4);
    }

    @Test
    void glassTakesTheWholeFallOffAndBreaksOutToAboutTwoAndAThirdBlocks() {
        assertEquals(45F, Gl54Grenade.damageAt(0), 1e-4);
        assertTrue(Gl54Grenade.damageAt(2.3) >= 10F);
        assertTrue(Gl54Grenade.damageAt(2.4) < 10F);
    }

    @Test
    void armedAfterThreeBlocks() {
        assertFalse(Gl54Grenade.armed(0));
        assertFalse(Gl54Grenade.armed(2.99));
        assertTrue(Gl54Grenade.armed(3.0));
        assertTrue(Gl54Grenade.armed(40));
        assertEquals(4F, Gl54Grenade.DUD_DAMAGE);
    }

    @Test
    void blastRadiusIsThreeBlocksAndNtmsSizeHalfOfIt() {
        assertEquals(3F, Gl54Grenade.RADIUS);
        // EntityProcessorCross doubles the size it is given: 1.5 reaches 3 blocks
        assertEquals(3F, 2 * (Gl54Grenade.RADIUS / 2F));
    }

    @Test
    void speedAndDropAreFortyMillimetres() {
        assertEquals(4.7F, Gl54Grenade.SPEED);
        assertEquals(0.0245, Gl54Grenade.GRAVITY * Gl54Grenade.SPEED, 1e-9);
    }

    @Test
    void distanceToABlockIsToItsNearestPoint() {
        BlockPos b = new BlockPos(10, 64, 10);
        assertEquals(0.0, Gl54Grenade.distanceToBlock(new Vec3(10.5, 64.5, 10.5), b), 1e-9);
        assertEquals(1.0, Gl54Grenade.distanceToBlock(new Vec3(9.0, 64.5, 10.5), b), 1e-9);
        assertEquals(2.0, Gl54Grenade.distanceToBlock(new Vec3(13.0, 64.5, 10.5), b), 1e-9);
        assertEquals(Math.sqrt(2), Gl54Grenade.distanceToBlock(new Vec3(9.0, 63.0, 10.5), b), 1e-9);
    }

    @Test
    void candidatesAreTheBlocksWithinTheRadius() {
        var near = Gl54Grenade.candidates(new Vec3(0.5, 0.5, 0.5));
        assertTrue(near.contains(new BlockPos(0, 0, 0)));
        assertTrue(near.contains(new BlockPos(3, 0, 0)));
        assertFalse(near.contains(new BlockPos(4, 0, 0)));
        assertFalse(near.contains(new BlockPos(3, 3, 3)));
    }
}
