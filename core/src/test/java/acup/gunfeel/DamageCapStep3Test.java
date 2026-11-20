package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.NtmBullets;
import acup.gunfeel.host.NtmGuns;
import acup.gunfeel.host.WorldView;
import acup.gunfeel.host.Worlds;

/** The 5071e2f shot algorithm against core and the adapter's actual event reads. */
public class DamageCapStep3Test {
    public record Shot(long tick, boolean sniper) {}

    public static class Entity {
        final String name;
        List<String> calls;
        public int tickCount = 7;
        Entity(String name) { this.name = name; }
        @Override public int hashCode() {
            if (calls != null) calls.add("map.key " + name);
            return System.identityHashCode(this);
        }
        @Override public boolean equals(Object other) { return this == other; }
    }

    public static final class LivingEntity extends Entity {
        LivingEntity(String name) { super(name); }
    }

    public static final class ServerLevel {
        WorldView world;
        long getGameTime() { return world.gameTime(); }
    }

    public static final class EntityJoinLevelEvent {
        final Object level;
        final Entity first, second;
        final List<String> calls;
        int reads;
        EntityJoinLevelEvent(Object level, Entity first, Entity second, List<String> calls) {
            this.level = level;
            this.first = first;
            this.second = second;
            this.calls = calls;
        }
        public Object getLevel() { calls.add("getLevel"); return level; }
        public Entity getEntity() {
            calls.add("getEntity " + ++reads);
            return reads == 1 ? first : second;
        }
    }

