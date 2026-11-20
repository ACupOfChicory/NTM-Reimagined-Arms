package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.CapNow;
import acup.gunfeel.host.HurtNow;

/** a61451e's step two on recording ports against core, including every ledger field after each action. */
class DamageCapStep2Test {
    private static final class Person {
        final String name;
        final UUID uuid;
        final boolean player;
        Person(String name, long uuid, boolean player) {
            this.name = name;
            this.uuid = new UUID(0, uuid);
            this.player = player;
        }
        @Override public boolean equals(Object other) { return other instanceof Person p && uuid.equals(p.uuid); }
        @Override public int hashCode() { return uuid.hashCode(); }
        @Override public String toString() { return name; }
    }

    private record Props(Person person, int serial) {}

    private static final class Reads implements CapNow, DamageCapBase.Settings {
        final List<String> calls = new ArrayList<>();
        final Map<String, Boolean> flags = new HashMap<>();
        final Map<String, Float> numbers = new HashMap<>();
        final Map<Person, Body> bodies = new IdentityHashMap<>();
        final Map<Body, Person> people = new IdentityHashMap<>();
        final Map<Person, Float> shields = new IdentityHashMap<>();
        long tick = 100;
        int dataCalls;
        float amount = 37F;
        Person amountOf;
        boolean client, head, scheme;
        final HurtNow now = (HurtNow) Proxy.newProxyInstance(HurtNow.class.getClassLoader(), new Class<?>[] {HurtNow.class}, (p, m, a) -> {
            if (!m.getName().equals("onHead")) throw new AssertionError(m.getName());
            calls.add("onHead " + people.get(a[0]));
            return head;
        });

