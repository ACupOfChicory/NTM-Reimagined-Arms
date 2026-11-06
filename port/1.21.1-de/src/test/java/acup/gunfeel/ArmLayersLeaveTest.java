package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

// port: 26.2's ArmLayersLeaveTest without the render state, which the port's ArmLayers does not keep (1.21.1 hands
// the layers the player itself). ArmLayers keeps no player of a world left for the main menu: the player is held
// weakly and let go on leaving
class ArmLayersLeaveTest {
    private static Field field(String name) throws Exception {
        Field f = ArmLayers.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @Test
    void playerIsHeldWeakly() throws Exception {
        assertEquals(WeakReference.class, field("owner").getType());
    }

    @Test
    void leavingLetsThePlayerGo() throws Exception {
        Field owner = field("owner");
        Field prepared = field("prepared");
        Object player = new Object();
        // the erased type takes any object: a stand-in for the LocalPlayer of the world being left
        owner.set(null, new WeakReference<>(player));
        prepared.setBoolean(null, true);
        ArmLayers.leave();
        assertNull(((WeakReference<?>) owner.get(null)).get());
        assertFalse(prepared.getBoolean(null));
    }
}
