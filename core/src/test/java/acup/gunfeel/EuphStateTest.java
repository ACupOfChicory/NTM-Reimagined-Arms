package acup.gunfeel;

import static acup.gunfeel.EuphImmunityBase.carry;
import static acup.gunfeel.EuphImmunityBase.phase;
import static acup.gunfeel.EuphImmunityBase.pool;
import static acup.gunfeel.EuphImmunityBase.poolAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import acup.gunfeel.EuphImmunityBase.Kit;
import acup.gunfeel.EuphImmunityBase.Phase;
import acup.gunfeel.EuphImmunityBase.State;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.WorldView;
import acup.gunfeel.host.Worlds;

/** The old state owner against core: answers, every state field and the version's reads in order. */
class EuphStateTest {
    private static final class Reads {
        final List<String> calls = new ArrayList<>();
        final Body[] bodies = new Body[4];
        final Object level = new Object();
        long time;
        final WorldView world = (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[] {WorldView.class},
                (p, m, a) -> {
                    if (!m.getName().equals("gameTime")) throw new UnsupportedOperationException(m.getName());
                    calls.add("time");
                    return time;
                });
        final Worlds.Port worlds = l -> {
            assertSame(level, l);
            return world;
        };
        final BooleanSupplier shared = () -> {
            calls.add("shared");
            return EuphImmunityBase.shared();
        };

