package acup.gunfeel.water;

import java.util.IdentityHashMap;
import java.util.Map;

import com.hbm.items.weapon.sedna.BulletConfig;

// 26.2's NtmFields (X560): NTM's static fields the water rules know configs by, looked up by name: optional knowledge,
// so a field another NTM has not leaves out only what it tells (Compat would turn the whole core off for a missing
// direct reference)
final class NtmFields {
    private NtmFields() {
    }

    private static final String FACTORY = "com.hbm.items.weapon.sedna.factory.";

    // {class, field, field, ...} rows: each field's config, by identity, with its name
    static Map<BulletConfig, String> configs(String[][] rows) {
        Map<BulletConfig, String> out = new IdentityHashMap<>();
        for (String[] row : rows) for (int i = 1; i < row.length; i++) {
            Object c = get(row[0], row[i]);
            if (c instanceof BulletConfig) out.put((BulletConfig) c, row[i]);
        }
        return out;
    }

    static Object get(String factory, String field) {
        try {
            return Class.forName(FACTORY + factory, true, BulletConfig.class.getClassLoader()).getField(field).get(null);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            return null;
        }
    }
}
