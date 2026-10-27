package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class MobFaceRayTest {
    @Test void edgeOnPaperStillGetsRealPerpendicularHitProbes() {
        var triangle=new MobRayOracle.Triangle(new Vec3(0,0,0),new Vec3(0,1,0),new Vec3(0,0,1));
        var surface=List.of(triangle);
        assertTrue(MobRayOracle.grid(surface).stream().noneMatch(MobRayOracle.Ray::hit));
        var probes=MobRayOracle.acrossFaces(surface);
        assertFalse(probes.isEmpty());
        assertTrue(probes.stream().allMatch(MobRayOracle.Ray::hit));
        assertTrue(probes.stream().allMatch(ray -> ray.from().x<0 && ray.to().x>0));
    }
}
