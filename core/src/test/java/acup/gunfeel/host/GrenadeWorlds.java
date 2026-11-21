package acup.gunfeel.host;

import java.lang.reflect.Proxy;

/** An isolated level view for grenade selection tests. */
public final class GrenadeWorlds {
    private GrenadeWorlds() {
    }

    public static void put(Object level, int minY) {
        Worlds.of(l -> (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[] {WorldView.class},
                (p, m, a) -> {
                    if (m.getName().equals("minY")) return minY;
                    throw new AssertionError(m.getName());
                }), level);
    }
}
