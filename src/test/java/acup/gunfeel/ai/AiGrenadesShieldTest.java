package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

// A near throw's shield: a mob down behind its cover, all of it off the burst, or not.
class AiGrenadesShieldTest {
    static final int STONE = Voxel.of(Voxel.SOLID, true, 2, false, false, false);
    static final int GLASS = Voxel.of(Voxel.SOLID, false, 0, false, false, false);
    static final int SLAB = Voxel.of(Voxel.LOW, false, 0, false, false, false);
    static final double LIE = AiProne.SIZE.height(), CROUCH = 1.5;

    /** the ground at y 63 and under; a wall along x = 1, from z -3 to 3, this high and of this block */
    static VoxelRay.Source wall(int high, int block) {
        return (x, y, z) -> y < 64 ? STONE : x == 1 && z >= -3 && z <= 3 && y < 64 + high ? block : Voxel.OPEN;
    }

    @Test
    void lyingBehindALowWallItIsShielded() {
        // it lies at x 0.5, against the wall; the burst 8 blocks on, on the ground
        assertTrue(AiGrenades.shielded(wall(1, STONE), 0.5, 64, 0.5, LIE, new Vec3(8.5, 64, 0.5)));
        // a burst at the wall's end, a little to its side: still behind it
        assertTrue(AiGrenades.shielded(wall(1, STONE), 0.5, 64, 0.5, LIE, new Vec3(8.5, 64, 2.5)));
    }

    @Test
    void crouchingOverALowWallItIsNot() {
        // its head and shoulders over a block's wall
        assertFalse(AiGrenades.shielded(wall(1, STONE), 0.5, 64, 0.5, CROUCH, new Vec3(8.5, 64, 0.5)));
        // behind a wall two blocks high it is
        assertTrue(AiGrenades.shielded(wall(2, STONE), 0.5, 64, 0.5, CROUCH, new Vec3(8.5, 64, 0.5)));
    }

    @Test
    void noShieldPastTheWallsEndAboveItOrThroughGlassOrASlab() {
        // past the wall's end, on its side
        assertFalse(AiGrenades.shielded(wall(1, STONE), 0.5, 64, 0.5, LIE, new Vec3(2.5, 64, 9.5)));
        // a burst up on a roof, seeing down over the wall
        assertFalse(AiGrenades.shielded(wall(1, STONE), 0.5, 64, 0.5, LIE, new Vec3(4.5, 68, 0.5)));
        // glass and a slab do not hide
        assertFalse(AiGrenades.shielded(wall(1, GLASS), 0.5, 64, 0.5, LIE, new Vec3(8.5, 64, 0.5)));
        assertFalse(AiGrenades.shielded(wall(1, SLAB), 0.5, 64, 0.5, LIE, new Vec3(8.5, 64, 0.5)));
        // in the open
        assertFalse(AiGrenades.shielded((x, y, z) -> y < 64 ? STONE : Voxel.OPEN, 0.5, 64, 0.5, LIE, new Vec3(8.5, 64, 0.5)));
    }
}
