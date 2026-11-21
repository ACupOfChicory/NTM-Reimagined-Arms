package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.CapNow;
import acup.gunfeel.host.HurtNow;
import acup.gunfeel.host.Hurts;
import acup.gunfeel.host.NtmBullets;
import acup.gunfeel.host.NtmGuns;
import acup.gunfeel.host.WorldView;
import acup.gunfeel.host.Worlds;

/** The complete 5eccde4 handler against the actual adapter and core, with recording game reads. */
public class DamageCapDamageTest {
    public static class Entity {
        final String name;
        Reads reads;
        Body body;
        public int tickCount = 7;
        Entity(String name) { this.name = name; }
        public Object level() { reads.call("level"); return reads.server ? reads.level : this; }
        @Override public int hashCode() { reads.call("key " + name); return System.identityHashCode(this); }
        @Override public boolean equals(Object other) { return this == other; }
        @Override public String toString() { return name; }
    }
    public static class LivingEntity extends Entity {
        LivingEntity(String name) { super(name); }
    }
    public static final class ServerLevel {
        Reads reads;
        public long getGameTime() { reads.call("gameTime"); return reads.tick; }
    }
    public static final class DamageSource {
        final Reads r;
        DamageSource(Reads r) { this.r = r; }
        public Entity getEntity() { return r.raw(r.hurts.cause(this)); }
        public Entity getDirectEntity() { return r.raw(r.hurts.direct(this)); }
    }
    public static final class LivingDamageEvent {
        public static final class Pre {
            final Reads r;
            float damage;
            Pre(Reads r) { this.r = r; damage = r.damage; }
            public LivingEntity getEntity() { r.call("getEntity"); return r.target; }
            public DamageSource getSource() { r.call("getSource"); return r.source; }
            public float getOriginalDamage() { r.call("getOriginalDamage"); return r.original; }
            public float getNewDamage() { r.call("getNewDamage"); return damage; }
            public void setNewDamage(float value) {
                r.call("setNewDamage " + bits(value) + " " + (r.old == null ? DamageCapBase.last : r.old.last)
                        + " " + (r.old == null ? DamageCapBase.cut : r.old.cut));
                damage = value;
            }
        }
    }
    public static final class EntityBody {
        public static Body of(Entity entity) { return entity == null ? null : entity.body; }
    }

    public static final class Reads implements CapNow, NtmBullets, DamageCapBase.Settings {
        final List<String> calls = new ArrayList<>();
        final Map<String, Boolean> flags = new HashMap<>();
        final LivingEntity target = new LivingEntity("target"), shooter = new LivingEntity("shooter"), other = new LivingEntity("other");
        final Entity bullet = new Entity("bullet"), grenade = new Entity("grenade");
        final ServerLevel level = new ServerLevel();
        final DamageSource source = new DamageSource(this);
        Entity direct = bullet, cause = shooter;
        boolean server = true, player = true, head, scheme, pellet, explosion, hasShooter = true, ntmFire;
        int delay = 25, exempt = -1, kind;
        long tick = 100;
        float original = 30F, damage = 30F, shield = 5F;
        Float amount;
        String fail;
        Old old;

