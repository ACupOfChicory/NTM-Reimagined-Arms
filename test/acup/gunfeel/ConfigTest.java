package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

// Every setting lives in three places in Config: the field, apply() and snapshot(). These tests catch one of the
// three being forgotten: a value that never takes effect, or one that is not reset when switching guns.
class ConfigTest {
    @AfterEach
    void restore() {
        apply(builtin());
    }

    @Test
    void everySettingHasAKey() {
        List<String> missing = new ArrayList<>();
        for (Field f : settings()) if (!Config.isKey(f.getName())) missing.add(f.getName());
        assertTrue(missing.isEmpty(), "not in snapshot(), so never reset to default: " + missing);
    }

    @Test
    void everyKeyIsASetting() {
        List<String> stray = new ArrayList<>();
        for (String k : Config.keys()) {
            try {
                Config.class.getField(k);
            } catch (NoSuchFieldException e) {
                stray.add(k);
            }
        }
        assertTrue(stray.isEmpty(), "keys without a field of that name: " + stray);
    }

    @Test
    void defaultsReadBackUnchanged() {
        // what snapshot() writes into a fresh config file must give the same values when read back
        List<Object> before = values();
        apply(builtin());
        List<Object> after = values();
        List<Field> fields = settings();
        for (int i = 0; i < fields.size(); i++) {
            Object a = before.get(i), b = after.get(i);
            if (a instanceof float[] x) assertArrayEquals(x, (float[]) b, fields.get(i).getName());
            else assertEquals(a, b, fields.get(i).getName());
        }
    }

    @Test
    void everyKeyIsRead() throws IllegalAccessException {
        List<String> ignored = new ArrayList<>();
        for (Field f : settings()) {
            Object def = f.get(null);
            Properties p = builtin();
            p.setProperty(f.getName(), changed(f, def));
            apply(p);
            Object now = f.get(null);
            boolean same = def instanceof float[] d ? java.util.Arrays.equals(d, (float[]) now) : def.equals(now);
            if (same) ignored.add(f.getName());
            apply(builtin());
        }
        assertTrue(ignored.isEmpty(), "apply() ignores: " + ignored);
    }

    @Test
    void badNumbersKeepTheDefault() throws IllegalAccessException {
        for (Field f : settings()) {
            if (f.getType() != float.class && f.getType() != int.class && f.getType() != float[].class) continue;
            Object def = f.get(null);
            Properties p = builtin();
            p.setProperty(f.getName(), "not a number");
            try {
                apply(p);
            } catch (RuntimeException e) {
                fail(f.getName() + " = 'not a number' throws " + e);
            }
            Object now = f.get(null);
            if (def instanceof float[] d) assertArrayEquals(d, (float[]) now, f.getName());
            else if (!f.getName().equals("ammoMenuColor")) assertEquals(def, now, f.getName());
            apply(builtin());
        }
    }

    @Test
    void colourIsHex() {
        Properties p = builtin();
        p.setProperty("ammoMenuColor", "#00ff7f");
        apply(p);
        assertEquals(0x00FF7F, Config.ammoMenuColor);
    }

    @Test
    void timesAndSpeedsStayPositive() {
        Properties p = builtin();
        p.setProperty("cameraTime", "0");
        p.setProperty("modelSpeed", "-3");
        p.setProperty("hitVolume", "100");
        apply(p);
        assertTrue(Config.cameraTime > 0F);
        assertTrue(Config.modelSpeed > 0F);
        assertTrue(Config.hitVolume <= 4F);
    }

    // ------------------------------------------------------------------

    static List<Field> settings() {
        List<Field> out = new ArrayList<>();
        for (Field f : Config.class.getFields()) {
            int m = f.getModifiers();
            if (Modifier.isStatic(m) && !Modifier.isFinal(m)) out.add(f);
        }
        out.sort(java.util.Comparator.comparing(Field::getName));
        assertTrue(out.size() > 50, "found only " + out.size() + " settings");
        return out;
    }

    static List<Object> values() {
        List<Object> out = new ArrayList<>();
        for (Field f : settings()) {
            try {
                Object v = f.get(null);
                out.add(v instanceof float[] a ? a.clone() : v);
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        return out;
    }

    static Properties builtin() {
        Properties p = new Properties();
        for (String k : Config.keys()) p.setProperty(k, Config.codeValue(k));
        return p;
    }

    static String changed(Field f, Object def) {
        if (f.getName().equals("ammoMenuColor")) return String.format("%06x", ((Integer) def) ^ 0x00FF00);
        if (def instanceof Boolean b) return Boolean.toString(!b);
        if (def instanceof Float x) {
            // NaN (= "use another setting") and fixed points of the formula, like 0.02, would come back the same
            float y = Float.isNaN(x) ? 1.5F : x * 0.5F + 0.01F;
            return Float.toString(y == x ? x + 1F : y);
        }
        if (def instanceof Integer x) return Integer.toString(x / 2 + 1);
        if (def instanceof float[] a) return (a[0] + 0.5F) + "," + (a[1] + 0.5F) + "," + (a[2] + 0.5F);
        return def + "x";
    }

    static void apply(Properties p) {
        try {
            Method m = Config.class.getDeclaredMethod("apply", Properties.class);
            m.setAccessible(true);
            m.invoke(null, p);
        } catch (InvocationTargetException e) {
            throw new AssertionError(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