    private static final class Reads implements NtmBullets, DamageCapBase.Settings {
        final List<String> calls = new ArrayList<>();
        Entity projectile;
        boolean hasShooter = true, pellet;
        int delay = 25, sniperDelay = 25;
        long tick = 100;
        final Body body = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class},
                (p, m, a) -> switch (m.getName()) {
                    case "uuid" -> new java.util.UUID(0, 1);
                    case "hashCode" -> { calls.add("map.body"); yield System.identityHashCode(p); }
                    case "equals" -> p == a[0];
                    default -> throw new AssertionError("unexpected Body read: " + m);
                });
        final NtmGuns guns = (NtmGuns) Proxy.newProxyInstance(NtmGuns.class.getClassLoader(), new Class<?>[] {NtmGuns.class},
                (p, m, a) -> {
                    assertEquals("maxDelay", m.getName());
                    assertSame(body, a[0]);
                    assertEquals(Body.MAIN_HAND, a[1]);
                    calls.add("gunDelay");
                    return delay;
                });
        final WorldView world = (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[] {WorldView.class},
                (p, m, a) -> {
                    assertEquals("gameTime", m.getName());
                    calls.add("gameTime");
                    return tick;
                });
        @Override public boolean capProjectile(Object e) { calls.add("capProjectile"); return e == projectile; }
        @Override public Body shooter(Object e) {
            assertSame(projectile, e);
            calls.add("shooter");
            return hasShooter ? body : null;
        }
        @Override public boolean pellet(Object e) {
            assertSame(projectile, e);
            calls.add("pellet");
            return pellet;
        }
        @Override public boolean flag(String key, boolean def) { throw new AssertionError(key); }
        @Override public float number(String key, float def) {
            calls.add("number " + key + " " + Float.floatToRawIntBits(def));
            return key.equals("damageCapSniperDelay") ? sniperDelay : def;
        }
        int gunDelay(LivingEntity shooter) { return guns.maxDelay(body, Body.MAIN_HAND); }
    }

    // The old methods, with only game and settings reads replaced by the fakes.
    private static final class Old {
        final Map<Entity, Shot> SHOTS = new WeakHashMap<>();
        void joined(EntityJoinLevelEvent e, Reads r) {
            if (!(e.getLevel() instanceof ServerLevel level) || e.getEntity() instanceof LivingEntity) return;
            Entity entity = e.getEntity();
            if (!r.capProjectile(entity)) return;
            Body shooter = r.shooter(entity);
            if (shooter == null) return;
            SHOTS.put(entity, new Shot(level.getGameTime(),
                    r.guns.maxDelay(shooter, Body.MAIN_HAND) >= limits(r).sniperDelay() && !r.pellet(entity)));
        }
        Shot damage(Entity projectile, LivingEntity shooter, long now, Reads r) {
            Shot shot = projectile == null ? null : SHOTS.get(projectile);
            if (shot == null) shot = new Shot(projectile == null ? now : now - projectile.tickCount,
                    r.gunDelay(shooter) >= limits(r).sniperDelay() && (projectile == null || !r.pellet(projectile)));
            return shot;
        }
        private static DamageCapRule.Limits limits(Reads r) {
            var d = DamageCapRule.Limits.DEFAULT;
            return new DamageCapRule.Limits(share(r, "damageCapClick", d.click()), share(r, "damageCapHead", d.head()),
                    share(r, "damageCapWindow", d.window()), ticks(r, "damageCapWindowTicks", d.windowTicks()),
                    share(r, "damageCapSniperHead", d.sniperHead()), ticks(r, "damageCapSniperDelay", d.sniperDelay()));
        }
        private static float share(Reads r, String key, float def) {
            float v = r.number(key, def);
            return Float.isFinite(v) && v > 0F ? v : def;
        }
        private static int ticks(Reads r, String key, int def) {
            float v = r.number(key, def);
            return Float.isFinite(v) && v >= 1F ? Math.round(v) : def;
        }
    }

    private static Method joinedAdapter, damageAdapter;

    /** Compile the real adapter bodies with fake types; no game classes or temporary files. */
    @BeforeAll static void compileAdapter() throws Exception {
        String source = Files.readString(Path.of("src/main/java/acup/gunfeel/DamageCap.java"));
        int start = source.indexOf("private static void joined(EntityJoinLevelEvent e) {");
        assertTrue(start >= 0);
        start = source.indexOf('{', start) + 1;
        String joined = source.substring(start, source.indexOf('\n' + "    }", start));
        start = source.indexOf("        Shot shot = projectile == null");
        assertTrue(start >= 0);
        String damage = source.substring(start, source.indexOf("        boolean head =", start));
        String code = "package acup.gunfeel; import acup.gunfeel.DamageCapStep3Test.*; "
                + "import java.util.function.*; public class ShotAdapter {"
                + "public static void joined(EntityJoinLevelEvent e, BiConsumer<Object,Object> sink) {"
                + joined.replace("joinedOf(", "sink.accept(") + "}"
                + "public static Shot damage(Entity projectile, LivingEntity shooter, long now, Function<Object,Shot> shots, "
                + "ToIntFunction<LivingEntity> gunDelay, Supplier<DamageCapRule.Limits> limits, Predicate<Object> pellet) {"
                + damage.replace("shotOf(", "shots.apply(").replace("gunDelay(", "gunDelay.applyAsInt(")
                        .replace("limits()", "limits.get()").replace("pellet(", "pellet.test(")
                + "return shot; }}";
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        Map<String, ByteArrayOutputStream> compiled = new HashMap<>();
        try (var manager = new ForwardingJavaFileManager<>(compiler.getStandardFileManager(null, null, null)) {
            @Override public JavaFileObject getJavaFileForOutput(Location location, String name, JavaFileObject.Kind kind,
                    javax.tools.FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("memory:///" + name.replace('.', '/') + kind.extension), kind) {
                    @Override public OutputStream openOutputStream() {
                        var bytes = new ByteArrayOutputStream();
                        compiled.put(name, bytes);
                        return bytes;
                    }
                };
            }
        }) {
            JavaFileObject input = new SimpleJavaFileObject(URI.create("string:///acup/gunfeel/ShotAdapter.java"), JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignore) { return code; }
            };
            String classpath = Path.of(DamageCapStep3Test.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    + java.io.File.pathSeparator
                    + Path.of(DamageCapRule.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            assertTrue(compiler.getTask(null, manager, null, List.of("-proc:none", "-classpath", classpath),
                    null, List.of(input)).call(), code);
        }
        Class<?> adapter = new ClassLoader(DamageCapStep3Test.class.getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                var bytes = compiled.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                byte[] data = bytes.toByteArray();
                return defineClass(name, data, 0, data.length);
            }
        }.loadClass("acup.gunfeel.ShotAdapter");
        joinedAdapter = adapter.getMethod("joined", EntityJoinLevelEvent.class, BiConsumer.class);
        damageAdapter = adapter.getMethod("damage", Entity.class, LivingEntity.class, long.class,
                Function.class, ToIntFunction.class, Supplier.class, Predicate.class);
    }

    private final Reads a = new Reads(), b = new Reads();
    private final Old old = new Old();
    private final Entity bullet = new Entity("bullet"), other = new Entity("other");
    private final LivingEntity shooter = new LivingEntity("shooter");
    private final ServerLevel level = new ServerLevel();
    private Map<Object, DamageCapBase.Shot> actual, saved;

    @SuppressWarnings("unchecked")
    @BeforeEach void start() throws Exception {
        var field = DamageCapBase.class.getDeclaredField("SHOTS");
        field.setAccessible(true);
        actual = (Map<Object, DamageCapBase.Shot>) field.get(null);
        assertInstanceOf(WeakHashMap.class, actual);
        saved = new WeakHashMap<>(actual);
        actual.clear();
        a.projectile = b.projectile = bullet;
        level.world = a.world;
        var worlds = Worlds.class.getDeclaredMethod("of", Worlds.Port.class, Object.class);
        worlds.setAccessible(true);
        int[] made = {0};
        Worlds.Port port = key -> { assertSame(level, key); made[0]++; return b.world; };
        assertSame(b.world, worlds.invoke(null, port, level));
        assertSame(b.world, worlds.invoke(null, port, level));
        assertSame(b.world, Worlds.of(level));
        assertEquals(1, made[0]);
    }

    @AfterEach void stop() {
        traces(null);
        if (actual != null) { actual.clear(); actual.putAll(saved); }
        Worlds.forget(level);
    }

    private void traces(List<String> calls) { bullet.calls = other.calls = shooter.calls = calls; }

    private void same() {
        traces(null);
        assertEquals(a.calls, b.calls, "ports, time and map operations in order");
        assertEquals(old.SHOTS.size(), actual.size());
        for (var expected : old.SHOTS.entrySet()) {
            var entry = actual.entrySet().stream().filter(e -> e.getKey() == expected.getKey()).findFirst().orElseThrow();
            assertEquals(expected.getValue().tick(), entry.getValue().tick());
            assertEquals(expected.getValue().sniper(), entry.getValue().sniper());
        }
        a.calls.clear();
        b.calls.clear();
    }

    private void joined(Object world, Entity first, Entity second) throws Exception {
        traces(a.calls);
        old.joined(new EntityJoinLevelEvent(world, first, second, a.calls), a);
        traces(b.calls);
        BiConsumer<Object, Object> sink = (l, e) -> DamageCapBase.joinedOf(l, e, b, b.guns, b);
        joinedAdapter.invoke(null, new EntityJoinLevelEvent(world, first, second, b.calls), sink);
        same();
    }

    private void joined() throws Exception { joined(level, bullet, bullet); }

    private void damage(Entity projectile) throws Exception {
        traces(a.calls);
        Shot expected = old.damage(projectile, shooter, a.tick, a);
        traces(b.calls);
        Function<Object, Shot> shots = e -> {
            var shot = DamageCapBase.shotOf(e);
            return shot == null ? null : new Shot(shot.tick(), shot.sniper());
        };
        ToIntFunction<LivingEntity> delay = b::gunDelay;
        Supplier<DamageCapRule.Limits> limits = () -> DamageCapBase.limitsOf(b);
        Predicate<Object> pellet = b::pellet;
        Shot result = (Shot) damageAdapter.invoke(null, projectile, shooter, b.tick, shots, delay, limits, pellet);
        assertEquals(expected, result);
        same();
    }

    @Test void rejectedEventsKeepTheirEarlyReturns() throws Exception {
        joined(new Object(), bullet, bullet);
        joined(level, shooter, bullet);
        joined(level, other, other);
        a.hasShooter = b.hasShooter = false;
        joined();
        assertTrue(actual.isEmpty());
    }

    @Test void secondEntityReadSuppliesTheRawKey() throws Exception {
        joined(level, other, bullet);
        assertEquals(1, actual.size());
        assertSame(bullet, actual.keySet().iterator().next());
        assertNotSame(b.body, actual.keySet().iterator().next());
    }

    @Test void delayBoundaryReadsPelletOnlyWhenNeeded() throws Exception {
        for (int delay : new int[] {0, 24, 25, 26}) for (boolean pellet : new boolean[] {false, true}) {
            a.delay = b.delay = delay;
            a.pellet = b.pellet = pellet;
            a.tick = b.tick += 1;
            joined();
            assertEquals(delay >= 25 && !pellet, actual.get(bullet).sniper());
        }
    }

    @Test void anotherJoinOverwritesBothShotFields() throws Exception {
        joined();
        a.tick = b.tick = 999;
        a.delay = b.delay = 24;
        joined();
        assertEquals(1, actual.size());
        assertEquals(999, actual.get(bullet).tick());
        assertFalse(actual.get(bullet).sniper());
    }

    @Test void damageMapHitMissAndNullPreserveReadsAndNeverStoreFallback() throws Exception {
        joined();
        a.tick = b.tick = 125;
        a.delay = b.delay = 0;
        damage(bullet);
        a.projectile = b.projectile = other;
        for (int delay : new int[] {24, 25}) for (boolean pellet : new boolean[] {false, true}) {
            a.delay = b.delay = delay;
            a.pellet = b.pellet = pellet;
            damage(other);
            damage(other);
            damage(null);
        }
        assertEquals(1, actual.size());
        assertSame(bullet, actual.keySet().iterator().next());
    }

    @Test void sweepAndBothForgetPathsLeaveShotsAlone() throws Exception {
        joined();
        DamageCapBase.sweepOf(10000);
        same();
        DamageCapBase.forgetOf();
        same();
        DamageCapBase.forgetOf(b.body, new acup.gunfeel.host.CapNow() {
            @Override public Object entity(Body target) { return bullet; }
            @Override public boolean client(Body target) { throw new AssertionError(); }
            @Override public long time(Body target) { throw new AssertionError(); }
            @Override public float shield(Body target) { throw new AssertionError(); }
            @Override public <T> T withShield(Body target, ShieldUse<T> use) { throw new AssertionError(); }
            @Override public float takeAmount(Body target, float fallback) { throw new AssertionError(); }
            @Override public boolean schemeHit(Body target, Object hurt) { throw new AssertionError(); }
        });
        same();
        assertEquals(1, actual.size());
    }
}
