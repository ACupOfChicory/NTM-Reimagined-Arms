package acup.gunfeel.host;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Raw identities, fresh stacks and live reads required of each version's gun-table port. */
public abstract class GunBalanceContract {
    protected abstract GunBalance reads();
    protected abstract Object gun();
    protected abstract Object ammo();

    @Test
    void rawTokensAndFreshStacks() {
        GunBalance r = reads();
        assertTrue(r.isGun(gun()));
        assertFalse(r.isGun(null));
        Object a = r.freshStack(gun()), b = r.freshStack(gun());
        assertNotSame(a, b);
        assertSame(gun(), r.item(a));
        assertSame(gun(), r.item(b));
        assertFalse(r.empty(a));
        List<Object> seen = new ArrayList<>();
        Map<Object, Float> click = new IdentityHashMap<>();
        r.walk(gun(), a, new GunBalance.Walk() {
            private Object pending;
            @Override public boolean begin(Object ammo, List<Object> out, Map<Object, Float> values) {
                assertSame(seen, out); assertSame(click, values);
                assertNull(pending);
                if (values.containsKey(ammo)) return false;
                pending = ammo; out.add(ammo); return true;
            }
            @Override public float damage(Object ammo, float base, GunBalance reads) {
                assertSame(r, reads); assertSame(pending, ammo);
                return base * reads.damageMult(ammo) * (reads.min(ammo) + reads.max(ammo)) / 2F;
            }
            @Override public void finish(Object ammo, float damage, float split, Map<Object, Float> values) {
                assertSame(pending, ammo); assertSame(click, values);
                values.put(ammo, damage * split); pending = null;
            }
        }, seen, click);
        assertTrue(seen.stream().anyMatch(x -> x == ammo()));
        assertNotNull(r.ammoItem(ammo()));
        assertNotNull(r.ammoStack(ammo()));
    }

    @Test
    void missingThrowsForEveryRead() throws Exception {
        for (var method : GunBalance.class.getDeclaredMethods()) {
            Object[] args = new Object[method.getParameterCount()];
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < args.length; i++) if (types[i] == int.class) args[i] = 0;
            var error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> method.invoke(GunBalance.MISSING, args));
            assertInstanceOf(UnsupportedOperationException.class, error.getCause());
        }
    }
}
