package acup.gunfeel.water;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.hbm.items.weapon.sedna.BulletConfig;

// NTM's static fields the water rules know configs by, looked up by name: optional knowledge, so a field another NTM
// has not leaves out only what it tells (Compat would turn the whole core off for a missing direct reference).
// port: 26.2's NtmFields (27023c4); NTM 1.7.10 X5863 has no XFactoryNPC nor XFactoryPile, so those rows find nothing;
// its pile's debris is in a tile entity's class, named in full
final class NtmFields {
    private NtmFields() {
    }

    private static final String FACTORY = "com.hbm.items.weapon.sedna.factory.";

    // the 5.56 mm incendiary rounds (r556_inc_*, clones of the plain ones with the plain ammo): their onImpact lambda
    static final Supplier<Object> R556_INCENDIARY = memo(() -> get("XFactory556mm", "INCENDIARY"));

    // {class, field, field, ...} rows: each field's config, by identity, with its name
    static Map<BulletConfig, String> configs(String[][] rows) {
        Map<BulletConfig, String> out = new IdentityHashMap<>();
        for (String[] row : rows) for (int i = 1; i < row.length; i++) {
            Object o = get(row[0], row[i]);
            if (o instanceof BulletConfig) out.put((BulletConfig) o, row[i]);
        }
        return out;
    }

    // factory: a class in NTM's weapon factories by its simple name, or any class by its full name
    static Object get(String factory, String field) {
        try {
            return Class.forName(factory.indexOf('.') >= 0 ? factory : FACTORY + factory, true, BulletConfig.class.getClassLoader()).getField(field).get(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }

    private static <T> Supplier<T> memo(Supplier<T> f) {
        return new Supplier<T>() {
            private volatile boolean done;
            private T value;

            @Override
            public T get() {
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
