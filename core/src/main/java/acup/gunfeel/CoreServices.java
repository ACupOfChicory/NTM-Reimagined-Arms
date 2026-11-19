package acup.gunfeel;

import java.util.Iterator;
import java.util.ServiceLoader;

/**
 * How core reaches what only a Minecraft version can give (a folder, the world, the time): core declares a small
 * interface, the version's adapter implements it and names its class in {@code META-INF/services/<interface>}
 * (src/main/resources), and core loads it here once. In the game and in the version's tests the version's class is
 * found; core's own tests, built without the game, get the fallback. The mod is one jar (one module), so the
 * provider is looked up with core's own class loader.
 */
public final class CoreServices {
    private CoreServices() {}

    /** The version's implementation of {@code type}, or {@code fallback} where there is none; two is an error. */
    public static <T> T load(Class<T> type, T fallback) {
        Iterator<T> it = ServiceLoader.load(type, type.getClassLoader()).iterator();
        if (!it.hasNext()) return fallback;
        T found = it.next();
        if (it.hasNext()) throw new IllegalStateException("two implementations of " + type.getName() + ": "
                + found.getClass().getName() + " and " + it.next().getClass().getName());
        return found;
    }
}
