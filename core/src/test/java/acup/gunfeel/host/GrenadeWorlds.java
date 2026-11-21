package acup.gunfeel.host;

import java.lang.reflect.Proxy;

/** An isolated level view for grenade selection tests. */
public final class GrenadeWorlds {
    private GrenadeWorlds() {
    }

    public static void put(Object level, int minY) {
        put(level, minY, new long[1]);
    }

    public static void put(Object level, WorldView world) {
        Worlds.of(l -> world, level);
    }

    public static void put(Object level, int minY, long[] time) {
        Worlds.of(l -> (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[] {WorldView.class},
                (p, m, a) -> {
                    if (m.getName().equals("minY")) return minY;
                    if (m.getName().equals("gameTime")) return time[0];
                    if (m.getName().equals("state")) return 0;
                    if (m.getName().equals("air")) return true;
                    throw new AssertionError(m.getName());
                }), level);
    }
}