        Reads() {
            level.reads = this;
            int id = 0;
            for (Entity e : List.of(target, shooter, other, bullet, grenade)) {
                e.reads = this;
                UUID uuid = new UUID(0, ++id);
                e.body = proxy(Body.class, (m, a) -> switch (m) {
                    case "player" -> { call("player " + e); yield e == target ? player : true; }
                    case "uuid" -> { call("uuid " + e); yield uuid; }
                    case "maxHealth" -> { call("maxHealth " + e); yield 20F; }
                    case "age" -> { call("age " + e); yield e.tickCount; }
                    default -> throw new AssertionError(m);
                });
            }
        }
        void call(String name) {
            calls.add(name);
            if (name.equals(fail)) throw new IllegalStateException(name);
        }
        Entity raw(Body body) {
            if (body == null) return null;
            for (Entity e : List.of(target, shooter, other, bullet, grenade)) if (e.body == body) return e;
            throw new AssertionError("foreign body");
        }
        final Hurts hurts = proxy(Hurts.class, (m, a) -> {
            assertSame(source, a[0]);
            call("hurts." + m + (m.equals("is") ? " " + a[1] : ""));
            return switch (m) {
                case "cause" -> EntityBody.of(cause);
                case "direct" -> EntityBody.of(direct);
                case "is" -> (int) a[1] == exempt || ((int) a[1] == Hurts.NTM_FIRE && ntmFire);
                case "directKind" -> kind;
                case "ntmExplosion" -> explosion;
                default -> throw new AssertionError(m);
            };
        });
        final HurtNow hurt = proxy(HurtNow.class, (m, a) -> {
            assertEquals("onHead", m); assertSame(target.body, a[0]); call("onHead"); return head;
        });
        final NtmGuns guns = proxy(NtmGuns.class, (m, a) -> {
            assertEquals("maxDelay", m); assertSame(shooter.body, a[0]); assertEquals(Body.MAIN_HAND, a[1]);
            call("gunDelay"); return delay;
        });
        final WorldView world = proxy(WorldView.class, (m, a) -> {
            assertEquals("gameTime", m); return level.getGameTime();
        });
        @Override public boolean flag(String key, boolean def) { call("flag " + key); return flags.getOrDefault(key, def); }
        @Override public float number(String key, float def) { call("number " + key); return def; }
        @Override public Object entity(Body b) { Entity e = raw(b); call("entity " + e); return e; }
        @Override public boolean client(Body b) { call("client"); return !server; }
        @Override public long time(Body b) { call("cap.time"); return tick; }
        @Override public float shield(Body b) { call("shield"); return shield; }
        @Override public <T> T withShield(Body b, ShieldUse<T> use) {
            call("props"); call("shield");
            var back = use.take(this, shield);
            if (back.give()) { call("giveShield " + bits(back.amount())); shield += back.amount(); }
            return back.value();
        }
        @Override public float takeAmount(Body b, float fallback) {
            assertSame(target.body, b); call("takeAmount " + bits(fallback));
            float out = amount == null ? fallback : amount; amount = null; return out;
        }
        @Override public boolean schemeHit(Body b, Object hurt) {
            assertSame(target.body, b); assertSame(source, hurt); call("schemeHit"); return scheme;
        }
        @Override public Body shooter(Object e) {
            call("shooter " + e);
            return !hasShooter || e == null || e == other ? null : e == target ? target.body : shooter.body;
        }
        @Override public boolean pellet(Object e) { call("pellet " + e); return pellet; }
        @Override public boolean capProjectile(Object e) { throw new AssertionError("join was not requested"); }
    }

