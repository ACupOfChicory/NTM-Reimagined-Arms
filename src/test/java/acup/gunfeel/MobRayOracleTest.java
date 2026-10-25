package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class MobRayOracleTest {
    private static final List<MobRayOracle.Triangle> FACE=List.of(new MobRayOracle.Triangle(
            new Vec3(0,0,0),new Vec3(2,0,0),new Vec3(0,2,0)));

    @Test void emptyCornerOfBoundsIsNotSurface() {
        assertTrue(MobRayOracle.hit(FACE,new Vec3(.4,.4,-1),new Vec3(.4,.4,1)));
        assertFalse(MobRayOracle.hit(FACE,new Vec3(1.6,1.6,-1),new Vec3(1.6,1.6,1)));
    }
    @Test void bothFacesAndFiniteSegment() {
        assertTrue(MobRayOracle.hit(FACE,new Vec3(.4,.4,1),new Vec3(.4,.4,-1)));
        assertFalse(MobRayOracle.hit(FACE,new Vec3(.4,.4,-2),new Vec3(.4,.4,-1)));
        assertFalse(MobRayOracle.hit(FACE,new Vec3(.4,.4,1),new Vec3(1,.4,1)));
    }
}
