package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import acup.gunfeel.host.PierceShots;
import acup.gunfeel.host.PierceShotsContract;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

class PierceShots26Test extends PierceShotsContract {
    private final PierceShots shots = CoreServices.root().port(PierceShots.class);

    @Override protected PierceShots shots() { return shots; }
    @Override protected Object point(double x, double y, double z) { return new Vec3(x, y, z); }
    @Override protected Object hit(Object point) { return new BlockHitResult((Vec3) point, Direction.UP, BlockPos.ZERO, false); }

    @Test
    void originalGameOverloadsDelegateAndRetainPenetration() {
        PierceSteps steps = new PierceSteps();
        steps.penetration.restore(3.25);
        steps.start(Vec3.ZERO);
        steps.pierced((BlockHitResult) hit(new Vec3(2, 0, 0)), (BlockHitResult) hit(new Vec3(3, 0, 0)), 42F, 0.5);
        steps.start(new Vec3(1, 2, 3));
        assertEquals(Double.doubleToRawLongBits(3.25), Double.doubleToRawLongBits(steps.penetration.spent()));
        // An empty ledger never reads the bullet or the point.
        steps.enter(null, null);
        assertThrows(NullPointerException.class, () -> steps.finish(null));
    }
}
