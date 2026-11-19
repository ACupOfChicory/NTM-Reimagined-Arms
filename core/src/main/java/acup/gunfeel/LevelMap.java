package acup.gunfeel;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * State kept per level: the key is the version's level object (26.2: its ServerLevel), opaque to core and held
 * weakly, as a WeakHashMap holds it; each method is that map's own operation, so a registry moved onto it keeps its
 * calls as they were (a get then a put stays so).
 *
 * <p>A value that holds its level, or entities of it, keeps the key alive: such a value is forgotten by the version
 * when the level goes ({@link #remove}), the weak key never lets it go. Not thread-safe: the server thread, or the
 * owner's own lock.
 */
public final class LevelMap<V> {
    private final Map<Object, V> map = new WeakHashMap<>();

    public @Nullable V get(Object level) {
        return map.get(level);
    }

    public @Nullable V put(Object level, V value) {
        return map.put(level, value);
    }

    public V computeIfAbsent(Object level, Function<Object, ? extends V> make) {
        return map.computeIfAbsent(level, make);
    }

    public @Nullable V remove(Object level) {
        return map.remove(level);
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public void clear() {
        map.clear();
    }
}
