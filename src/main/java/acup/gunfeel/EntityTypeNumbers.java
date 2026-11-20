package acup.gunfeel;

import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.world.entity.EntityType;

/**
 * The mod's own number of an entity type (EntityTypeTable), by the EntityType object: the game's raw registry id
 * would not do, NeoForge renumbers the registries when a client joins a server (ItemNumbers does the same for items).
 * Numbers from 0, in the order the types are first met.
 */
final class EntityTypeNumbers {
    private static final ConcurrentHashMap<EntityType<?>, Integer> NUMBERS = new ConcurrentHashMap<>();
    /** the types by number (EntityTypeNames), written under add's lock */
    private static final ConcurrentHashMap<Integer, EntityType<?>> TYPES = new ConcurrentHashMap<>();
    private static int next;

    private EntityTypeNumbers() {
    }

    static int number(EntityType<?> type) {
        Integer n = NUMBERS.get(type);
        return n != null ? n : add(type);
    }

    private static synchronized int add(EntityType<?> type) {
        Integer n = NUMBERS.get(type);
        if (n != null) return n;
        int m = next++;
        TYPES.put(m, type);
        NUMBERS.put(type, m);
        return m;
    }

    /** the type with this number, null for a number none has */
    static EntityType<?> type(int number) {
        return TYPES.get(number);
    }
}
