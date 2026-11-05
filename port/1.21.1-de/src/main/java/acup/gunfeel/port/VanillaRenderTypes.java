package acup.gunfeel.port;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

// What a vanilla 1.21.1 RenderType keeps to itself (its CompositeState), read as DE's RenderTypeInfo reads the
// texture: NeoForge 1.21.1 runs with Mojang's names, so reflection finds them.
final class VanillaRenderTypes {
    private static final Method STATE;
    private static final Field CULL;
    private static final Field ENABLED;

    static {
        Method state = null;
        Field cull = null;
        Field enabled = null;
        try {
            state = Class.forName("net.minecraft.client.renderer.RenderType$CompositeRenderType").getDeclaredMethod("state");
            state.setAccessible(true);
            cull = RenderType.CompositeState.class.getDeclaredField("cullState");
            cull.setAccessible(true);
            enabled = RenderStateShard.BooleanStateShard.class.getDeclaredField("enabled");
            enabled.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            acup.gunfeel.GunFeel.LOGGER.warn("The state of 1.21.1 RenderTypes cannot be read: every type is taken as culled", e);
        }
        STATE = state;
        CULL = cull;
        ENABLED = enabled;
    }

    private VanillaRenderTypes() {
    }

    // whether the type draws only the faces turned to the camera (its cull state; true when it cannot be read)
    static boolean cull(RenderType type) {
        if (STATE == null || CULL == null || ENABLED == null || !STATE.getDeclaringClass().isInstance(type)) return true;
        try {
            return ENABLED.getBoolean(CULL.get(STATE.invoke(type)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return true;
        }
    }
}
