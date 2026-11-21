package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 4c5d5a7's shot and receiver decisions against core, including every port call. */
class BalanceGunsShotTest {
    static final class Fake extends BalanceGunsWalkTest.Fake {
        record ReceiverState(int delay, boolean auto, float damage) { }
        final Object m0 = new BalanceGunsWalkTest.Token("m0"), m1 = new BalanceGunsWalkTest.Token("m1");
        final Map<Object, ReceiverState> recs = new IdentityHashMap<>();
        final Map<Object, Integer> mags = new IdentityHashMap<>();
        boolean bound = true, checkLock, missingGun;
        Fake() {
            recs.put(r0, new ReceiverState(7, false, 0x1.abcdep4F));
            recs.put(r1, new ReceiverState(9, true, -0F));
            recs.put(r2, new ReceiverState(11, false, Float.intBitsToFloat(0x7fc01234)));
            mags.put(m0, 12); mags.put(m1, 23);
        }
        @Override void call(String op, Object... args) {
            if (checkLock) assertTrue(Thread.holdsLock(BalanceGunsBase.class), op);
            super.call(op, args);
        }
        @Override public void fired(Object projectile, Object shooter, Object gun, Object ammo) { call("fired", projectile, shooter, gun, ammo); }
        @Override public void warnPair(Object gun, Object ammo, RuntimeException error) { call("warnPair", gun, ammo, error.getClass().getName(), error.getMessage()); }
        @Override public Object gun(String name) { call("gun", name); return missingGun ? plain : gun; }
        @Override public boolean bound(Object item) { call("bound", item); return bound; }
        @Override public Object magazine(Object rec) { call("magazineField", rec); return rec == r2 ? m1 : m0; }
        @Override public boolean singleMagazine(Object mag) { call("singleMagazine", mag); return mag == m0; }
        @Override public int delay(Object rec) { call("delay", rec); return recs.get(rec).delay(); }
        @Override public boolean auto(Object rec) { call("auto", rec); return recs.get(rec).auto(); }
        @Override public float damage(Object rec) { call("damage", rec); return recs.get(rec).damage(); }
        @Override public int capacity(Object mag) { call("capacity", mag); return mags.get(mag); }
        @Override public void delay(Object rec, int v) { call("setDelay", rec, v); var s = recs.get(rec); recs.put(rec, new ReceiverState(v, s.auto(), s.damage())); }
        @Override public void auto(Object rec, boolean v) { call("setAuto", rec, v); var s = recs.get(rec); recs.put(rec, new ReceiverState(s.delay(), v, s.damage())); }
        @Override public void damage(Object rec, float v) { call("setDamage", rec, Float.floatToRawIntBits(v)); var s = recs.get(rec); recs.put(rec, new ReceiverState(s.delay(), s.auto(), v)); }
        @Override public void capacity(Object mag, int v) { call("setCapacity", mag, v); mags.put(mag, v); }
    }

