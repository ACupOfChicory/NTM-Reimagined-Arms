package acup.gunfeel;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import acup.gunfeel.host.EntityTypeTable;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;

/**
 * 26.2's EntityTypeTable: a type's registry id by its number (EntityTypeNumbers), worked out once a number; a tag by
 * its text, its TagKey made once (both sides ask: the client's whizz and decals, the server's hits, one JVM in single
 * player). A tag question is the entity's own {@code is(tag)}: its type's builtInRegistryHolder().is(tag).
 */
final class EntityTypeNames implements EntityTypeTable {
    /** by number; grown under the lock, read without it (an array once filled in is never changed) */
    private volatile String[] names = new String[64];
    /** the tags by their text; empty: the text is no id */
    private final ConcurrentHashMap<String, Optional<TagKey<EntityType<?>>>> tags = new ConcurrentHashMap<>();

    @Override
    public String name(int type) {
        String[] a = names;
        if (type >= 0 && type < a.length) {
            String n = a[type];
            if (n != null) return n;
        }
        return fill(type);
    }

    private synchronized String fill(int type) {
        String[] a = names;
        if (type >= 0 && type < a.length && a[type] != null) return a[type];
        EntityType<?> t = EntityTypeNumbers.type(type);
        if (t == null) throw new IllegalArgumentException("no entity type has number " + type);
        String n = BuiltInRegistries.ENTITY_TYPE.getKey(t).toString();
        String[] b = java.util.Arrays.copyOf(a, Math.max(a.length, Integer.highestOneBit(type) << 1));
        b[type] = n;
        names = b;
        return n;
    }

    @Override
    public boolean tagged(int type, String tag) {
        Optional<TagKey<EntityType<?>>> key = tags.computeIfAbsent(tag, s -> {
            Identifier t = Identifier.tryParse(s);
            return t == null ? Optional.empty() : Optional.of(TagKey.create(Registries.ENTITY_TYPE, t));
        });
        if (key.isEmpty()) return false;
        EntityType<?> t = EntityTypeNumbers.type(type);
        if (t == null) throw new IllegalArgumentException("no entity type has number " + type);
        return t.builtInRegistryHolder().is(key.get());
    }
}
