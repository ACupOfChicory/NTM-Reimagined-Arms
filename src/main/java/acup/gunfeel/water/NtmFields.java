package acup.gunfeel.water;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import com.hbm.items.weapon.sedna.BulletConfig;

// NTM's static fields the water rules know configs by, looked up by name: optional knowledge, so a field another NTM
// has not leaves out only what it tells (Compat would turn the whole core off for a missing direct reference)
final class NtmFields {
    private NtmFields() {
    }

    private static final String FACTORY = "com.hbm.items.weapon.sedna.factory.";

    // the 5.56 mm incendiary rounds (r556_inc_*, clones of the plain ones with the plain ammo): their onImpact lambda
    static final Supplier<@Nullable Object> R556_INCENDIARY = memo(() -> get("XFactory556mm", "INCENDIARY"));

    // {class, field, field, ...} rows: each field's config, by identity, with its name
    static Map<BulletConfig, String> configs(String[][] rows) {
        Map<BulletConfig, String> out = new IdentityHashMap<>();
        for (String[] row : rows) for (int i = 1; i < row.length; i++)
            if (get(row[0], row[i]) instanceof BulletConfig c) out.put(c, row[i]);
        return out;
    }

    static @Nullable Object get(String factory, String field) {
        try {
            return Class.forName(FACTORY + factory, true, BulletConfig.class.getClassLoader()).getField(field).get(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    private static <T> Supplier<@Nullable T> memo(Supplier<@Nullable T> f) {
        return new Supplier<>() {
            private volatile boolean done;
            private @Nullable T value;

            @Override
            public @Nullable T get() {
                // not kept while it is null: asked before NTM made its factories' fields, it would stay so
                if (!done) {
                    value = f.get();
                    done = value != null;
                }
                return value;
            }
        };
    }
}