    private interface Read { Object get(String method, Object[] args); }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Read read) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> switch (m.getName()) {
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            case "toString" -> type.getSimpleName();
            default -> read.get(m.getName(), a);
        });
    }

    // 5eccde4's complete damage and projectile methods; game reads use the recording fakes above.
    private static final class Old {
        record Shot(long tick, boolean sniper) {}
        record Before(long tick, float shield) {}
        final Reads r;
        final Map<Object, Shot> SHOTS = new WeakHashMap<>();
        final Map<Object, Before> SHIELD_BEFORE = new IdentityHashMap<>();
        final Map<UUID, Map<UUID, DamageCapRule.Ledger>> LEDGERS = new HashMap<>();
        String last = "";
        long cut;
        Old(Reads r) { this.r = r; r.old = this; }
        Before removeBeforeOf(Object target) { return SHIELD_BEFORE.remove(target); }
        boolean on() { return r.flag("damageCap", true); }
        boolean capped(LivingEntity target) { return target.body.player() || r.flag("damageCapMobs", false); }
        LivingEntity shooter(Entity entity) {
            Body b = r.shooter(entity == null ? null : r.entity(entity.body));
            return b == null ? null : (LivingEntity) r.raw(b);
        }
        boolean exempt(DamageSource source) {
            return r.hurts.is(source, Hurts.NTM_NUCLEAR_BLAST) || r.hurts.is(source, Hurts.NTM_RADIATION)
                    || r.hurts.is(source, Hurts.NTM_DIGAMMA)
                    || (r.hurts.is(source, Hurts.FIRE) && !irBeam(source)) || r.hurts.is(source, Hurts.FALL);
        }
        boolean irBeam(Object source) {
            return (r.hurts.directKind(source) & Hurts.NTM_BEAM) != 0 && r.hurts.is(source, Hurts.NTM_FIRE);
        }
        Entity projectile(DamageSource source, LivingEntity shooter) {
            Entity direct = source.getDirectEntity(), cause = source.getEntity();
            if (direct != null && direct != shooter) return direct;
            if (cause != null && cause != shooter) return cause;
            return null;
        }
        DamageCapRule.Limits limits() {
            var d = DamageCapRule.Limits.DEFAULT;
            return new DamageCapRule.Limits(r.number("damageCapClick", d.click()), r.number("damageCapHead", d.head()),
                    r.number("damageCapWindow", d.window()), Math.round(r.number("damageCapWindowTicks", d.windowTicks())),
                    r.number("damageCapSniperHead", d.sniperHead()), Math.round(r.number("damageCapSniperDelay", d.sniperDelay())));
        }
        boolean blastFree(float raw) {
            if (!r.flag("damageCapBlasts", true)) return true;
            return raw > BalanceArmorBase.explRawLimit() && !BalanceArmorBase.bulletBlastNow();
        }
        Shot shotOf(Entity projectile) { return SHOTS.get(r.entity(projectile.body)); }
        int gunDelay(LivingEntity shooter) { return r.guns.maxDelay(shooter.body, Body.MAIN_HAND); }
        boolean pellet(Entity entity) { return r.pellet(entity); }
        boolean headOf(Body target) { return r.hurt.onHead(target); }
        boolean sniperOf(Body target, Object source, boolean head, boolean sniper) {
            return head && sniper && r.schemeHit(target, source) ? false : sniper;
        }
        float takeAmountOf(Body target, float original) { return r.takeAmount(target, original); }
        record Hit(long click, boolean head, boolean sniper, float shield, float before, DamageCapRule.Cut result) {}
        Hit applyOf(Body target, Body shooter, Before before, long now, long click, boolean head, boolean sniper,
                java.util.function.Supplier<Float> damage) {
            if (target.player() && before != null && before.tick() == now) {
                return r.withShield(target, (props, current) -> {
                    Hit hit = apply(target, shooter, now, click, head, sniper, damage, Math.max(0F, before.shield() - current));
                    return new CapNow.ShieldBack<>(hit, hit.result().shield() > 0F, hit.result().shield());
                });
            }
            return apply(target, shooter, now, click, head, sniper, damage, 0F);
        }
        Hit apply(Body target, Body shooter, long now, long click, boolean head, boolean sniper,
                java.util.function.Supplier<Float> damage, float shield) {
            var ledger = LEDGERS.computeIfAbsent(target.uuid(), k -> new HashMap<>())
                    .computeIfAbsent(shooter.uuid(), k -> new DamageCapRule.Ledger());
            float before = damage.get();
            return new Hit(click, head, sniper, shield, before,
                    DamageCapRule.take(ledger, limits(), now, click, head, sniper, target.maxHealth(), shield, before));
        }
        void finishOf(Hit hit) {
            if (hit.result().damage() < hit.before()) cut++;
            last = "click=" + hit.click() + " head=" + hit.head() + " sniper=" + hit.sniper() + " shield=" + hit.shield()
                    + " damage=" + hit.before() + "->" + hit.result().damage() + " shieldBack=" + hit.result().shield();
        }
        void damage(LivingDamageEvent.Pre e) {
            LivingEntity target = e.getEntity();
            Before shieldBefore = removeBeforeOf(target);
            if (!(target.level() instanceof ServerLevel level) || !on() || !capped(target)) return;
            DamageSource source = e.getSource();
            LivingEntity shooter = shooter(source.getEntity());
            if (shooter == null) shooter = shooter(source.getDirectEntity());
            if (shooter == null || shooter == target || exempt(source)) return;
            Entity projectile = projectile(source, shooter);
            boolean blast = r.hurts.ntmExplosion(source)
                    || (BalanceArmorBase.vntBlastNow() && r.flag("damageCapAnyBlast", true));
            if (projectile == null && !blast) return;
            long now = level.getGameTime();
            float raw = takeAmountOf(EntityBody.of(target), e.getOriginalDamage());
            if (blast && blastFree(raw)) return;
            // Port adaptation of the raw key and tickCount field; no decision is changed.
            Shot shot = projectile == null ? null : shotOf(projectile);
            if (shot == null) {
                if (projectile != null) r.call("age " + projectile);
                shot = new Shot(projectile == null ? now : now - projectile.tickCount,
                        gunDelay(shooter) >= limits().sniperDelay() && (projectile == null || !pellet(projectile)));
            }
            boolean head = headOf(EntityBody.of(target));
            if (!sniperOf(EntityBody.of(target), source, head, shot.sniper()) && shot.sniper()) shot = new Shot(shot.tick(), false);
            var hit = applyOf(EntityBody.of(target), EntityBody.of(shooter), shieldBefore, now, shot.tick(), head, shot.sniper(), e::getNewDamage);
            if (hit.result().damage() < hit.before()) e.setNewDamage(hit.result().damage());
            finishOf(hit);
        }
    }

    private static Method adapter;
    private static Field adapterReads;

    /** Compile the actual event body and stateless access enum against fakes in the same runtime package. */
    @BeforeAll static void compileAdapter() throws Exception {
        String source = Files.readString(Path.of("src/main/java/acup/gunfeel/DamageCap.java"));
        int start = source.indexOf("    private static void damage(LivingDamageEvent.Pre e)");
        int end = source.indexOf("    /** the stand: what was taken", start);
        String body = source.substring(start, end).replace("private static void damage(", "public static void damage(");
        String code = "package acup.gunfeel; import acup.gunfeel.DamageCapDamageTest.*; import acup.gunfeel.host.*; "
                + "public class DamageAdapterB2 extends DamageCapBase { public static Reads reads; "
                + "static <E> Applied damageOf(Body t,Object l,Before b,E e,DamageEvent<E> d) {"
                + "return DamageCapBase.damageOf(t,l,b,e,d,reads.hurts,reads,reads.guns,reads,reads.hurt,reads); }"
                + "static Applied applyOf(Body t,Body s,Before b,long n,long c,boolean h,boolean sn,float d) {"
                + "return DamageCapBase.applyOf(t,s,b,n,c,h,sn,d,reads,reads); }" + body + "}";
        var compiler = ToolProvider.getSystemJavaCompiler();
        Map<String, ByteArrayOutputStream> classes = new LinkedHashMap<>();
        try (var manager = new ForwardingJavaFileManager<>(compiler.getStandardFileManager(null, null, null)) {
            @Override public JavaFileObject getJavaFileForOutput(Location l, String name, JavaFileObject.Kind kind,
                    javax.tools.FileObject sibling) {
                return new SimpleJavaFileObject(URI.create("memory:///" + name.replace('.', '/') + kind.extension), kind) {
                    @Override public OutputStream openOutputStream() {
                        var bytes = new ByteArrayOutputStream(); classes.put(name, bytes); return bytes;
                    }
                };
            }
        }) {
            var input = new SimpleJavaFileObject(URI.create("string:///acup/gunfeel/DamageAdapterB2.java"), JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignore) { return code; }
            };
            String cp = Path.of(DamageCapDamageTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    + java.io.File.pathSeparator + Path.of(DamageCapBase.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            assertTrue(compiler.getTask(null, manager, null, List.of("-proc:none", "-classpath", cp), null, List.of(input)).call(), code);
        }
        Class<?> outer = null;
        for (var entry : classes.entrySet()) {
            Class<?> c = MethodHandles.lookup().defineClass(entry.getValue().toByteArray());
            if (entry.getKey().equals("acup.gunfeel.DamageAdapterB2")) outer = c;
        }
        assertNotNull(outer);
        adapter = outer.getMethod("damage", LivingDamageEvent.Pre.class);
        adapterReads = outer.getField("reads");
    }

    private String savedLast;
    private long savedCut;
    private Map<Object, DamageCapBase.Shot> savedShots;
    @BeforeEach void start() throws Exception {
        savedLast = DamageCapBase.last; savedCut = DamageCapBase.cut;
        savedShots = new WeakHashMap<>(shots());
        clear();
    }
    @AfterEach void stop() throws Exception {
        clear(); shots().putAll(savedShots);
        DamageCapBase.last = savedLast; DamageCapBase.cut = savedCut;
        BalanceArmorBase.VNT_BLAST.remove(); BalanceArmorBase.BULLET_BLAST.remove();
    }
    private static void clear() throws Exception {
        DamageCapBase.forgetOf(); shots().clear(); DamageCapBase.last = ""; DamageCapBase.cut = 0;
    }
    private static Object field(Object object, String name) throws Exception {
        Class<?> type = object instanceof Class<?> c ? c : object.getClass();
        Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(object instanceof Class<?> ? null : object);
    }
    @SuppressWarnings("unchecked")
    private static Map<Object, DamageCapBase.Shot> shots() throws Exception { return (Map<Object, DamageCapBase.Shot>) field(DamageCapBase.class, "SHOTS"); }
    @SuppressWarnings("unchecked")
    private static Map<Object, DamageCapBase.Before> snapshots() throws Exception { return (Map<Object, DamageCapBase.Before>) field(DamageCapBase.class, "SHIELD_BEFORE"); }
    private static int bits(float value) { return Float.floatToRawIntBits(value); }
    private static Object state(Object value) throws Exception {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new HashMap<>();
            for (var e : map.entrySet()) out.put(e.getKey() instanceof Entity entity ? entity.name : e.getKey(), state(e.getValue()));
            return out;
        }
        if (value instanceof DamageCapRule.Ledger) {
            List<Object> taken = new ArrayList<>();
            for (Object t : (Iterable<?>) field(value, "taken")) taken.add(List.of(field(t, "tick"), field(t, "click"), bits((float) field(t, "amount"))));
            return List.of(field(value, "last"), taken);
        }
        if (value instanceof DamageCapBase.Shot s) return List.of(s.tick(), s.sniper());
        if (value instanceof Old.Shot s) return List.of(s.tick(), s.sniper());
        if (value instanceof DamageCapBase.Before b) return List.of(b.tick(), bits(b.shield()));
        if (value instanceof Old.Before b) return List.of(b.tick(), bits(b.shield()));
        throw new AssertionError(value);
    }
    private static List<String> canonical(List<String> calls) {
        // Echo's approved relocation: only this pure event getter moves ahead of applyOf.
        List<String> out = new ArrayList<>(calls);
        int getter = out.indexOf("getNewDamage");
        if (getter >= 0) {
            out.remove(getter);
            int afterHead = out.indexOf("onHead") + 1;
            if (afterHead < out.size() && out.get(afterHead).equals("schemeHit")) afterHead++;
            out.add(afterHead, "getNewDamage");
        }
        return out;
    }
    private static String exception(Runnable action) {
        try { action.run(); return ""; }
        catch (RuntimeException e) { return e.getClass().getName() + ":" + e.getMessage(); }
    }
    private void compare(Reads a, Reads b, Old old, boolean found, int snapshotAge) throws Exception {
        clear();
        assertInstanceOf(WeakHashMap.class, shots());
        old.SHIELD_BEFORE.put(a.target, new Old.Before(a.tick - snapshotAge, 40F));
        snapshots().put(b.target, new DamageCapBase.Before(b.tick - snapshotAge, 40F));
        if (found) {
            old.SHOTS.put(a.bullet, new Old.Shot(70, true)); shots().put(b.bullet, new DamageCapBase.Shot(70, true));
        }
        var worlds = Worlds.class.getDeclaredMethod("of", Worlds.Port.class, Object.class);
        worlds.setAccessible(true); worlds.invoke(null, (Worlds.Port) level -> { assertSame(b.level, level); return b.world; }, b.level);
        a.calls.clear(); b.calls.clear();
        try {
            for (int repeat = 0; repeat < 2; repeat++) {
                var ea = new LivingDamageEvent.Pre(a); var eb = new LivingDamageEvent.Pre(b);
                String expected = exception(() -> old.damage(ea));
                adapterReads.set(null, b);
                String actual = exception(() -> {
                    try { adapter.invoke(null, eb); }
                    catch (InvocationTargetException e) { throw (RuntimeException) e.getCause(); }
                    catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                });
                assertEquals(expected, actual, "exception");
                assertEquals(canonical(a.calls), canonical(b.calls), "ordered reads and writes");
                assertEquals(bits(ea.damage), bits(eb.damage), "damage bits");
                assertEquals(bits(a.shield), bits(b.shield), "shield bits");
                assertEquals(a.amount, b.amount, "consumed raw amount");
                assertEquals(old.last, DamageCapBase.last); assertEquals(old.cut, DamageCapBase.cut);
                assertEquals(state(old.SHOTS), state(shots()), "raw shot keys and fields");
                assertEquals(state(old.LEDGERS), state(field(DamageCapBase.class, "LEDGERS")), "all ledger fields");
                assertEquals(state(old.SHIELD_BEFORE), state(snapshots()), "identity snapshots");
                a.calls.clear(); b.calls.clear(); a.tick++; b.tick++;
            }
        } finally { Worlds.forget(b.level); }
    }
    private void scenario(java.util.function.Consumer<Reads> setup, boolean found, int snapshotAge) throws Exception {
        Reads a = new Reads(), b = new Reads(); setup.accept(a); setup.accept(b);
        compare(a, b, new Old(a), found, snapshotAge);
    }

    @Test void allEarlyExitsAndSourceIdentities() throws Exception {
        for (int exit = 0; exit < 12; exit++) {
            int n = exit;
            scenario(r -> {
                switch (n) {
                    case 0 -> r.server = false;
                    case 1 -> r.flags.put("damageCap", false);
                    case 2 -> r.player = false;
                    case 3 -> r.hasShooter = false;
                    case 4 -> r.cause = r.target;
                    case 5 -> r.exempt = Hurts.NTM_NUCLEAR_BLAST;
                    case 6 -> r.exempt = Hurts.NTM_RADIATION;
                    case 7 -> r.exempt = Hurts.NTM_DIGAMMA;
                    case 8 -> r.exempt = Hurts.FIRE;
                    case 9 -> r.exempt = Hurts.FALL;
                    case 10 -> { r.direct = r.shooter; r.cause = r.shooter; }
                    case 11 -> { r.explosion = true; r.flags.put("damageCapBlasts", false); }
                    default -> throw new AssertionError(n);
                }
            }, false, 0);
        }
        for (int direct = 0; direct < 4; direct++) for (int cause = 0; cause < 4; cause++) {
            int d = direct, c = cause;
            scenario(r -> {
                Entity[] entities = {null, r.shooter, r.bullet, r.grenade};
                r.direct = entities[d]; r.cause = entities[c]; r.player = false; r.flags.put("damageCapMobs", true);
            }, false, 1);
        }
        for (boolean beam : List.of(false, true)) for (boolean ntmFire : List.of(false, true))
            scenario(r -> { r.exempt = Hurts.FIRE; r.kind = beam ? Hurts.NTM_BEAM : 0; r.ntmFire = ntmFire; }, false, 0);
    }

    @Test void shotsPelletsHeadSchemesAndShieldTicks() throws Exception {
        for (boolean found : List.of(false, true)) for (int delay : new int[] {24, 25, 26})
            for (boolean pellet : List.of(false, true)) for (boolean head : List.of(false, true))
                for (boolean scheme : List.of(false, true)) for (int age : new int[] {0, 1})
                    scenario(r -> { r.delay = delay; r.pellet = pellet; r.head = head; r.scheme = scheme; }, found, age);
        scenario(r -> { r.shield = 39F; r.damage = 2F; }, false, 0);
        scenario(r -> { r.damage = -0F; r.original = Float.intBitsToFloat(0x7fc00001); }, false, 0);
    }

    @Test void blastsVntAnyClassAndRawBoundary() throws Exception {
        for (boolean vnt : List.of(false, true)) for (boolean any : List.of(false, true))
            for (boolean ntm : List.of(false, true)) for (boolean bullet : List.of(false, true))
                for (boolean noProjectile : List.of(false, true))
                    for (float raw : new float[] {Math.nextDown(60F), 60F, Math.nextUp(60F)}) {
                        BalanceArmorBase.VNT_BLAST.set(vnt); BalanceArmorBase.BULLET_BLAST.set(bullet);
                        scenario(r -> {
                            r.explosion = ntm; r.flags.put("damageCapAnyBlast", any); r.original = raw; r.amount = raw;
                            if (noProjectile) r.direct = r.shooter;
                        }, false, 0);
                    }
    }

    @Test void exceptionsKeepConsumedSnapshotsAndDoNotFinishBeforeSetter() throws Exception {
        for (String failure : List.of("level", "flag damageCap", "getSource", "hurts.cause", "shooter shooter",
                "hurts.is 3", "hurts.direct", "hurts.ntmExplosion", "gameTime", "getOriginalDamage",
                "takeAmount " + bits(30F), "entity bullet", "key bullet", "age bullet", "gunDelay",
                "number damageCapSniperDelay", "pellet bullet", "onHead", "schemeHit")) {
            scenario(r -> { r.fail = failure; r.head = true; }, false, 0);
        }
        scenario(r -> r.fail = "setNewDamage 0  0", false, 0);
    }

    @Test void independentRuleNumbers() {
        var limits = DamageCapRule.Limits.DEFAULT;
        var ledger = new DamageCapRule.Ledger();
        var body = DamageCapRule.take(ledger, limits, 100, 93, false, false, 20F, 0F, 30F);
        assertEquals(bits(12F), bits(body.damage())); assertEquals(bits(0F), bits(body.shield()));
        var late = DamageCapRule.take(ledger, limits, 101, 93, false, false, 20F, 0F, 30F);
        assertEquals(bits(0F), bits(late.damage()));
        var next = DamageCapRule.take(ledger, limits, 102, 94, false, false, 20F, 0F, 30F);
        assertEquals(bits(4F), bits(next.damage()));
        var shield = DamageCapRule.take(new DamageCapRule.Ledger(), limits, 100, 93, false, false, 20F, 30F, 30F);
        assertEquals(bits(0F), bits(shield.damage())); assertEquals(bits(18F), bits(shield.shield()));
        var sniper = DamageCapRule.take(new DamageCapRule.Ledger(), limits, 100, 93, true, true, 20F, 0F, 30F);
        assertEquals(bits(20F), bits(sniper.damage()));
    }
}
