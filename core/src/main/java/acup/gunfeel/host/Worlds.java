package acup.gunfeel.host;

import acup.gunfeel.CoreServices;
import acup.gunfeel.LevelMap;

/**
 * One WorldView per level. LevelWorld holds its level strongly, so the version calls {@link #forget} on level
 * unload (LevelUnload); otherwise the weak key cannot go. Locked on the class: the unload of a client level comes on
 * the client's thread while the server's reads it.
 */
public final class Worlds {
    private Worlds() {
    }

    /** the version's maker of a level's WorldView (26.2: Wiring.world) */
    public interface Port {
        WorldView world(Object level);

        Port MISSING = level -> { throw new UnsupportedOperationException("no Worlds here"); };
    }

    private static final class PortOf {
        static final Port PORT = CoreServices.load(Port.class, Port.MISSING);
    }

    private static final LevelMap<WorldView> WORLDS = new LevelMap<>();

    public static WorldView of(Object level) {
        return of(PortOf.PORT, level);
    }

    static synchronized WorldView of(Port port, Object level) {
        // get first: computeIfAbsent's port::world would be a new object on every call, and this is read every tick
        WorldView v = WORLDS.get(level);
        if (v == null) {
            v = port.world(level);
            WORLDS.put(level, v);
        }
        return v;
    }

    public static synchronized void forget(Object level) {
        WORLDS.remove(level);
    }
}
