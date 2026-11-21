package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import net.minecraft.world.phys.Vec3;

class AiGrenadesNullTest {
    @Test void ownCoverNeverNeedsTheBurstOrSourceEvenWithLiveComparison() {
        assertNull(AiGrenades.alike);
        assertFalse(AiGrenadesOld.cover(null, null, 0, 0, 0, true));
        assertFalse(AiGrenades.cover(null, (Vec3) null, 0, 0, 0, true));
        AiGrenadesAlike alike = new AiGrenadesAlike();
        AiGrenades.alike = alike;
        try {
            assertFalse(AiGrenades.cover(null, (Vec3) null, 0, 0, 0, true));
            assertEquals(1, alike.covered[AiGrenadesAlike.Input.COVER.ordinal()]);
            assertEquals(0, alike.differ);
        } finally {
            AiGrenades.alike = null;
        }
    }

    @Test void missingCellNeverNeedsTheBurst() throws Exception {
        Method old = near(AiGrenadesOld.class), current = near(AiGrenades.class);
        for (double distance : new double[] {0, 289, Double.NaN}) {
            assertEquals(false, old.invoke(null, Long.MIN_VALUE, null, distance));
            assertEquals(false, current.invoke(null, Long.MIN_VALUE, null, distance));
        }
        assertInstanceOf(NullPointerException.class,
                assertThrows(InvocationTargetException.class, () -> old.invoke(null, 0L, null, 289.0)).getCause());
        assertInstanceOf(NullPointerException.class,
                assertThrows(InvocationTargetException.class, () -> current.invoke(null, 0L, null, 289.0)).getCause());
    }

    private static Method near(Class<?> type) throws Exception {
        Method m = type.getDeclaredMethod("near", long.class, Vec3.class, double.class);
        m.setAccessible(true);
        return m;
    }

    @Test void otherGeometryStillNeedsTheBurst() {
        assertThrows(NullPointerException.class, () -> AiGrenadesOld.behind(null, null, 0, 0, 0));
        assertThrows(NullPointerException.class, () -> AiGrenades.behind(null, (Vec3) null, 0, 0, 0));
        assertThrows(NullPointerException.class, () -> AiGrenadesOld.cover(null, null, 0, 0, 0, false));
        assertThrows(NullPointerException.class, () -> AiGrenades.cover(null, (Vec3) null, 0, 0, 0, false));
        assertThrows(NullPointerException.class, () -> AiGrenadesOld.shielded(null, 0, 0, 0, 0, null));
        assertThrows(NullPointerException.class, () -> AiGrenades.shielded(null, 0, 0, 0, 0, (Vec3) null));
        for (boolean own : new boolean[] {false, true}) {
            assertThrows(NullPointerException.class, () -> AiGrenadesOld.shielded(null, 0, 0, 0, 0, null, own));
            assertThrows(NullPointerException.class, () -> AiGrenades.shielded(null, 0, 0, 0, 0, (Vec3) null, own));
            assertThrows(NullPointerException.class, () -> AiGrenadesOld.safe(null, null, Long.MIN_VALUE, 0, own));
            assertThrows(NullPointerException.class, () -> AiGrenades.safe(null, (Vec3) null, Long.MIN_VALUE, 0, own));
        }
    }

    @Test void bothPlanningEntriesStillRejectANullTarget() {
        assertThrows(NullPointerException.class, () -> AiGrenadesOld.plan(null, null, null, false, 0));
        assertThrows(NullPointerException.class, () -> AiGrenades.plan(null, null, null, false, 0));
        assertThrows(NullPointerException.class, () -> AiGrenadesOld.planStick(null, 0, 0, 0, null, p -> true));
        assertThrows(NullPointerException.class, () -> AiGrenades.planStick(null, 0, 0, 0, null, p -> true));
    }
}
