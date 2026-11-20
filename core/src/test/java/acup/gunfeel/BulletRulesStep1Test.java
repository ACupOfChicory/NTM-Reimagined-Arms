package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import acup.gunfeel.BulletRuleText.Effect;
import acup.gunfeel.BulletRuleText.Rule;

// 1dd1056's matching on fakes against the actual core entries, including every port read in order.
class BulletRulesStep1Test {
    record Block(boolean entity, boolean model, boolean full, int fail, Set<String> tags) {
        BlockFacts facts() {
            return new BlockFacts("hbm:wall", List.of(), "stone", 1F, 1F, false, full, 1, entity, model, tags);
        }
    }

    private static final List<Block> BLOCKS = List.of(
            new Block(true, true, true, 0, Set.of("c:machine")),
            new Block(false, false, true, 0, Set.of()),
            new Block(false, true, true, 0, Set.of("c:stone")),
            new Block(false, true, false, 0, Set.of()),
            new Block(false, true, true, 3, Set.of()),
            new Block(false, true, true, 1, Set.of()),
            new Block(false, true, true, 2, Set.of()),
            new Block(false, true, true, 4, Set.of()));

    private static final class Fake {
        final List<String> calls = new ArrayList<>();
        final Map<Integer, Boolean> machines = new HashMap<>();
        final Map<Integer, Effect> hits = new HashMap<>();
        Engine engine;

        boolean blockEntity(int n) {
            calls.add("blockEntity:" + n);
            if (BLOCKS.get(n).fail == 1) throw new IllegalStateException("block entity");
            return BLOCKS.get(n).entity;
        }

        boolean model(int n) {
            calls.add("model:" + n);
            if (BLOCKS.get(n).fail == 2) throw new IllegalStateException("render shape");
            return BLOCKS.get(n).model;
        }

        boolean fullCollision(int n) {
            calls.add("fullCollision:" + n);
            if (BLOCKS.get(n).fail == 3) throw new IllegalStateException("collision shape");
            if (BLOCKS.get(n).fail == 4) throw new LinkageError("collision shape");
            return BLOCKS.get(n).full;
        }

        BlockFacts facts(int n) {
            calls.add("facts:" + n);
            return BLOCKS.get(n).facts();
        }

        boolean machine(int n) {
            calls.add("machine:" + n);
            return machines.computeIfAbsent(n, k -> engine == null ? oldMachine(this, k)
                    : (boolean) engine.call("machineOrPartial", new Class<?>[] {int.class}, k));
        }

        int typeNumber(int entity) {
            calls.add("typeNumber:" + entity);
            return switch (entity) { case 101, 103 -> 7; case 102 -> 8; default -> -1; };
        }

        String name(int type) {
            calls.add("name:" + type);
            return switch (type) {
                case 7 -> "hbm:mob";
                case 8 -> "minecraft:arrow";
                default -> throw new IllegalArgumentException("no type " + type);
            };
        }

        boolean tagged(int type, String tag) {
            calls.add("tagged:" + type + ":" + tag);
            return type == 7 && tag.equals("c:living");
        }
    }