    private final Fake f = new Fake();
    private final Legacy old = new Legacy(f);
    private final Map<String, Object> saved = new HashMap<>();
    private ServerSettings.Snapshot remote;
    private static Field field(String name) {
        try { Field f = BalanceGunsBase.class.getDeclaredField(name); f.setAccessible(true); return f; }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static Object value(String name) {
        try { return field(name).get(null); } catch (IllegalAccessException e) { throw new AssertionError(e); }
    }
    private static void set(String name, Object v) {
        try { field(name).set(null, v); } catch (IllegalAccessException e) { throw new AssertionError(e); }
    }
    @SuppressWarnings("unchecked") private static <K, V> Map<K, V> map(String name) { return (Map<K, V>) value(name); }
    private static void clearCaches() {
        for (String name : List.of("SHOTS", "FROM", "EXTRA", "UNDER", "BEFORE", "MAGS")) map(name).clear();
        ((Set<?>) value("FAILED")).clear();
    }
    @BeforeEach void prepare() {
        remote = ServerSettings.remote();
        for (String name : List.of("table", "tableStamp", "lastPoll", "tableRemote", "tableGeneration", "shotsOf", "applied", "appliedTable")) saved.put(name, value(name));
        clearCaches(); set("applied", false); set("appliedTable", null);
        settings(true, "test\ta\t1.234567\t23.4567\t0.345678\t2\tfloor=0.345678 from=3 under=7:2.34567:34.5678:0.456789");
    }
    @AfterEach void cleanup() {
        clearCaches(); saved.forEach(BalanceGunsShotTest::set); ServerSettings.useRemote(remote);
    }
    private void settings(boolean on, String table) {
        ServerSettings.useRemote(new ServerSettings.Snapshot("", Map.of(BalanceKeys.KEY, on ? "arms" : "ntm"), Map.of(), table));
        old.walk.t = BalanceGunsBase.table(); old.walk.generation = BalanceGunsBase.tableGeneration;
    }
    private static Object result(Supplier<?> action) {
        try { return action.get(); }
        catch (RuntimeException e) { return e.getClass().getName() + ":" + e.getMessage(); }
    }
    private static void bits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }
    private static void bits(float[] expected, float[] actual) {
        if (expected == null) { assertNull(actual); return; }
        assertNotNull(actual); assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) bits(expected[i], actual[i]);
    }
    private Object same(Supplier<?> before, Supplier<?> after) {
        var recs = new IdentityHashMap<>(f.recs); var mags = new IdentityHashMap<>(f.mags);
        f.reset(); Object expected = result(before); List<String> calls = List.copyOf(f.calls);
        var changed = new IdentityHashMap<>(f.recs); var changedMags = new IdentityHashMap<>(f.mags);
        f.recs.clear(); f.recs.putAll(recs); f.mags.clear(); f.mags.putAll(mags);
        f.reset(); Object actual = result(after);
        if (expected instanceof Float e) bits(e, (Float) actual);
        else if (expected instanceof float[] e) bits(e, (float[]) actual);
        else assertEquals(expected, actual);
        assertEquals(calls, f.calls);
        assertEquals(changedMags, f.mags);
        assertEquals(changed.size(), f.recs.size());
        changed.forEach((key, e) -> {
            var a = f.recs.get(key); assertEquals(e.delay(), a.delay()); assertEquals(e.auto(), a.auto()); bits(e.damage(), a.damage());
        });
        return actual;
    }
    private void shot(Object projectile, Object shooter, Object ammo, float damage) {
        same(() -> old.shot(projectile, shooter, ammo, damage), () -> BalanceGunsBase.shot(projectile, shooter, ammo, damage, f));
        List<String> calls = List.copyOf(f.calls);
        bits(old.extra(projectile), BalanceGunsBase.extra(projectile));
        for (int tier : new int[] {-1, 0, 2, 3, 4, 6, 7, 9}) {
            same(() -> old.underOf(projectile, tier), () -> BalanceGunsBase.underOf(projectile, tier));
            bits(old.floorOf(projectile, tier), BalanceGunsBase.floorOf(projectile, tier));
        }
        f.calls.clear(); f.calls.addAll(calls);
    }
    private void seed(BalanceGunsRule.Shot s) {
        BalanceGunsBase.table(); old.walk.table();
        Map<Object, BalanceGunsRule.Shot> m = new IdentityHashMap<>(); m.put(f.a, s);
        BalanceGunsShotTest.<Object, Map<Object, BalanceGunsRule.Shot>>map("SHOTS").put(f.gun, m);
        old.walk.SHOTS.put(f.gun, m);
    }

    @Test void shotBitsExtrasUnderAndSameArray() {
        Object projectile = new Object();
        shot(projectile, f.shooter, f.a, 0x1.abcdefp13F);
        float[] e = BalanceGunsBase.extra(projectile);
        assertNotNull(e); assertSame(e, BalanceGunsBase.extra(projectile));
        e[0] = 123F; assertEquals(123F, BalanceGunsBase.extra(projectile)[0]);
        old.extra(projectile)[0] = 123F;
        seed(BalanceGunsRule.Shot.NONE); shot(projectile, f.shooter, f.a, -0F);
        assertSame(e, BalanceGunsBase.extra(projectile));
        assertNull(BalanceGunsBase.extra(null)); assertNull(BalanceGunsBase.underOf(null, 2));
        assertNull(BalanceGunsBase.extra(new Object())); assertNull(BalanceGunsBase.underOf(new Object(), 2));
        for (var s : List.of(new BalanceGunsRule.Shot(1F, 1F, 0F), new BalanceGunsRule.Shot(1F, 0F, 0.5F),
                new BalanceGunsRule.Shot(1F, 0F, 0F, 0.3F, 3, -1), new BalanceGunsRule.Shot(1F, 0F, 0F, 1F, 3, 0),
                new BalanceGunsRule.Shot(1F, 0F, 0F, 1F, 3, -1, List.of(new BalanceGunsRule.Under(4, 2F, 0F, 0F))))) {
            seed(s); shot(new Object(), f.shooter, f.a, 7F);
        }
        Random random = new Random(0xA3B);
        for (int i = 0; i < 500; i++) {
            seed(new BalanceGunsRule.Shot(Float.intBitsToFloat(random.nextInt()), 0F, 0F));
            shot(new Object(), f.shooter, f.a, Float.intBitsToFloat(random.nextInt()));
        }
    }

    @Test void firedPrecedesPairEvenWhenNoneOrPairThrows() {
        seed(BalanceGunsRule.Shot.NONE);
        shot(new Object(), f.shooter, f.a, -0F);
        same(() -> old.shot(null, f.shooter, f.a, 7F), () -> BalanceGunsBase.shot(null, f.shooter, f.a, 7F, f));
        assertEquals(List.of("mainHand[shooter]", "item[shooter]", "isGun[gun]", "fired[null, shooter, gun, a]", "isGun[gun]"), f.calls);
        f.fail = "isGun"; f.failAt = 2;
        shot(new Object(), f.shooter, f.a, 17F);
        assertEquals(1, f.calls.stream().filter(c -> c.startsWith("warnPair")).count());
        shot(new Object(), f.shooter, f.a, 17F);
        assertEquals(0, f.calls.stream().filter(c -> c.startsWith("warnPair")).count());
        f.fail = null;
    }

    @Test void guardsTooltipAndFailureBoundary() {
        shot(new Object(), null, f.a, Float.intBitsToFloat(0x7fc01234));
        shot(new Object(), f.shooter, null, -0F);
        settings(false, "test\ta\t2\t0\t0");
        shot(new Object(), f.shooter, f.a, 2F);
        same(() -> old.tooltipMult(f.gun, f.a), () -> BalanceGunsBase.tooltipMult(f.gun, f.a, f));
        assertTrue(f.calls.isEmpty());
        settings(true, "test\ta\t2\t0\t0");
        f.main = f.off = f.plain;
        shot(new Object(), f.shooter, f.a, 2F);
        f.main = f.gun;
        same(() -> old.tooltipMult(f.gun, f.a), () -> BalanceGunsBase.tooltipMult(f.gun, f.a, f));
        same(() -> old.tooltipMult(f.gun, null), () -> BalanceGunsBase.tooltipMult(f.gun, null, f));
        for (String op : List.of("itemId", "freshStack", "configCount", "ammoItem")) {
            map("SHOTS").clear(); old.walk.SHOTS.clear(); ((Set<?>) value("FAILED")).clear(); old.FAILED.clear();
            f.fail = op;
            for (int n = 0; n < 2; n++) {
                same(() -> old.safePair(f.gun, f.a), () -> BalanceGunsBase.safePair(f.gun, f.a, f));
                assertEquals(n == 0 ? 1 : 0, f.calls.stream().filter(c -> c.startsWith("warnPair")).count());
            }
        }
        f.fail = "fired";
        shot(new Object(), f.shooter, f.a, 8F);
        assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("warnPair")));
        f.fail = null;
    }

    @Test void weakRawKeysAndWrapperMonitor() throws Exception {
        assertEquals(Collections.synchronizedMap(new WeakHashMap<>()).getClass(), value("EXTRA").getClass());
        assertEquals(Collections.synchronizedMap(new WeakHashMap<>()).getClass(), value("UNDER").getClass());
        WeakReference<Object> key = weakShot();
        for (int i = 0; i < 100 && key.get() != null; i++) { System.gc(); Thread.sleep(10); }
        assertNull(key.get(), "Both caches must release the raw projectile");
        assertTrue(map("EXTRA").isEmpty()); assertTrue(map("UNDER").isEmpty());
        Object raw = new BalanceGunsWalkTest.Token("raw");
        BalanceGunsBase.shot(raw, f.shooter, f.a, 1F, f);
        assertSame(raw, map("EXTRA").keySet().iterator().next());
        assertSame(raw, map("UNDER").keySet().iterator().next());
        assertSame(BalanceGunsBase.extra(raw), BalanceGunsBase.extra(new BalanceGunsWalkTest.Token("equal")));
        // synchronizedMap's monitor is the wrapper itself, for both caches.
        for (String name : List.of("EXTRA", "UNDER")) {
            Map<Object, ?> cache = map(name);
            var entered = new java.util.concurrent.CountDownLatch(1);
            Thread reader = new Thread(() -> { entered.countDown(); cache.get(raw); });
            synchronized (cache) {
                reader.start(); assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                for (int i = 0; i < 100 && reader.getState() != Thread.State.BLOCKED; i++) Thread.sleep(5);
                assertEquals(Thread.State.BLOCKED, reader.getState());
            }
            reader.join(2000); assertFalse(reader.isAlive());
        }
    }
    private WeakReference<Object> weakShot() {
        Object raw = new Object(); BalanceGunsBase.shot(raw, f.shooter, f.a, 1F, f);
        WeakReference<Object> ref = new WeakReference<>(raw);
        f.calls.clear(); return ref;
    }

    private void sync() {
        f.checkLock = true;
        try {
            same(() -> { synchronized (BalanceGunsBase.class) { old.sync(); } return null; },
                    () -> { BalanceGunsBase.sync(f); return null; });
        } finally { f.checkLock = false; }
        assertEquals(old.applied, value("applied")); assertSame(old.appliedTable, value("appliedTable"));
        assertInstanceOf(IdentityHashMap.class, value("BEFORE")); assertInstanceOf(IdentityHashMap.class, value("MAGS"));
        assertEquals(old.BEFORE.size(), map("BEFORE").size()); assertEquals(old.MAGS, map("MAGS"));
        old.BEFORE.forEach((key, expected) -> {
            Object actual = map("BEFORE").get(key); assertNotNull(actual);
            try {
                assertEquals(expected.delay(), actual.getClass().getDeclaredMethod("delay").invoke(actual));
                assertEquals(expected.auto(), actual.getClass().getDeclaredMethod("auto").invoke(actual));
                bits(expected.dmg(), (Float) actual.getClass().getDeclaredMethod("dmg").invoke(actual));
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
    }
    private static final String ALL = "test\t*\t-\t0\t0\t2\tdelay=2 auto=true mag=41 dmg=123.4567";

    @Test void syncRepeatUnboundRestoreThenChangeAndIdentitySnapshots() {
        settings(true, ALL); sync();
        assertEquals(3, map("BEFORE").size()); assertEquals(1, map("MAGS").size());
        sync(); assertTrue(f.calls.isEmpty());
        settings(true, ALL.replace("delay=2", "delay=3")); f.bound = false;
        sync(); assertEquals(List.of("gun[test]", "isGun[gun]", "bound[gun]"), f.calls);
        assertEquals(2, f.recs.get(f.r0).delay());
        f.bound = true; sync();
        int fresh = f.calls.indexOf("freshStack[gun]");
        assertTrue(f.calls.subList(0, fresh).contains("setCapacity[m0, 12]"));
        assertEquals(3, f.recs.get(f.r0).delay());
        settings(false, ALL); sync();
        assertTrue(map("BEFORE").isEmpty()); assertTrue(map("MAGS").isEmpty());
        assertEquals(7, f.recs.get(f.r0).delay()); bits(-0F, f.recs.get(f.r1).damage());
        bits(Float.intBitsToFloat(0x7fc01234), f.recs.get(f.r2).damage());
        assertEquals(12, f.mags.get(f.m0)); sync(); assertTrue(f.calls.isEmpty());
    }

    @Test void syncNoChangesMissingGunAndPartialFailures() {
        for (String tail : List.of("", "delay=4", "auto=false", "mag=31", "dmg=0.1234567")) {
            settings(true, "test\t*\t-\t0\t0\t2\t" + tail); sync();
            if (tail.isEmpty()) assertFalse(f.calls.stream().anyMatch(c -> c.startsWith("freshStack")));
        }
        f.missingGun = true; settings(true, ALL); sync();
        assertTrue(map("BEFORE").isEmpty()); assertTrue(map("MAGS").isEmpty()); f.missingGun = false;
        for (String op : List.of("freshStack", "configCount", "config", "receivers", "magazineField", "delay", "auto", "damage", "capacity", "setDelay", "setAuto", "setDamage", "setCapacity")) {
            settings(false, ALL); sync();
            f.fail = op; settings(true, ALL); sync(); f.fail = null;
        }
    }

    @Test void syncMethodsLockTheirDeclaringBaseClass() {
        var methods = Arrays.stream(BalanceGunsBase.class.getDeclaredMethods()).filter(m -> m.getName().equals("sync")).toList();
        assertEquals(2, methods.size());
        for (var method : methods) {
            assertTrue(Modifier.isStatic(method.getModifiers())); assertTrue(Modifier.isSynchronized(method.getModifiers()));
            assertSame(BalanceGunsBase.class, method.getDeclaringClass());
        }
    }

    /** Original decisions; only typed game access is replaced by calls on the fake port. */
    static final class Legacy {
        final Fake r;
        final BalanceGunsWalkTest.Legacy walk;
        final Map<Object, float[]> EXTRA = Collections.synchronizedMap(new WeakHashMap<>());
        final Map<Object, BalanceGunsRule.Shot> UNDER = Collections.synchronizedMap(new WeakHashMap<>());
        final Set<Object> FAILED = Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
        record Before(int delay, boolean auto, float dmg) { }
        final Map<Object, Before> BEFORE = new IdentityHashMap<>();
        final Map<Object, Integer> MAGS = new IdentityHashMap<>();
        boolean applied;
        BalanceGunsRule.Table appliedTable;
        Legacy(Fake r) { this.r = r; walk = new BalanceGunsWalkTest.Legacy(r); }
        float shot(Object projectile, Object shooter, Object ammo, float damage) {
            if (shooter == null || ammo == null || !BalanceGunsBase.on()) return damage;
            Object gun = walk.gunOf(shooter);
            if (gun == null) return damage;
            r.fired(projectile, shooter, gun, ammo);
            BalanceGunsRule.Shot s = safePair(gun, ammo);
            if (s.none()) return damage;
            if (s.neg() != 0F || s.pen() != 0F || s.floor() != 1F || s.tier() >= 0)
                EXTRA.put(projectile, new float[] {s.neg(), s.pen(), s.floor(), s.from(), s.tier()});
            if (!s.under().isEmpty()) UNDER.put(projectile, s);
            return damage * s.mult();
        }
        float[] underOf(Object direct, int tier) {
            if (direct == null) return null;
            BalanceGunsRule.Shot s = UNDER.get(direct);
            if (s == null) return null;
            return BalanceGunsRule.underShift(s, tier);
        }
        float[] extra(Object direct) { return direct == null ? null : EXTRA.get(direct); }
        float floorOf(Object direct, int tier) {
            float[] e = extra(direct);
            if (e == null || e.length < 4) return 1F;
            float own = e[2] == 1F || tier < e[3] ? 1F : e[2];
            float k = e.length > 4 ? BalanceGunsRule.tierFloor(BalanceGunsBase.tierFloorRules(), (int) e[4], tier) : 1F;
            return k < 1F ? Math.max(BalanceGunsRule.LEAST_FLOOR, own * k) : own;
        }
        float tooltipMult(Object gun, Object ammo) {
            if (ammo == null || !BalanceGunsBase.on()) return 1F;
            return safePair(gun, ammo).mult();
        }
        BalanceGunsRule.Shot safePair(Object gun, Object ammo) {
            try { return walk.pair(gun, ammo); }
            catch (RuntimeException e) {
                if (FAILED.add(gun)) r.warnPair(gun, ammo, e);
                return BalanceGunsRule.Shot.NONE;
            }
        }
        void sync() {
            boolean want = BalanceGunsBase.on();
            BalanceGunsRule.Table t = BalanceGunsBase.table();
            if (want == applied && (!want || t == appliedTable)) return;
            Map<Object, BalanceGunsRule.GunLine> guns = new LinkedHashMap<>();
            if (want) {
                for (Map.Entry<String, BalanceGunsRule.GunLine> e : t.guns().entrySet()) {
                    Object item = r.gun(e.getKey());
                    if (!r.isGun(item)) continue;
                    if (!r.bound(item)) return;
                    guns.put(item, e.getValue());
                }
            }
            restore();
            guns.forEach(this::change);
            applied = want; appliedTable = t;
        }
        void change(Object gun, BalanceGunsRule.GunLine line) {
            if (line.delay() == null && line.auto() == null && line.mag() == null && line.dmg() == null) return;
            Object stack = r.freshStack(gun);
            for (int c = 0; c < r.configCount(gun); c++) {
                Object cfg = r.config(gun, stack, c);
                for (Object rec : r.receivers(cfg, stack)) {
                    Object mag = r.magazine(rec);
                    BEFORE.putIfAbsent(rec, new Before(r.delay(rec), r.auto(rec), r.damage(rec)));
                    if (line.mag() != null && r.singleMagazine(mag)) MAGS.putIfAbsent(mag, r.capacity(mag));
                    if (line.delay() != null) r.delay(rec, line.delay());
                    if (line.auto() != null) r.auto(rec, line.auto());
                    if (line.dmg() != null) r.damage(rec, line.dmg());
                    if (line.mag() != null && r.singleMagazine(mag)) r.capacity(mag, line.mag());
                }
            }
        }
        void restore() {
            for (Map.Entry<Object, Before> e : BEFORE.entrySet()) {
                Object a = e.getKey(); Before b = e.getValue();
                r.delay(a, b.delay()); r.auto(a, b.auto()); r.damage(a, b.dmg());
            }
            for (Map.Entry<Object, Integer> e : MAGS.entrySet()) r.capacity(e.getKey(), e.getValue());
            BEFORE.clear(); MAGS.clear();
        }
    }
}
