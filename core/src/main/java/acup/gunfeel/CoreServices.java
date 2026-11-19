package acup.gunfeel;

import java.util.Iterator;
import java.util.ServiceLoader;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import acup.gunfeel.host.NtmFeature;
import acup.gunfeel.host.Ports;

/**
 * How core reaches what only a Minecraft version can give (a folder, the world, the time): core declares a small
 * interface (a port), the version implements it and hands it out through its root ({@link Ports}), and core asks for
 * it here, once per port. The root is looked for in this one place, the first time anything asks:
 * <ul>
 * <li>the root the version gave by {@link #install}, from its entry point before anything else ran; else
 * <li>the one class named in {@code META-INF/services/acup.gunfeel.host.Ports} (26.2: {@code acup.gunfeel.Wiring}),
 * looked up with core's own class loader, the mod being one jar; else
 * <li>none: core's own tests, built without the game, get every fallback.
 * </ul>
 * A version whose class loader cannot do ServiceLoader calls {@link #install} and that is all.
 */
public final class CoreServices {
    private CoreServices() {}

    private static volatile @Nullable Ports given;
    private static volatile boolean asked;

    /**
     * The version gives its root itself: before core asks for any port (the mod's entry point, first line). Later is a
     * mistake, since what core took meanwhile was the other root's or a fallback.
     */
    public static synchronized void install(Ports root) {
        if (asked) throw new IllegalStateException("core asked for its ports before the version gave its root");
        given = root;
    }

    /** The version's implementation of {@code type}, or {@code fallback} where it has none. */
    public static <T> T load(Class<T> type, T fallback) {
        Ports root = Root.PORTS;
        if (root == null) return fallback;
        T found = root.port(type);
        return found != null ? found : fallback;
    }

    /** Whether this version's NTM can do {@code f}; without a version, nothing. */
    public static boolean ntm(NtmFeature f) {
        Ports root = Root.PORTS;
        return root != null && root.ntm().contains(f);
    }

    /** The version's root, or null without a version. */
    public static @Nullable Ports root() {
        return Root.PORTS;
    }

    /** What the root's NTM can do, empty without a version. */
    public static Set<NtmFeature> ntm() {
        Ports root = Root.PORTS;
        return root == null ? Set.of() : root.ntm();
    }

    // the root, found once, the first time it is asked for
    private static final class Root {
        static final @Nullable Ports PORTS = find();
    }

    private static synchronized @Nullable Ports find() {
        asked = true;
        if (given != null) return given;
        Iterator<Ports> it = ServiceLoader.load(Ports.class, Ports.class.getClassLoader()).iterator();
        if (!it.hasNext()) return null;
        Ports found = it.next();
        if (it.hasNext()) throw new IllegalStateException("two roots of ports: " + found.getClass().getName() + " and "
                + it.next().getClass().getName());
        return found;
    }
}