    // The old methods, only the game's reads replaced by the fake ports.
    private static boolean oldMachine(Fake f, int n) {
        if (f.blockEntity(n) || !f.model(n)) return true;
        try {
            return !f.fullCollision(n);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static boolean oldMatches(Fake f, List<String> selectors, int n) {
        return BlockSelectors.matches(selectors, f.facts(n),
                () -> Materials.classify(f.facts(n), List.of()).material(), () -> f.machine(n));
    }

    private static boolean oldEntityMatches(Fake f, List<String> selectors, int type) {
        String key = f.name(type);
        for (String s : selectors) {
            if (s.equals("*")) return true;
            if (s.startsWith("#")) {
                if (f.tagged(type, s.substring(1))) return true;
            } else if (BlockSelectors.idMatches(s, key)) return true;
        }
        return false;
    }

    private static Effect oldEntity(Fake f, List<Rule> rules, String kind, int entity) {
        int type = f.typeNumber(entity);
        Rule r = BulletRuleText.last(rules, kind, x -> oldEntityMatches(f, x.selectors(), type));
        return r == null ? BulletRuleText.NO_EFFECT : BulletRuleText.effect(r);
    }

    private static Effect oldHit(Fake f, List<Rule> rules, int n) {
        return f.hits.computeIfAbsent(n, k -> {
            Rule r = BulletRuleText.last(rules, "hit", x -> oldMatches(f, x.selectors(), k));
            return r == null ? BulletRuleText.NO_EFFECT : BulletRuleText.effect(r);
        });
    }

    // Each engine gets its own CoreServices and rule caches, with no static port setter or service file.
    private static final class Engine extends URLClassLoader {
        final Class<?> base, text, body;

        Engine(Fake f, List<String> lines) throws Exception {
            super(new URL[] {BulletRulesBase.class.getProtectionDomain().getCodeSource().getLocation()},
                    BulletRulesStep1Test.class.getClassLoader());
            Class<?> ports = loadClass("acup.gunfeel.host.Ports");
            Object root = proxy(ports, (p, m, a) -> {
                if (m.getName().equals("ntm")) return Set.of();
                Class<?> port = (Class<?>) a[0];
                return switch (port.getSimpleName()) {
                    case "BlockTable" -> proxy(port, (bp, bm, ba) -> {
                        int n = (int) ba[0];
                        return switch (bm.getName()) {
                            case "facts" -> copyFacts(f.facts(n));
                            case "machineOrPartial" -> f.machine(n);
                            case "blockEntity" -> f.blockEntity(n);
                            case "model" -> f.model(n);
                            case "fullCollision" -> f.fullCollision(n);
                            default -> throw new AssertionError(bm);
                        };
                    });
                    case "EntityTypeTable" -> proxy(port, (tp, tm, ta) -> switch (tm.getName()) {
                        case "name" -> f.name((int) ta[0]);
                        case "tagged" -> f.tagged((int) ta[0], (String) ta[1]);
                        default -> throw new AssertionError(tm);
                    });
                    default -> null;
                };
            });
            loadClass("acup.gunfeel.CoreServices").getMethod("install", ports).invoke(null, root);
            base = loadClass("acup.gunfeel.BulletRulesBase");
            body = loadClass("acup.gunfeel.host.Body");
            text = loadClass("acup.gunfeel.BulletRuleText");
            Method parse = text.getDeclaredMethod("parse", List.class);
            parse.setAccessible(true);
            field("rules", parse.invoke(null, lines));
            field("builtinRead", true);
            field("pollDue", false);
            f.engine = this;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("acup.gunfeel.")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) c = findClass(name);
                if (resolve) resolveClass(c);
                return c;
            }
        }

        private Object proxy(Class<?> type, java.lang.reflect.InvocationHandler h) {
            return Proxy.newProxyInstance(this, new Class<?>[] {type}, h);
        }

        private Object copyFacts(BlockFacts f) throws Exception {
            return loadClass("acup.gunfeel.BlockFacts").getConstructor(String.class, List.class, String.class,
                    float.class, float.class, boolean.class, boolean.class, double.class, boolean.class,
                    boolean.class, Set.class).newInstance(f.id(), f.classes(), f.sound(), f.hardness(), f.blast(),
                            f.toolRequired(), f.fullCollision(), f.collisionVolume(), f.blockEntity(), f.modelShape(), f.tags());
        }

        private void field(String name, Object value) throws Exception {
            Field field = text.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        }

        Object call(String name, Class<?>[] types, Object... args) {
            try {
                Method m = base.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(null, args);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException r) throw r;
                if (e.getCause() instanceof Error error) throw error;
                throw new AssertionError(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }

        Object entity(Fake f, String kind, int entity) {
            Object b = proxy(body, (p, m, a) -> {
                if (!m.getName().equals("typeNumber")) throw new AssertionError(m);
                return f.typeNumber(entity);
            });
            return call("entity", new Class<?>[] {String.class, body}, kind, b);
        }
    }

    private static Object get(Object value, String field) throws Exception {
        return value.getClass().getMethod(field).invoke(value);
    }

    private static void bits(float expected, Object actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits((Float) actual));
    }

    private static void same(Effect expected, Object actual) throws Exception {
        List<?> particles = (List<?>) get(actual, "particles");
        assertEquals(expected.particles().size(), particles.size());
        for (int i = 0; i < particles.size(); i++) {
            assertEquals(expected.particles().get(i).id(), get(particles.get(i), "id"));
            assertEquals(expected.particles().get(i).count(), get(particles.get(i), "count"));
        }
        Object sound = get(actual, "sound");
        if (expected.sound() == null) assertEquals(null, sound);
        else {
            assertEquals(expected.sound().id(), get(sound, "id"));
            bits(expected.sound().volume(), get(sound, "volume"));
            bits(expected.sound().pitch(), get(sound, "pitch"));
        }
        assertEquals(expected.toAll(), get(actual, "toAll"));
        bits(expected.range(), get(actual, "range"));
    }

