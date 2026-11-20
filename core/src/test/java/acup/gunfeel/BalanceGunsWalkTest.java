package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.host.GunBalance;
import acup.gunfeel.host.GunBalanceContract;

/** a5096a9's walk against core, with live read traces and identity cache snapshots. */
class BalanceGunsWalkTest extends GunBalanceContract {
    static final class Token {
        final String name;
        Token(String name) { this.name = name; }
        @Override public boolean equals(Object other) { return other instanceof Token; }
        @Override public int hashCode() { return 1; }
        @Override public String toString() { return name; }
    }

    static final class Fake implements GunBalance {
        final List<String> calls = new ArrayList<>();
        final Object gun = new Token("gun"), twin = new Token("twin"), foreign = new Token("foreign"), plain = new Token("plain");
        final Object a = new Token("a"), b = new Token("b"), missing = new Token("missing"), shooter = new Token("shooter");
        final Object c0 = new Token("c0"), c1 = new Token("c1"), r0 = new Token("r0"), r1 = new Token("r1"), r2 = new Token("r2");
        final Object[] first = {r0, r1}, second = {r2};
        final Map<Object, Object> stacks = new IdentityHashMap<>();
        final Map<Object, String> names = new IdentityHashMap<>();
        Object main = gun, off = twin;
        String fail;
        int failAt = 1, failSeen, fresh;
        boolean noItem, nullStack, empty, noAmmo;
        float base = 0x1.abcdep7F, mult = 0x1.abcdep-2F, split = 0x1.345678p2F;
        int min = 3, max = 8;
        Fake() {
            names.put(gun, "gun_test"); names.put(twin, "gun_test"); names.put(foreign, "foreign");
            names.put(plain, "stone"); names.put(a, "ammo_standard_a"); names.put(b, "ammo_b"); names.put(missing, "special");
        }
        void call(String op, Object... args) {
            calls.add(op + Arrays.toString(args));
            if (op.equals(fail) && ++failSeen == failAt) throw new IllegalStateException("failed:" + op);
        }
        void reset() { calls.clear(); fresh = 0; failSeen = 0; }
        @Override public Object itemId(Object item) { call("registry"); call("itemId", item); return item; }
        @Override public Object stackId(Object stack) { call("registry"); Object item = item(stack); call("itemId", item); return item; }
        @Override public String namespace(Object id) { call("namespace", id); return id == foreign || id == plain ? "other" : "hbm"; }
        @Override public String path(Object id) { call("path", id); return names.get(id); }
        @Override public boolean isGun(Object item) { call("isGun", item); return item == gun || item == twin || item == foreign; }
        @Override public Object freshStack(Object item) { call("freshStack", item); Object stack = new Token("fresh" + fresh++); stacks.put(stack, item); return stack; }
        int configCount(Object gun) { call("configCount", gun); return 2; }
        Object config(Object gun, Object stack, int index) { call("config", gun, stack, index); return index == 0 ? c0 : c1; }
        Object[] receivers(Object config, Object stack) { call("receivers", config, stack); return config == c0 ? first : second; }
        Object magazine(Object receiver, Object stack) { call("magazine", receiver, stack); return receiver; }
        List<?> accepted(Object magazine) { call("accepted", magazine); return noAmmo ? List.of() : magazine == r0 ? List.of(a, b, a) : List.of(b); }
        float baseDamage(Object receiver, Object stack) { call("baseDamage", receiver, stack); return base; }
        float split(Object receiver, Object stack) { call("split", receiver, stack); return split; }
        @Override public void walk(Object gun, Object stack, Walk visit, List<Object> out, Map<Object, Float> click) {
            for (int c = 0; c < configCount(gun); c++) {
                for (Object rec : receivers(config(gun, stack, c), stack)) {
                    for (Object b : accepted(magazine(rec, stack))) {
                        if (!visit.begin(b, out, click)) continue;
                        float damage = visit.damage(b, baseDamage(rec, stack), this);
                        visit.finish(b, damage, split(rec, stack), click);
                    }
                }
            }
        }
        @Override public Object ammoItem(Object ammo) { call("ammoItem", ammo); return noItem ? null : ammo; }
        @Override public Object ammoStack(Object ammo) { call("ammoStack", ammo); if (nullStack) return null; stacks.put(ammo, ammo); return ammo; }
        @Override public int ammoId(Object ammo) { call("ammoId", ammo); return ammo == a ? 1 : ammo == b ? 2 : 3; }
        @Override public boolean empty(Object stack) { call("empty", stack); return empty; }
        @Override public Object item(Object stack) { call("item", stack); return stacks.get(stack); }
        @Override public float damageMult(Object ammo) { call("damageMult", ammo); return mult; }
        @Override public int min(Object ammo) { call("min", ammo); return min; }
        @Override public int max(Object ammo) { call("max", ammo); return max; }
        @Override public Object mainHand(Object shooter) { call("mainHand", shooter); stacks.put(shooter, main); return shooter; }
        @Override public Object offHand(Object shooter) { call("offHand", shooter); stacks.put(shooter, off); return shooter; }
    }