        Reads() {
            UUID[] ids = {new UUID(0, 1), new UUID(0, 1), new UUID(0, 2), new UUID(0, 3)};
            // A respawn shares the very UUID object, but has its own entity identity.
            ids[1] = ids[0];
            for (int i = 0; i < bodies.length; i++) {
                UUID id = ids[i];
                bodies[i] = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> {
                    return switch (m.getName()) {
                        case "uuid" -> { calls.add("uuid"); yield id; }
                        case "level" -> { calls.add("level"); yield level; }
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == a[0];
                        case "toString" -> "body " + id;
                        default -> throw new UnsupportedOperationException(m.getName());
                    };
                });
            }
        }
    }

    // The old methods, with the entity's reads supplied by the same fake Body and world as core's.
    private static final class Old {
        private final Map<Kit, Map<UUID, State>> STATES = new EnumMap<>(Kit.class);
        private final Map<Body, Long> IN_RADON = new WeakHashMap<>();
        private final Map<UUID, Kit> LAST_KIT = new HashMap<>();
        private final Reads reads;

        Old(Reads reads) {
            this.reads = reads;
            for (Kit k : Kit.values()) STATES.put(k, new HashMap<>());
        }

        synchronized State state(Kit kit, Body player, long now) {
            UUID id = player.uuid();
            Map<UUID, State> own = STATES.get(kit);
            if (reads.shared.getAsBoolean()) {
                Kit was = LAST_KIT.put(id, kit);
                State from = was == null || was == kit ? null : STATES.get(was).get(id);
                if (from != null) own.put(id, carry(from, pool(was), pool(kit), now));
            } else {
                // off: no kit remembered, so turning it back on carries nothing stale
                LAST_KIT.remove(id);
            }
            return own.computeIfAbsent(id, u -> new State(pool(kit).size(), now));
        }

        synchronized void inRadon(Body entity, long now) {
            IN_RADON.put(entity, now);
        }

        synchronized boolean radon(Body entity, long now) {
            Long t = IN_RADON.get(entity);
            return t != null && now - t <= 1;
        }

        boolean vulnerable(Body player) {
            long now = reads.worlds.world(player.level()).gameTime();
            if (radon(player, now)) return true;
            State s;
            synchronized (this) {
                s = STATES.get(Kit.EUPHEMIUM).get(player.uuid());
            }
            return s != null && phase(s, now) == Phase.WINDOW;
        }

        synchronized String describe(Kit kit, Body player) {
            State s = STATES.get(kit).get(player.uuid());
            if (s == null) return "нет";
            long now = reads.worlds.world(player.level()).gameTime();
            return phase(s, now) + " " + Math.round(poolAt(s, now, pool(kit)));
        }

        synchronized float poolOf(Kit kit, Body player) {
            State s = STATES.get(kit).get(player.uuid());
            if (s == null) return Float.NaN;
            long now = reads.worlds.world(player.level()).gameTime();
            return phase(s, now) == Phase.POOL ? poolAt(s, now, pool(kit)) : Float.NaN;
        }

        synchronized void forget(Body player) {
            for (Map<UUID, State> m : STATES.values()) m.remove(player.uuid());
            // DECIDED belongs to the version; its removal remains under the same lock there.
            LAST_KIT.remove(player.uuid());
        }

        synchronized void clear() {
            for (Map<UUID, State> m : STATES.values()) m.clear();
            IN_RADON.clear();
            LAST_KIT.clear();
        }
    }

    @Test
    void sequencesMatchInBitsStateAndReads() throws Throwable {
        runSequences();
    }

    @Test
    void sharedKeySwitchesInSequences() throws Throwable {
        // BalanceKeys freezes its dev switch at class initialization. A fresh core loader exercises both settings
        // without changing that switch for the other tests in this worker.
        String was = System.getProperty("ntmgunfeel.balanceKeys");
        URL[] path = {EuphStateTest.class.getProtectionDomain().getCodeSource().getLocation(),
                EuphImmunityBase.class.getProtectionDomain().getCodeSource().getLocation()};
        try (URLClassLoader loader = new URLClassLoader(path, EuphStateTest.class.getClassLoader()) {
            @Override
            protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("acup.gunfeel.")) return super.loadClass(name, resolve);
                Class<?> found = findLoadedClass(name);
                if (found == null) found = findClass(name);
                if (resolve) resolveClass(found);
                return found;
            }
        }) {
            System.setProperty("ntmgunfeel.balanceKeys", "true");
            var method = loader.loadClass(EuphStateTest.class.getName()).getDeclaredMethod("runSequences");
            method.setAccessible(true);
            try {
                method.invoke(null);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        } finally {
            if (was == null) System.clearProperty("ntmgunfeel.balanceKeys");
            else System.setProperty("ntmgunfeel.balanceKeys", was);
        }
    }

    private static void runSequences() throws Exception {
        int sharedOn = 0, sharedOff = 0;
        try {
            for (int seed = 0; seed < 20; seed++) {
                EuphImmunityBase.clearAll();
                Reads before = new Reads(), after = new Reads();
                Old old = new Old(before);
                Random random = new Random(0xE0F400L + seed);
                boolean remote = false;
                for (int step = 0; step < 2500; step++) {
                    if (step % 29 == 0) {
                        remote = !remote;
                        ServerSettings.useRemote(remote ? new ServerSettings.Snapshot("",
                                Map.of("balance.immune.shared", "false"), Map.of(), "") : null);
                    }
                    if (EuphImmunityBase.shared()) sharedOn++;
                    else sharedOff++;
                    long now = before.time += new int[] {0, 1, 2, 40, 400}[random.nextInt(5)];
                    after.time = now;
                    int op = random.nextInt(8), index = random.nextInt(4);
                    Kit kit = Kit.values()[random.nextInt(3)];
                    Body a = before.bodies[index], b = after.bodies[index];
                    String at = "seed " + seed + " step " + step + " op " + op + " kit " + kit + " body " + index;
                    before.calls.clear();
                    after.calls.clear();
                    switch (op) {
                        case 0 -> {
                            State want = old.state(kit, a, now), got = EuphImmunityBase.stateOf(kit, b, now, after.shared);
                            assertEquals(stateBits(want), stateBits(got), at);
                            float drain = random.nextInt(5000) / 3F;
                            boolean weapon = random.nextBoolean(), keeps = random.nextBoolean();
                            int reach = random.nextInt(500);
                            assertEquals(EuphImmunityBase.hit(want, now, drain, weapon, keeps, EuphImmunityBase.pool(kit), reach),
                                    EuphImmunityBase.hit(got, now, drain, weapon, keeps, EuphImmunityBase.pool(kit), reach), at);
                        }
                        case 1 -> { old.inRadon(a, now); EuphImmunityBase.inRadonOf(b, now); }
                        case 2 -> assertEquals(old.radon(a, now), EuphImmunityBase.radonOf(b, now), at);
                        case 3 -> assertEquals(old.vulnerable(a), EuphImmunityBase.vulnerableOf(b, after.worlds), at);
                        case 4 -> assertEquals(old.describe(kit, a), EuphImmunityBase.describeOf(kit, b, after.worlds), at);
                        case 5 -> assertEquals(Float.floatToRawIntBits(old.poolOf(kit, a)),
                                Float.floatToRawIntBits(EuphImmunityBase.poolOf(kit, b, after.worlds)), at);
                        case 6 -> { old.forget(a); EuphImmunityBase.forgetOf(b); }
                        case 7 -> { old.clear(); EuphImmunityBase.clearAll(); }
                        default -> throw new AssertionError(op);
                    }
                    assertEquals(before.calls, after.calls, at);
                    assertState(old, before, after, at);
                }
            }
            assertTrue(sharedOn > 0);
            if (BalanceKeys.dev()) assertTrue(sharedOff > 0, "the remote false key was read");
        } finally {
            EuphImmunityBase.clearAll();
            ServerSettings.useRemote(null);
        }
    }

    private static List<Number> stateBits(State s) {
        return List.of(Float.floatToRawIntBits(s.pool), s.at, s.lastHit, s.windowStart, s.windowEnd, s.recoverEnd, s.pause);
    }

    private static Object field(String name) throws Exception {
        Field f = EuphImmunityBase.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    @SuppressWarnings("unchecked")
    private static void assertState(Old old, Reads before, Reads after, String at) throws Exception {
        assertEquals(old.LAST_KIT, field("LAST_KIT"), at + " LAST_KIT");
        Map<Kit, Map<UUID, State>> states = (Map<Kit, Map<UUID, State>>) field("STATES");
        for (Kit kit : Kit.values()) {
            Map<UUID, State> want = old.STATES.get(kit), got = states.get(kit);
            assertEquals(want.keySet(), got.keySet(), at);
            for (UUID id : want.keySet()) assertEquals(stateBits(want.get(id)), stateBits(got.get(id)), at);
        }
        Map<Body, Long> radon = (Map<Body, Long>) field("IN_RADON");
        assertEquals(old.IN_RADON.size(), radon.size(), at);
        for (int i = 0; i < before.bodies.length; i++)
            assertEquals(old.IN_RADON.get(before.bodies[i]), radon.get(after.bodies[i]), at);
    }

    @Test
    void missingStateDoesNotReadTime() {
        Reads r = new Reads();
        try {
            EuphImmunityBase.clearAll();
            assertEquals("нет", EuphImmunityBase.describeOf(Kit.DNS, r.bodies[0], r.worlds));
            assertEquals(List.of("uuid"), r.calls);
            r.calls.clear();
            assertEquals(Float.floatToRawIntBits(Float.NaN),
                    Float.floatToRawIntBits(EuphImmunityBase.poolOf(Kit.DNS, r.bodies[0], r.worlds)));
            assertEquals(List.of("uuid"), r.calls);
        } finally {
            EuphImmunityBase.clearAll();
        }
    }

    @Test
    void forgetRemovesLastKitAndKeepsRadon() throws Exception {
        Reads r = new Reads();
        try {
            EuphImmunityBase.clearAll();
            EuphImmunityBase.stateOf(Kit.DNS, r.bodies[0], 7);
            EuphImmunityBase.inRadonOf(r.bodies[0], 7);
            EuphImmunityBase.forgetOf(r.bodies[0]);
            assertEquals(Map.of(), field("LAST_KIT"));
            assertTrue(EuphImmunityBase.radonOf(r.bodies[0], 7));
            assertEquals("нет", EuphImmunityBase.describeOf(Kit.DNS, r.bodies[0], r.worlds));
        } finally {
            EuphImmunityBase.clearAll();
        }
    }

    @Test
    void newEntityWithSameUuidIsNotInRadon() {
        Reads r = new Reads();
        Body a = r.bodies[0], b = r.bodies[1];
        assertSame(a.uuid(), b.uuid());
        try {
            EuphImmunityBase.clearAll();
            EuphImmunityBase.inRadonOf(a, 17);
            assertFalse(EuphImmunityBase.radonOf(b, 17));
            assertTrue(EuphImmunityBase.radonOf(a, 17));
            assertTrue(EuphImmunityBase.radonOf(a, 18));
            assertFalse(EuphImmunityBase.radonOf(a, 19));
        } finally {
            EuphImmunityBase.clearAll();
        }
    }
}