        Body body(Person person) {
            return bodies.computeIfAbsent(person, key -> {
                Body body = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "player": calls.add("player " + key); return key.player;
                        case "uuid": calls.add("uuid " + key); return key.uuid;
                        case "maxHealth": calls.add("maxHealth " + key); return 20F;
                        default: throw new AssertionError(m.getName());
                    }
                });
                people.put(body, key);
                return body;
            });
        }

        @Override public boolean flag(String key, boolean def) { calls.add("flag " + key); return flags.getOrDefault(key, def); }
        @Override public float number(String key, float def) { calls.add("number " + key); return numbers.getOrDefault(key, def); }
        @Override public Object entity(Body b) { calls.add("entity " + people.get(b)); return people.get(b); }
        @Override public boolean client(Body b) { calls.add("level.client " + people.get(b)); return client; }
        @Override public long time(Body b) { calls.add("level.time " + people.get(b)); return tick; }
        Object props(Body b) {
            calls.add("getData " + people.get(b) + " " + ++dataCalls);
            return new Props(people.get(b), dataCalls);
        }
        float shield(Object props) {
            Props p = (Props) props;
            calls.add("shield " + p.serial);
            return shields.getOrDefault(p.person, 0F);
        }
        void giveShield(Object props, float amount) {
            Props p = (Props) props;
            calls.add("giveShield " + p.serial + " " + Float.floatToRawIntBits(amount));
            shields.put(p.person, shields.getOrDefault(p.person, 0F) + amount);
        }
        @Override public float shield(Body b) { return shield(props(b)); }
        @Override public <T> T withShield(Body b, ShieldUse<T> use) {
            Object props = props(b);
            var back = use.take(props, shield(props));
            if (back.give()) giveShield(props, back.amount());
            return back.value();
        }
        @Override public float takeAmount(Body b, float fallback) {
            calls.add("takeAmount " + people.get(b) + " " + Float.floatToRawIntBits(fallback));
            float out = amountOf == people.get(b) ? amount : fallback;
            amountOf = null;
            return out;
        }
        @Override public boolean schemeHit(Body b, Object hurt) { calls.add("schemeHit " + people.get(b)); return scheme; }
    }

    // The old code's state and decisions; no calls to DamageCapBase here.
    private static final class Old {
        private record Before(long tick, float shield) {}
        final Map<UUID, Map<UUID, DamageCapRule.Ledger>> ledgers = new HashMap<>();
        final Map<Object, Before> shields = new IdentityHashMap<>();
        String last = "";
        long cut;

        boolean capped(Body target, Reads r) { return target.player() || r.flag("damageCapMobs", false); }
        void incoming(Body target, Reads r) {
            if (target.player() && !r.client(target) && r.flag("damageCap", true))
                shields.put(r.entity(target), new Before(r.time(target), r.shield(r.props(target))));
        }
        boolean blastFree(float raw, Reads r) {
            if (!r.flag("damageCapBlasts", true)) return true;
            return raw > BalanceArmorBase.explRawLimit() && !BalanceArmorBase.bulletBlastNow();
        }
        float amount(Body target, float fallback, Reads r) { return r.takeAmount(target, fallback); }
        float apply(Body target, Body shooter, Before shieldBefore, long now, long click, boolean head, boolean sniper,
                float damage, Reads r) {
            if (head && sniper && r.schemeHit(target, null)) sniper = false;
            float shield = 0F;
            Object props = null;
            if (target.player() && shieldBefore != null && shieldBefore.tick() == now) {
                props = r.props(target);
                shield = Math.max(0F, shieldBefore.shield() - r.shield(props));
            }
            var ledger = ledgers.computeIfAbsent(target.uuid(), k -> new HashMap<>()).computeIfAbsent(shooter.uuid(),
                    k -> new DamageCapRule.Ledger());
            r.calls.add("getNewDamage");
            float before = damage;
            var result = DamageCapRule.take(ledger, limits(r), now, click, head, sniper, target.maxHealth(), shield, before);
            if (result.shield() > 0F && props != null) r.giveShield(props, result.shield());
            if (result.damage() < before) {
                r.calls.add("setNewDamage " + Float.floatToRawIntBits(result.damage()));
                cut++;
            }
            last = "click=" + click + " head=" + head + " sniper=" + sniper + " shield=" + shield + " damage=" + before + "->"
                    + result.damage() + " shieldBack=" + result.shield();
            return result.damage();
        }
        void sweep(long now) {
            shields.clear();
            for (Iterator<Map<UUID, DamageCapRule.Ledger>> it = ledgers.values().iterator(); it.hasNext();) {
                var byShooter = it.next();
                byShooter.values().removeIf(l -> l.idle(now));
                if (byShooter.isEmpty()) it.remove();
            }
        }
        void forget(Body target, Reads r) { ledgers.remove(target.uuid()); shields.remove(r.entity(target)); }
        void forget() { ledgers.clear(); shields.clear(); }

        private static DamageCapRule.Limits limits(Reads r) {
            var d = DamageCapRule.Limits.DEFAULT;
            return new DamageCapRule.Limits(share(r, "damageCapClick", d.click()), share(r, "damageCapHead", d.head()), share(r, "damageCapWindow", d.window()),
                    ticks(r, "damageCapWindowTicks", d.windowTicks()), share(r, "damageCapSniperHead", d.sniperHead()), ticks(r, "damageCapSniperDelay", d.sniperDelay()));
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

    private final Reads a = new Reads(), b = new Reads();
    private final Old old = new Old();
    private final Person player = new Person("player", 1, true), shooter = new Person("shooter", 2, true), mob = new Person("mob", 3, false);
    private String savedLast;
    private long savedCut;

    @BeforeEach void start() {
        savedLast = DamageCapBase.last;
        savedCut = DamageCapBase.cut;
        DamageCapBase.forgetOf();
        DamageCapBase.last = "";
        DamageCapBase.cut = 0;
        a.amountOf = b.amountOf = player;
    }

    @AfterEach void stop() {
        DamageCapBase.forgetOf();
        DamageCapBase.last = savedLast;
        DamageCapBase.cut = savedCut;
        BalanceArmorBase.BULLET_BLAST.remove();
    }

    private static void bits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }

    private static Object field(Object object, String name) throws Exception {
        Class<?> type = object instanceof Class<?> c ? c : object.getClass();
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object instanceof Class<?> ? null : object);
    }

    private static Object ledgerState(Object value) throws Exception {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> out = new HashMap<>();
            for (var entry : map.entrySet()) out.put(entry.getKey(), ledgerState(entry.getValue()));
            return out;
        }
        DamageCapRule.Ledger ledger = (DamageCapRule.Ledger) value;
        List<Object> taken = new ArrayList<>();
        for (Object t : (Iterable<?>) field(ledger, "taken"))
            taken.add(List.of(field(t, "tick"), field(t, "click"), Float.floatToRawIntBits((float) field(t, "amount"))));
        return List.of(field(ledger, "last"), taken);
    }

    private void same() throws Exception {
        assertEquals(a.calls, b.calls, "reads and writes, in order");
        assertEquals(old.last, DamageCapBase.last);
        assertEquals(old.cut, DamageCapBase.cut);
        assertEquals(ledgerState(old.ledgers), ledgerState(field(DamageCapBase.class, "LEDGERS")));
        Map<?, ?> actual = (Map<?, ?>) field(DamageCapBase.class, "SHIELD_BEFORE");
        assertEquals(old.shields.size(), actual.size());
        for (var entry : old.shields.entrySet()) {
            Object value = actual.entrySet().stream().filter(e -> e.getKey() == entry.getKey()).findFirst().orElseThrow().getValue();
            assertEquals(entry.getValue().tick(), field(value, "tick"));
            bits(entry.getValue().shield(), (float) field(value, "shield"));
        }
        assertEquals(a.dataCalls, b.dataCalls);
        bits(a.amount, b.amount);
        assertSame(a.amountOf, b.amountOf);
        assertEquals(a.shields.size(), b.shields.size());
        for (var e : a.shields.entrySet()) bits(e.getValue(), b.shields.get(e.getKey()));
        a.calls.clear();
        b.calls.clear();
    }

    private void tick(long tick) { a.tick = b.tick = tick; }
    private void shield(Person p, float shield) { a.shields.put(p, shield); b.shields.put(p, shield); }
    private void flag(String key, boolean value) { a.flags.put(key, value); b.flags.put(key, value); }
    private void incoming(Person p) throws Exception {
        old.incoming(a.body(p), a);
        DamageCapBase.incomingOf(b.body(p), b, b);
        same();
    }

    private float hit(Person target, Person source, long click, boolean sniper, float damage, boolean server, boolean blast) throws Exception {
        var beforeA = old.shields.remove(target);
        var beforeB = DamageCapBase.removeBeforeOf(target);
        same();
        if (!server) return damage;
        assertEquals(a.flag("damageCap", true), DamageCapBase.onOf(b));
        same();
        if (!a.flags.getOrDefault("damageCap", true)) return damage;
        boolean capped = old.capped(a.body(target), a);
        assertEquals(capped, DamageCapBase.cappedOf(b.body(target), b));
        same();
        if (!capped || source == null || source == target) return damage;
        float raw = old.amount(a.body(target), damage, a);
        bits(raw, DamageCapBase.takeAmountOf(b.body(target), damage, b));
        same();
        if (blast) {
            boolean free = old.blastFree(raw, a);
            assertEquals(free, DamageCapBase.blastFreeOf(raw, b));
            same();
            if (free) return damage;
        }
        boolean head = a.now.onHead(a.body(target));
        assertEquals(head, DamageCapBase.headOf(b.body(target), b.now));
        float expected = old.apply(a.body(target), a.body(source), beforeA, a.tick, click, head, sniper, damage, a);
        boolean effective = DamageCapBase.sniperOf(b.body(target), null, head, sniper, b);
        var result = DamageCapBase.applyOf(b.body(target), b.body(source), beforeB, b.tick, click, head, effective,
                () -> { b.calls.add("getNewDamage"); return damage; }, b, b);
        if (result.result().damage() < damage) b.calls.add("setNewDamage " + Float.floatToRawIntBits(result.result().damage()));
        DamageCapBase.finishOf(result);
        bits(expected, result.result().damage());
        same();
        return expected;
    }

    private float hit(Person target, Person source, long click, boolean sniper, float damage) throws Exception {
        return hit(target, source, click, sniper, damage, true, false);
    }

    private void forget(Person p) throws Exception {
        old.forget(a.body(p), a);
        DamageCapBase.forgetOf(b.body(p), b);
        same();
    }

    private void sweep(int serverTick, long worldTick) throws Exception {
        boolean due = serverTick % 200 == 0;
        assertEquals(due, DamageCapBase.sweepTickOf(serverTick));
        if (due) { old.sweep(worldTick); DamageCapBase.sweepOf(worldTick); }
        same();
    }

    @Test void identitySnapshotsAndUuidLedgersSurviveRespawn() throws Exception {
        Person respawn = new Person("respawn", 1, true), otherShooter = new Person("otherShooter", 4, true);
        shield(player, 40F);
        incoming(player);
        shield(respawn, 0F);
        bits(12F, hit(respawn, shooter, 100, false, 30F));
        shield(player, 20F);
        bits(0F, hit(player, shooter, 100, false, 30F));
        bits(12F, hit(respawn, otherShooter, 100, false, 30F));
        incoming(player);
        incoming(respawn);
        forget(respawn);
        assertEquals(1, old.shields.size());
        assertSame(player, old.shields.keySet().iterator().next());
        forget(player);
    }

    @Test void shieldOnlyThisTickAndOnePropertiesObject() throws Exception {
        shield(player, 40F);
        incoming(player);
        shield(player, 10F);
        bits(0F, hit(player, shooter, 100, false, 20F));
        bits(28F, a.shields.get(player));
        assertEquals(2, a.dataCalls);
        tick(101);
        incoming(player);
        shield(player, 0F);
        tick(102);
        bits(4F, hit(player, shooter, 101, false, 20F));
        assertEquals(3, a.dataCalls);
        tick(400);
        incoming(player);
        shield(player, 5F);
        hit(player, shooter, 400, false, 2F);
    }

    @Test void earlyExitsAlwaysConsumeSnapshotBeforeChecking() throws Exception {
        for (int exit = 0; exit < 6; exit++) {
            flag("damageCap", true);
            incoming(player);
            if (exit == 1) flag("damageCap", false);
            if (exit == 4) flag("damageCapBlasts", false);
            hit(player, exit == 2 ? null : exit == 3 ? player : shooter, 100, false, 30F, exit != 0, exit == 4);
            assertTrue(old.shields.isEmpty());
        }
        incoming(mob);
        hit(mob, shooter, 100, false, 30F);
        a.client = b.client = true;
        incoming(player);
        a.client = b.client = false;
        flag("damageCap", false);
        incoming(player);
    }

    @Test void forgetAndSweepAtTwoHundredTicksKeepDiagnostics() throws Exception {
        hit(player, shooter, 100, false, 30F);
        incoming(player);
        sweep(199, 300);
        assertFalse(old.ledgers.isEmpty());
        assertFalse(old.shields.isEmpty());
        sweep(200, 299);
        assertFalse(old.ledgers.isEmpty());
        assertTrue(old.shields.isEmpty());
        sweep(399, 300);
        sweep(400, 300);
        assertTrue(old.ledgers.isEmpty());
        Person later = new Person("later", 5, true);
        tick(500);
        hit(player, shooter, 500, false, 30F);
        tick(501);
        hit(player, later, 501, false, 30F);
        sweep(600, 700);
        assertEquals(1, old.ledgers.get(player.uuid).size());
        assertTrue(old.ledgers.get(player.uuid).containsKey(later.uuid));
        sweep(800, 701);
        assertTrue(old.ledgers.isEmpty());
        tick(900);
        hit(player, shooter, 900, true, 30F);
        flag("damageCapMobs", true);
        hit(mob, shooter, 900, false, 30F);
        forget(player);
        assertFalse(old.ledgers.isEmpty());
        old.forget();
        DamageCapBase.forgetOf();
        same();
        assertFalse(old.last.isEmpty());
        assertTrue(old.cut > 0);
    }

    @Test void cappedAndBlastFreeShortCircuitAndBoundaries() throws Exception {
        for (Person p : List.of(player, mob)) for (boolean enabled : List.of(false, true)) {
            flag("damageCapMobs", enabled);
            assertEquals(old.capped(a.body(p), a), DamageCapBase.cappedOf(b.body(p), b));
            same();
        }
        for (boolean enabled : List.of(false, true)) for (boolean bullet : List.of(false, true)) {
            flag("damageCapBlasts", enabled);
            BalanceArmorBase.BULLET_BLAST.set(bullet);
            for (float raw : new float[] {Float.NaN, Float.NEGATIVE_INFINITY, -0F, 0F, 60F, Math.nextUp(60F), Float.POSITIVE_INFINITY}) {
                assertEquals(old.blastFree(raw, a), DamageCapBase.blastFreeOf(raw, b));
                same();
            }
        }
    }

    @Test void amountIsConsumedExactlyOnceOnEachCall() throws Exception {
        for (int call = 0; call < 2; call++) {
            float expected = old.amount(a.body(player), 0F, a);
            bits(call == 0 ? 37F : 0F, expected);
            bits(expected, DamageCapBase.takeAmountOf(b.body(player), 0F, b));
            same();
        }
        a.amountOf = b.amountOf = player;
        bits(old.amount(a.body(mob), 99F, a), DamageCapBase.takeAmountOf(b.body(mob), 99F, b));
        same();
        bits(old.amount(a.body(player), 0F, a), DamageCapBase.takeAmountOf(b.body(player), 0F, b));
        same();
    }

    @Test void headSchemeDiagnosticsAndFloatBits() throws Exception {
        long tick = 100;
        for (boolean head : List.of(false, true)) for (boolean sniper : List.of(false, true)) for (boolean scheme : List.of(false, true)) {
            a.head = b.head = head;
            a.scheme = b.scheme = scheme;
            tick(tick += 250);
            hit(player, shooter, tick, sniper, 33.3F);
        }
        for (float damage : new float[] {-0F, 0F, -3F, Float.intBitsToFloat(0x7fc00001), Float.POSITIVE_INFINITY, Float.MIN_VALUE}) {
            tick(tick += 250);
            hit(player, shooter, tick, false, damage);
        }
    }
}