    private final Fake f = new Fake();
    private final Legacy old = new Legacy(f);
    @Override protected GunBalance reads() { return f; }
    @Override protected Object gun() { return f.gun; }
    @Override protected Object ammo() { return f.a; }
    private ServerSettings.Snapshot remote;
    private final Map<String, Object> saved = new HashMap<>();

    private static Field field(String name) {
        try { Field f = BalanceGunsBase.class.getDeclaredField(name); f.setAccessible(true); return f; }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static Object value(String name) {
        try { return field(name).get(null); } catch (IllegalAccessException e) { throw new AssertionError(e); }
    }
    private static void set(String name, Object value) {
        try { field(name).set(null, value); } catch (IllegalAccessException e) { throw new AssertionError(e); }
    }
    @BeforeEach void prepare() {
        remote = ServerSettings.remote();
        for (String name : List.of("table", "tableStamp", "lastPoll", "tableRemote", "tableGeneration", "shotsOf")) saved.put(name, value(name));
        caches().forEach(Map::clear);
        ServerSettings.useRemote(new ServerSettings.Snapshot("", Map.of(), Map.of(), "test\ta\t2\t0\t0\ntest\tb\t3\t0\t0\ntest\tspecial\t4\t0\t0"));
        old.t = BalanceGunsBase.loadTable();
        old.generation = BalanceGunsBase.tableGeneration;
    }
    @AfterEach void restore() {
        caches().forEach(Map::clear);
        saved.forEach(BalanceGunsWalkTest::set);
        ServerSettings.useRemote(remote);
    }
    private static List<Map<?, ?>> caches() { return List.of((Map<?, ?>) value("SHOTS"), (Map<?, ?>) value("FROM")); }

    private static Object outcome(Supplier<?> action) {
        try { return action.get(); }
        catch (RuntimeException e) { return e.getClass().getName() + ":" + e.getMessage(); }
    }
    private Object same(Supplier<?> before, Supplier<?> after) {
        f.reset(); Object expected = outcome(before); List<String> calls = List.copyOf(f.calls);
        f.reset(); Object actual = outcome(after);
        if (expected instanceof Token) assertSame(expected, actual);
        else assertEquals(expected, actual);
        assertEquals(calls, f.calls);
        sameMap(old.SHOTS, (Map<?, ?>) value("SHOTS"));
        sameMap(old.FROM, (Map<?, ?>) value("FROM"));
        assertEquals(old.shotsOf, value("shotsOf"));
        return actual;
    }
    private static void sameMap(Map<?, ?> expected, Map<?, ?> actual) {
        assertEquals(expected.size(), actual.size());
        if (expected instanceof IdentityHashMap) assertInstanceOf(IdentityHashMap.class, actual);
        expected.forEach((key, v) -> {
            Object actualKey = actual.keySet().stream().filter(k -> k == key).findFirst().orElse(null);
            // FROM's string keys need equality; all identity-map keys must be the original objects.
            if (expected instanceof IdentityHashMap) assertSame(key, actualKey);
            Object w = actual.get(key);
            if (v instanceof Map<?, ?> m) sameMap(m, (Map<?, ?>) w);
            else assertEquals(v, w);
        });
    }

    @Test void cacheHitsMissingAmmoIdentityKeysAndGeneration() {
        same(() -> old.pair(f.gun, f.a), () -> BalanceGunsBase.pair(f.gun, f.a, f));
        assertEquals(2F, BalanceGunsBase.pair(f.gun, f.a, f).mult());
        same(() -> old.pair(f.gun, f.b), () -> BalanceGunsBase.pair(f.gun, f.b, f));
        assertEquals(List.of("isGun[gun]"), f.calls);
        same(() -> old.pair(f.gun, f.missing), () -> BalanceGunsBase.pair(f.gun, f.missing, f));
        same(() -> old.pair(f.twin, f.a), () -> BalanceGunsBase.pair(f.twin, f.a, f));
        assertEquals(2, ((Map<?, ?>) value("SHOTS")).size());
        same(() -> old.from(f.gun), () -> BalanceGunsBase.from(f.gun, f));
        same(() -> old.from(f.plain), () -> BalanceGunsBase.from(f.plain, f));
        same(() -> old.pair(f.foreign, f.a), () -> BalanceGunsBase.pair(f.foreign, f.a, f));
        same(() -> old.pair(f.plain, f.a), () -> BalanceGunsBase.pair(f.plain, f.a, f));
        same(() -> old.pair(f.gun, null), () -> BalanceGunsBase.pair(f.gun, null, f));
        ServerSettings.useRemote(new ServerSettings.Snapshot("", Map.of(), Map.of(), "test\ta\t5\t0\t0"));
        old.t = BalanceGunsBase.loadTable(); old.generation = BalanceGunsBase.tableGeneration;
        same(() -> old.pair(f.gun, f.a), () -> BalanceGunsBase.pair(f.gun, f.a, f));
        assertEquals(1, ((Map<?, ?>) value("SHOTS")).size());
        same(() -> old.as(f.gun, f.a, f.b), () -> BalanceGunsBase.as(f.gun, f.a, f.b, f));
        same(() -> old.as(null, f.a, f.b), () -> BalanceGunsBase.as(null, f.a, f.b, f));
    }

    @Test void namesAndExactCatchBoundary() {
        for (Object item : Arrays.asList(f.gun, f.a, f.foreign, null))
            same(() -> old.gunName(item), () -> BalanceGunsBase.gunName(item, f));
        for (Object ammo : List.of(f.a, f.b, f.missing))
            same(() -> old.ammoName(ammo), () -> BalanceGunsBase.ammoName(ammo, f));
        f.noItem = true;
        same(() -> old.ammoName(f.a), () -> BalanceGunsBase.ammoName(f.a, f)); f.noItem = false;
        f.nullStack = true;
        same(() -> old.ammoName(f.a), () -> BalanceGunsBase.ammoName(f.a, f)); f.nullStack = false;
        f.empty = true;
        same(() -> old.ammoName(f.a), () -> BalanceGunsBase.ammoName(f.a, f)); f.empty = false;
        for (String fail : List.of("ammoItem", "ammoStack", "ammoId", "empty", "registry", "item", "itemId", "path")) {
            f.fail = fail;
            if (fail.equals("ammoId")) f.noItem = true;
            same(() -> old.ammoName(f.a), () -> BalanceGunsBase.ammoName(f.a, f));
            f.noItem = false;
        }
        f.fail = null;
    }

    @Test void failedWalksLeaveTheSamePartialCachesAndRepeatLiveNames() {
        for (String operation : List.of("isGun", "itemId", "namespace", "path", "freshStack", "configCount", "config",
                "receivers", "magazine", "accepted", "baseDamage", "damageMult", "min", "max", "split", "ammoItem", "empty", "item")) {
            for (int occurrence : new int[] {1, 2, 3}) {
                old.SHOTS.clear(); old.FROM.clear(); caches().forEach(Map::clear);
                f.fail = operation; f.failAt = occurrence;
                same(() -> old.pair(f.gun, f.a), () -> BalanceGunsBase.pair(f.gun, f.a, f));
            }
        }
        f.fail = null;
    }

    @Test void handsShortCircuitAndFirstAmmo() {
        for (Object main : Arrays.asList(f.gun, f.plain, null)) for (Object off : Arrays.asList(f.twin, f.plain, null)) {
            f.main = main; f.off = off;
            same(() -> old.gunOf(f.shooter), () -> BalanceGunsBase.gunOf(f.shooter, f));
        }
        same(() -> old.firstAmmo(f.gun), () -> BalanceGunsBase.firstAmmo(f.gun, f));
        f.noAmmo = true;
        same(() -> old.firstAmmo(f.gun), () -> BalanceGunsBase.firstAmmo(f.gun, f));
        same(() -> old.from(f.gun), () -> BalanceGunsBase.from(f.gun, f));
    }

    @Test void clickBitsOrderOverflowAndFirstOccurrence() {
        Random random = new Random(0xA3A);
        for (int n = 0; n < 600; n++) {
            f.base = Float.intBitsToFloat(random.nextInt()); f.mult = Float.intBitsToFloat(random.nextInt());
            f.split = Float.intBitsToFloat(random.nextInt()); f.min = random.nextInt(); f.max = random.nextInt();
            Map<Object, Float> before = new IdentityHashMap<>(), after = new IdentityHashMap<>();
            f.reset(); List<Object> expected = old.ammoOf(f.gun, f.gun, before); List<String> calls = List.copyOf(f.calls);
            f.reset(); List<Object> actual = BalanceGunsBase.ammoOf(f.gun, f.gun, after, f);
            assertEquals(calls, f.calls); assertEquals(expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++) assertSame(expected.get(i), actual.get(i));
            assertEquals(before.size(), after.size());
            before.forEach((key, v) -> assertEquals(Float.floatToRawIntBits(v), Float.floatToRawIntBits(after.get(key))));
        }
    }

    @Test void synchronizedEntriesUseTheBaseMonitor() {
        for (var method : BalanceGunsBase.class.getDeclaredMethods()) {
            if (Set.of("table", "pair", "from", "loadTable", "reloadNow").contains(method.getName())) {
                assertTrue(Modifier.isStatic(method.getModifiers()));
                assertTrue(Modifier.isSynchronized(method.getModifiers()), method.toString());
                // A static synchronized method locks its declaring Class, by the JVM contract.
                assertSame(BalanceGunsBase.class, method.getDeclaringClass());
            }
        }
    }

    /** The original walk, with only typed game reads replaced by fake-port calls. */
    static final class Legacy {
        final Fake r;
        int shotsOf, generation;
        BalanceGunsRule.Table t;
        final Map<Object, Map<Object, BalanceGunsRule.Shot>> SHOTS = new IdentityHashMap<>();
        final Map<Object, Map<String, BalanceGunsRule.Resolved>> FROM = new IdentityHashMap<>();
        Legacy(Fake r) { this.r = r; }
        BalanceGunsRule.Table table() {
            if (shotsOf != generation) { shotsOf = generation; SHOTS.clear(); FROM.clear(); }
            return t;
        }
        String gunName(Object item) {
            Object id = r.itemId(item);
            if (id == null || !r.namespace(id).equals("hbm")) return null;
            String p = r.path(id);
            return p.startsWith("gun_") ? p.substring(4) : p;
        }
        String ammoName(Object b) {
            if (r.ammoItem(b) == null) return "config_" + r.ammoId(b);
            Object s;
            try { s = r.ammoStack(b); } catch (RuntimeException e) { return "config_" + r.ammoId(b); }
            if (s == null || r.empty(s)) return "config_" + r.ammoId(b);
            Object id = r.stackId(s);
            String p = r.path(id);
            if (p.startsWith("ammo_standard_")) return p.substring("ammo_standard_".length());
            return p.startsWith("ammo_") ? p.substring(5) : p;
        }
        List<Object> ammoOf(Object gun, Object stack, Map<Object, Float> click) {
            List<Object> out = new ArrayList<>();
            for (int c = 0; c < r.configCount(gun); c++) {
                for (Object rec : r.receivers(r.config(gun, stack, c), stack)) {
                    for (Object b : r.accepted(r.magazine(rec, stack))) {
                        if (click.containsKey(b)) continue;
                        out.add(b);
                        click.put(b, r.baseDamage(rec, stack) * r.damageMult(b) * (r.min(b) + r.max(b)) / 2F * r.split(rec, stack));
                    }
                }
            }
            return out;
        }
        BalanceGunsRule.Shot pair(Object gunItem, Object ammo) {
            BalanceGunsRule.Table t = table();
            if (!r.isGun(gunItem) || ammo == null) return BalanceGunsRule.Shot.NONE;
            Map<Object, BalanceGunsRule.Shot> m = SHOTS.get(gunItem);
            if (m == null || !m.containsKey(ammo)) {
                String name = gunName(gunItem);
                m = new IdentityHashMap<>();
                if (name != null) {
                    Map<Object, Float> click = new IdentityHashMap<>();
                    List<Object> all = ammoOf(gunItem, r.freshStack(gunItem), click);
                    if (!click.containsKey(ammo)) {
                        all.add(ammo);
                        click.put(ammo, r.damageMult(ammo) * (r.min(ammo) + r.max(ammo)) / 2F);
                    }
                    List<BalanceGunsRule.Ammo> named = new ArrayList<>();
                    for (Object b : all) named.add(new BalanceGunsRule.Ammo(ammoName(b), click.get(b), (r.min(b) + r.max(b)) / 2F));
                    Map<String, BalanceGunsRule.Resolved> resolved = BalanceGunsRule.resolve(t, name, named);
                    for (Object b : all) m.put(b, resolved.get(ammoName(b)).shot());
                    FROM.put(gunItem, resolved);
                }
                m.putIfAbsent(ammo, BalanceGunsRule.Shot.NONE);
                SHOTS.put(gunItem, m);
            }
            return m.get(ammo);
        }
        float as(Object gunItem, Object from, Object as) {
            if (!BalanceGunsBase.on() || gunItem == null || from == null || as == null) return 1F;
            return BalanceGunsBase.share(r.damageMult(from), pair(gunItem, from), r.damageMult(as), pair(gunItem, as));
        }
        Map<String, BalanceGunsRule.Resolved> from(Object gunItem) {
            Object first = firstAmmo(gunItem);
            if (first != null) pair(gunItem, first);
            return FROM.getOrDefault(gunItem, Map.of());
        }
        Object firstAmmo(Object item) {
            if (!r.isGun(item)) return null;
            List<Object> all = ammoOf(item, r.freshStack(item), new IdentityHashMap<>());
            return all.isEmpty() ? null : all.get(0);
        }
        Object gunOf(Object shooter) {
            Object main = r.item(r.mainHand(shooter));
            if (r.isGun(main)) return main;
            Object off = r.item(r.offHand(shooter));
            return r.isGun(off) ? off : null;
        }
    }
}