    @Test
    void blockReadsAndCatchBoundary() throws Exception {
        Fake old = new Fake(), now = new Fake();
        try (Engine e = new Engine(now, List.of())) {
            for (int repeat = 0; repeat < 2; repeat++) for (int n = 0; n < BLOCKS.size(); n++) {
                int state = n;
                if (BLOCKS.get(n).fail == 1 || BLOCKS.get(n).fail == 2) {
                    assertThrows(IllegalStateException.class, () -> oldMachine(old, state));
                    assertThrows(IllegalStateException.class, () -> e.call("machineOrPartial", new Class<?>[] {int.class}, state));
                } else if (BLOCKS.get(n).fail == 4) {
                    assertThrows(LinkageError.class, () -> oldMachine(old, state));
                    assertThrows(LinkageError.class, () -> e.call("machineOrPartial", new Class<?>[] {int.class}, state));
                } else assertEquals(oldMachine(old, n), e.call("machineOrPartial", new Class<?>[] {int.class}, n));
                assertEquals(old.calls, now.calls, "state " + n);
            }
        }
    }

    @Test
    void blockSelectorsAndEffects() throws Exception {
        List<List<String>> selectors = List.of(List.of("*"), List.of("#c:machine"), List.of("#c:stone"),
                List.of("%hbm"), List.of("%other", "hbm:wall"), List.of("#bad tag", "~hbm:.*"),
                List.of("@stone"), List.of("minecraft:stone"), List.of());
        List<List<String>> cases = List.of(List.of(), List.of(
                "hit * particles=block*2 sound=block/-0.0/1.0000001 range=-0.0",
                "hit #c:stone particles=minecraft:smoke*3 range=NaN",
                "hit %hbm particles=minecraft:crit*4 sound=hbm:hit/Infinity/0.25 to=all range=40"),
                List.of("hit #c:machine particles=block*7", "hit hbm:wall sound=none range=1.4E-45"));
        for (List<String> lines : cases) {
            Fake old = new Fake(), now = new Fake();
            List<Rule> rules = BulletRuleText.parse(lines);
            try (Engine e = new Engine(now, lines)) {
                for (int repeat = 0; repeat < 2; repeat++) for (int n = 0; n < 5; n++) {
                    for (List<String> sel : selectors) {
                        assertEquals(oldMatches(old, sel, n), e.call("matches", new Class<?>[] {List.class, int.class}, sel, n));
                        assertEquals(old.calls, now.calls, sel.toString());
                    }
                    same(oldHit(old, rules, n), e.call("hit", new Class<?>[] {int.class}, n));
                    assertEquals(old.calls, now.calls, "hit " + n);
                }
            }
        }
    }

    @Test
    void entityKindsTypesTagsAndUnknownNumber() throws Exception {
        List<List<String>> cases = List.of(List.of(), List.of(
                "entity * particles=minecraft:smoke*1 sound=hbm:hit/0.3/1.1 range=-0.0",
                "kill * particles=minecraft:crit*2 sound=hbm:kill/-0.0/Infinity to=all range=NaN",
                "entity #c:living particles=block*3 range=1.4E-45",
                "kill #c:living particles=block*4 range=40"), List.of(
                "entity hbm:mob particles=block*5", "kill ~hbm:.* sound=hbm:kill",
                "entity #bad tag,#c:absent sound=none"));
        for (List<String> lines : cases) {
            Fake old = new Fake(), now = new Fake();
            List<Rule> rules = BulletRuleText.parse(lines);
            try (Engine e = new Engine(now, lines)) {
                for (int repeat = 0; repeat < 2; repeat++) for (String kind : List.of("entity", "kill"))
                    for (int entity : new int[] {101, 102, 103, 999}) {
                        if (entity == 999 && !lines.isEmpty()) {
                            assertThrows(IllegalArgumentException.class, () -> oldEntity(old, rules, kind, entity));
                            assertThrows(IllegalArgumentException.class, () -> e.entity(now, kind, entity));
                        } else same(oldEntity(old, rules, kind, entity), e.entity(now, kind, entity));
                        assertEquals(old.calls, now.calls, kind + " " + entity);
                    }
            }
        }
    }
}
