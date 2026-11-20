package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import static acup.gunfeel.BalanceFinalBase.*;
import static acup.gunfeel.BalanceFinalStep2Test.*;

import java.lang.reflect.Field;
import java.util.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;
import acup.gunfeel.host.Body;

/** The 708ad2b explosion branch against core, including calls and state at callback boundaries. */
class BalanceFinalStep3Test {
    record Explosion(Object owner, double x, double y, double z, float size) {}
    record Attribute(double base, Double fbi) {}
    static final Object BLAST = new Object(), GRENADE_OBJECT = new Object();
    static final Explosion EXPLOSION = new Explosion(SHOOTER, 0, 0, 0, 4);
    static final Source EXPL = new Source(null, SHOOTER, "EXPL", true);

    static final class Reads extends BalanceFinalStep2Test.Reads {
        Legacy ref;
        String filling = "he", failure;
        boolean error, impact, ownerPlayer = true;
        Object owner = SHOOTER;
        double x = 1, y = 0, z = 0;
        Attribute[] attributes = {new Attribute(20, 7.25), new Attribute(2, -0.5)};
        void check(String at) {
            if (at.equals(failure)) {
                if (error) throw new AssertionError(at);
                throw new IllegalStateException(at);
            }
        }
        @Override float blastOrFrag(Body b, Object s) { return ref.blastOrFrag(b, s); }
        @Override public boolean rawClient(Object e) { calls.add("grenade.client"); return client; }
        @Override public String grenadeFilling(Object g) { calls.add("filling"); check("filling"); return filling; }
        @Override public Object boundBullet(Object b) { calls.add("boundBullet"); check("boundBullet"); return BULLET; }
        @Override public Object projectileOwner(Object b) { calls.add("owner"); return owner; }
        @Override public Object currentImpact() { calls.add("impact"); return impact ? BLAST : null; }
        @Override public Object exploder(Object e) { calls.add("exploder"); return ((Explosion) e).owner(); }
        @Override public boolean playerEntity(Object e) { calls.add("owner.player"); return ownerPlayer; }
        @Override public double boxDistance(Body target, Object value) {
            calls.add("box"); check("box");
            Explosion e = (Explosion) value;
            double dx = Math.max(Math.max(x - e.x(), 0), e.x() - (x + 0.6));
            double dy = Math.max(Math.max(y - e.y(), 0), e.y() - (y + 1.8));
            double dz = Math.max(Math.max(z - e.z(), 0), e.z() - (z + 0.6));
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        @Override public double blastSize(Object e) { calls.add("size"); return ((Explosion) e).size(); }
        @Override public Object ownAttribute(Body b, int which) { calls.add("attribute " + which); check("attribute"); return attributes[which]; }
        @Override public Object ownModifier(Object a) { calls.add("fbi"); return ((Attribute) a).fbi(); }
        @Override public double attributeBase(Object a) { calls.add("base"); return ((Attribute) a).base(); }
        @Override public double modifierAmount(Object m) { calls.add("amount"); return (Double) m; }
        @Override public String worn(Body b, int slot) { check("worn"); return super.worn(b, slot); }
        @Override public void logBlastHit(Body b, Object s, Object p, String id, double tier, boolean head) {
            calls.add(Arrays.asList("logHit", raw(b), s, p, id, Double.doubleToRawLongBits(tier), head));
        }
    }

    // 708ad2b's methods; only game getters and armorOf's existing boundary are substituted.
    static final class Legacy extends BalanceFinalStep2Test.Legacy {
        final Reads live;
        Legacy(Reads r) { super(r); this.live = r; r.ref = this; }
        /** a blast of the ladder's under way on one target: what one hit of it takes (of 20 HP and the pool), its pair */
        private record BlastNow(Object target, double take, String id, double tier, Object projectile) {
        }

        final ThreadLocal<BlastNow> BLAST_NOW = new ThreadLocal<>();

        /** a hand grenade going off (ItemGrenadeFilling.explode): its filling's name */
        final ThreadLocal<String> GRENADE = new ThreadLocal<>();

        /** a hand grenade goes off: its blasts are dealt with its filling known */
        void grenade(Object grenade, Runnable explode) {
            if (live.rawClient(grenade) || !live.enabled()) {
                explode.run();
                return;
            }
            String was = GRENADE.get(), filling;
            try {
                filling = live.grenadeFilling(grenade);
            } catch (RuntimeException e) {
                filling = null;
            }
            if (filling != null) GRENADE.set(filling);
            else GRENADE.remove();
            try {
                explode.run();
            } finally {
                if (was != null) GRENADE.set(was);
                else GRENADE.remove();
            }
        }

        /** Runs a blast hit with the scheme context. */
        boolean blastAt(Object explosion, Object blast, Body target, boolean struck,
                boolean explosive, java.util.function.Supplier<Boolean> hurt) {
            if (!explosive || explosion == null || target == null || live.client(target) || !live.enabled()) return hurt.get();
            BlastNow b;
            try {
                b = blastOf(explosion, blast, target, struck);
            } catch (RuntimeException e) {
                b = null;
            }
            if (b == null) return hurt.get();
            BlastNow was = BLAST_NOW.get();
            BLAST_NOW.set(b);
            try {
                return hurt.get();
            } finally {
                if (was != null) BLAST_NOW.set(was);
                else BLAST_NOW.remove();
            }
        }

        /** the blast's hurt under way on {@code target} is the ladder's (no part's multiplier on it, NTM's early check passed) */
        boolean blastNow(Body target) {
            BlastNow b = BLAST_NOW.get();
            return b != null && b.target() == live.raw(target);
        }

        /** a hit the scheme takes, a blast's or a GL-54 fragment's among them (BalanceArmor.covers) */
        boolean takes(Body target, Object source) {
            return hit(target, source) != null || blastNow(target) && expl(source) || !Float.isNaN(frag(target, source, false));
        }

        boolean expl(Object source) {
            return "EXPL".equals(live.category(source));
        }

        BlastNow blastOf(Object explosion, Object blast, Body target, boolean struck) {
            State s = state();
            var r = s.blast();
            // balanceArmorBlasts off: the blast is NTM's (BalanceArmor.category), so not the ladder's either
            if (!r.on() || !ReaimTimes.flag("balanceArmorBlasts", true)) return null;
            var armor = BalanceFinalBase.armorOf(target, s, live, live::balanceOn, live::worn);
            if (armor == null) return null;
            String id;
            double tier, near;
            Object owner, projectile;
            if (blast != null) {
                // a launcher's round (its gun in balance.final.blast.launchers): its pair by the shot
                var bullet = live.boundBullet(blast);
                id = pairOf(bullet);
                int dot = id == null ? -1 : id.indexOf('.');
                if (dot <= 0) return null;
                var l = r.launchers().get(id.substring(0, dot));
                Double round = r.rounds().get(id.substring(dot + 1));
                if (l == null || round == null) return null;
                tier = BalanceBlastRule.pairTier(l, round, r.cap());
                double t = tier;
                near = s.cache().computeIfAbsent(new CacheKey("blast " + id, armor), k -> (double) BalanceBlastRule.nearLauncher(l, BalanceBlastRule.target(t, armor, s.params(), r)));
                owner = live.projectileOwner(bullet);
                projectile = bullet;
            } else {
                // a hand grenade (its filling in balance.final.blast.grenades), not inside a shot's impact
                String filling = GRENADE.get();
                Double t = filling == null || live.currentImpact() != null ? null : r.grenades().get(filling);
                if (t == null) return null;
                id = "grenade." + filling;
                tier = t;
                near = s.cache().computeIfAbsent(new CacheKey("blast " + id, armor), k -> BalanceBlastRule.nearGrenade(BalanceBlastRule.target(t, armor, s.params(), r), s.params(), r));
                owner = live.exploder(explosion);
                // a mob's or a machine's grenade: NTM's numbers, as their shots
                if (owner != null && !live.playerEntity(owner)) return null;
                projectile = null;
            }
            // where the target is: struck, or its box's distance from the blast against NTM's reach (twice the size)
            double where = BalanceBlastRule.where(struck, live.boxDistance(target, explosion), 2.0 * live.blastSize(explosion), r.hits());
            double share = BalanceBlastRule.share(near, where);
            // one's own blast: half
            if (owner == live.raw(target)) share *= 0.5;
            return new BlastNow(live.raw(target), share * (BalanceFinalRule.HP + armor.pool()), id, tier, projectile);
        }

        /** Removes the mob's own base armor and FBI modifier from scheme hits. */
        float[] mobOwnArmor(Body target, Object source) {
            if (target.player() || live.client(target) || live.category(source) == null || !live.enabled()
                    || !ReaimTimes.text("balance.final.mobArmor", "player").trim().equalsIgnoreCase("player"))
                return NO_OWN;
            try {
                if (!takes(target, source)) return NO_OWN;
            } catch (RuntimeException e) {
                return NO_OWN;
            }
            return new float[] {own(target, 0),
                    own(target, 1)};
        }

        final float[] NO_OWN = {0F, 0F};

        /** the mob's own share of an attribute: its base and balance.fbiArmor's modifier */
        float own(Body target, int attribute) {
            var a = live.ownAttribute(target, attribute);
            if (a == null) return 0F;
            var fbi = live.ownModifier(a);
            return (float) Math.max(0, live.attributeBase(a) + (fbi == null ? 0 : live.modifierAmount(fbi)));
        }

        /** a blast's or a GL-54 fragment's take of 20 HP and the pool, or NaN when the hit is neither (the log counts it) */
        float blastOrFrag(Body target, Object source) {
            BlastNow b = BLAST_NOW.get();
            if (b != null && b.target() == live.raw(target) && expl(source)) {
                if (live.logOn()) live.logBlastHit(target, source, b.projectile(), b.id(), b.tier(), false);
                return (float) b.take();
            }
            return frag(target, source, true);
        }

        /** One fragment's share of a full cone burst, without a head multiplier. */
        float frag(Body target, Object source, boolean log) {
            if (live.client(target) || !live.enabled()) return Float.NaN;
            String cat = live.category(source);
            if (cat == null || "EXPL".equals(cat)) return Float.NaN;
            Object projectile = live.direct(source);
            String id = pairOf(projectile);
            if (id == null || !id.startsWith("gl54.")) return Float.NaN;
            State s = state();
            var r = s.blast();
            String kind = id.substring(5);
            var f = r.frags().get(kind);
            var l = r.launchers().get("gl54");
            Double round = r.rounds().get(kind);
            var spec = acup.gunfeel.gl54.frag.FragSpec.of(acup.gunfeel.gl54.GrenadeKind.of(kind));
            if (!r.on() || f == null || l == null || round == null || spec == null) return Float.NaN;
            var armor = BalanceFinalBase.armorOf(target, s, live, live::balanceOn, live::worn);
            if (armor == null) return Float.NaN;
            double tier = BalanceBlastRule.pairTier(l, round, r.cap());
            double role = s.params().roles().getOrDefault("airburst", 1.2);
            double click = s.cache().computeIfAbsent(new CacheKey("frag " + id, armor), k -> BalanceBlastRule.fragClick(l, f, tier, armor, s.params(), role));
            if (log && live.logOn()) live.logBlastHit(target, source, projectile, id, tier, false);
            // the click is a burst whose cone all hits (the ring's fragments are the side spray, each the same)
            return (float) (click / Math.max(1, spec.cone()));
        }
    }

    static Object field(String name) {
        try {
            Field f = BalanceFinalBase.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    @SuppressWarnings("unchecked") static Map<Object, Object> coreMap(String name) { return (Map<Object, Object>) field(name); }
    static ThreadLocal<?> context(String name) { return (ThreadLocal<?>) field(name); }
    static Object normalized(Object value) {
        if (value instanceof float[] a) {
            List<Integer> bits = new ArrayList<>();
            for (float f : a) bits.add(Float.floatToRawIntBits(f));
            return bits;
        }
        if (value instanceof Float || value instanceof Double || value instanceof Hit) return bits(value);
        if (value != null && value.getClass().isRecord()) {
            List<Object> parts = new ArrayList<>();
            for (var c : value.getClass().getRecordComponents()) {
                try { parts.add(normalized(c.getAccessor().invoke(value))); }
                catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            }
            return parts;
        }
        return value;
    }
    static Map<Object, Object> normalizedMap(Map<?, ?> map) {
        Map<Object, Object> out = new HashMap<>();
        map.forEach((k, v) -> out.put(k, normalized(v)));
        return out;
    }

    static final class Engine {
        final Reads r;
        final Legacy old;
        Engine(Reads r, Legacy old) { this.r = r; this.old = old; }
        void grenade(Runnable run) { if (old != null) old.grenade(GRENADE_OBJECT, run); else BalanceFinalBase.grenade(GRENADE_OBJECT, run, r, r::enabled); }
        boolean blast(Explosion x, Object b, Body target, boolean struck, boolean explosive, Supplier<Boolean> run) {
            return old != null ? old.blastAt(x, b, target, struck, explosive, run) : BalanceFinalBase.blastAt(x, b, target, struck, explosive, run, r, r::enabled, r);
        }
        boolean blast(Supplier<Boolean> run) { return blast(EXPLOSION, BLAST, r.target, false, true, run); }
        Object hit(Source s) { return old != null ? old.hit(r.target, s) : BalanceFinalBase.hit(r.target, s, r, r::enabled, r); }
        float damage(Source s) { return old != null ? old.damage(r.target, s) : BalanceFinalBase.damage(r.target, s, r, r::enabled, r); }
        float drain(Source s) { return old != null ? old.drain(r.target, s) : BalanceFinalBase.drain(r.target, s, r, r::enabled, r); }
        boolean takes(Source s) { return old != null ? old.takes(r.target, s) : BalanceFinalBase.takes(r.target, s, r, r::enabled, r); }
        float[] own(Source s) { return old != null ? old.mobOwnArmor(r.target, s) : BalanceFinalBase.mobOwnArmor(r.target, s, r, r::enabled, r); }
        boolean current(Body target) { return old != null ? old.blastNow(target) : BalanceFinalBase.blastNow(target, r); }
        void fire() { if (old != null) old.fired(BULLET, r.shooter, GUN, AMMO); else BalanceFinalBase.fired(BULLET, r.shooter, GUN, AMMO, r, r::enabled); }
        void probe() {
            r.calls.add(Arrays.asList("contexts", normalized(old != null ? old.BLAST_NOW.get() : context("BLAST_NOW").get()), old != null ? old.GRENADE.get() : context("GRENADE").get()));
            r.calls.add(Arrays.asList("state", normalizedMap(old != null ? old.PAIR : coreMap("PAIR")),
                    normalizedMap(old != null ? old.SHARE : coreMap("SHARE")), normalizedMap(old != null ? old.LAST : coreMap("LAST")),
                    normalizedMap(old != null ? old.IR_SUBS : coreMap("IR_SUBS")),
                    old != null ? new HashMap<>(r.hits) : normalizedMap(coreMap("BLAST_HITS")), normalizedMap(state().cache())));
        }
        void exercise(Source s) {
            probe();
            r.calls.add(Arrays.asList("answers", normalized(hit(s)), normalized(damage(s)), normalized(drain(s)), takes(s), normalized(own(s)), current(r.target), current(r.body(TARGET)), current(r.body(new Entity(TARGET.id(), TARGET.name()))), current(r.body(SHOOTER)), current(null)));
            probe();
        }
    }

    static final class Compare {
        final Reads old = new Reads(), now = new Reads();
        final Legacy ref = new Legacy(old);
        final Engine a = new Engine(old, ref), b = new Engine(now, null);
        final Map<CacheKey, Double> oldCache = new HashMap<>(), newCache = new HashMap<>();
        Compare() {
            for (String map : List.of("PAIR", "SHARE", "LAST", "IR_SUBS", "BLAST_HITS")) coreMap(map).clear();
            context("GRENADE").remove(); context("BLAST_NOW").remove();
        }
        void configure(Consumer<Reads> setup) { setup.accept(old); setup.accept(now); }
        Object run(Engine engine, Function<Engine, Object> operation) {
            try { return operation.apply(engine); }
            catch (RuntimeException | Error e) { return List.of(e.getClass(), e.getMessage()); }
        }
        void step(Function<Engine, Object> operation) {
            old.calls.clear(); now.calls.clear();
            State s = state();
            Object expected, actual;
            try {
                setState(withCache(s, oldCache)); expected = run(a, operation);
                setState(withCache(s, newCache)); actual = run(b, operation);
            } finally { setState(s); }
            assertEquals(normalized(expected), normalized(actual), "result bits or exception");
            assertEquals(old.calls, now.calls, "live reads and log order/count");
            assertEquals(normalizedMap(oldCache), normalizedMap(newCache), "cache bits");
            assertEquals(ref.PAIR, coreMap("PAIR"), "PAIR raw keys");
            assertEquals(ref.SHARE, coreMap("SHARE"), "SHARE raw keys");
            assertEquals(ref.IR_SUBS, coreMap("IR_SUBS"), "IR_SUBS raw keys");
            assertEquals(normalizedMap(ref.LAST), normalizedMap(coreMap("LAST")), "LAST");
            assertEquals(old.hits, normalizedMap(coreMap("BLAST_HITS")), "BLAST_HITS raw keys");
            assertEquals(normalized(ref.BLAST_NOW.get()), normalized(context("BLAST_NOW").get()), "BLAST_NOW");
            assertEquals(ref.GRENADE.get(), context("GRENADE").get(), "GRENADE");
            assertEquals(old.count, now.count, "shot count");
        }
        void pair(String gun, String ammo) { configure(r -> { r.gun = gun; r.ammo = ammo; }); step(e -> { e.fire(); return null; }); }
    }

    @Test void launchersGrenadesDistanceStruckAndOwnBlast() {
        for (boolean log : new boolean[] {false, true}) for (boolean player : new boolean[] {false, true})
            for (String kit : new String[] {"hev", "dns", "missing"}) for (double distance : new double[] {-1, 0, Math.nextDown(2.0), 2, 4, Math.nextDown(8.0), 8, Math.nextUp(8.0), 9})
                for (boolean struck : new boolean[] {false, true}) for (boolean own : new boolean[] {false, true}) {
                    Compare c = new Compare();
                    c.configure(r -> { r.logging = log; r.player = player; r.kit = kit; r.x = distance; r.owner = own ? TARGET : SHOOTER; r.head = true; r.health = 37; });
                    c.pair("gl54", "he");
                    c.step(e -> e.blast(EXPLOSION, BLAST, e.r.target, struck, true, () -> { e.exercise(EXPL); return false; }));
                    for (Object owner : Arrays.asList(null, TARGET, SHOOTER)) {
                        c.step(e -> { e.grenade(() -> e.blast(new Explosion(owner, 0, 0, 0, 4), null, e.r.target, struck, true, () -> { e.exercise(EXPL); return true; })); return null; });
                    }
                }
    }

    @Test void nestedContextsAndCallbackExceptions() {
        Compare c = new Compare(); c.configure(r -> r.logging = true); c.pair("gl54", "he");
        c.step(e -> {
            e.grenade(() -> {
                e.probe();
                e.blast(() -> {
                    e.exercise(EXPL);
                    e.r.filling = "powder";
                    try {
                        e.grenade(() -> e.blast(EXPLOSION, null, e.r.body(SHOOTER), true, true, () -> {
                            e.exercise(EXPL); throw new IllegalArgumentException("callback");
                        }));
                        fail("callback swallowed");
                    } catch (IllegalArgumentException expected) { assertEquals("callback", expected.getMessage()); }
                    e.r.filling = null;
                    e.grenade(e::probe);
                    e.exercise(EXPL); return true;
                });
                e.probe();
            });
            e.probe(); return null;
        });
        c.step(e -> e.blast(() -> { e.probe(); throw new AssertionError("callback error"); }));
        c.step(e -> { e.grenade(() -> { e.probe(); throw new IllegalStateException("grenade callback"); }); return null; });
        c.step(e -> { e.exercise(EXPL); return null; });
    }

    @Test void calculationCatchIsRuntimeOnlyAndDoesNotCatchCallbacks() {
        for (String failure : List.of("boundBullet", "box", "worn", "filling")) for (boolean error : new boolean[] {false, true}) {
            Compare c = new Compare(); c.pair("gl54", "he");
            c.configure(r -> { r.failure = failure; r.error = error; });
            c.step(e -> {
                e.grenade(() -> e.blast(() -> { e.probe(); e.r.calls.add("callback"); return true; }));
                return null;
            });
            c.configure(r -> r.failure = null);
            c.step(e -> { e.exercise(EXPL); return null; });
        }
    }

    @Test void fragmentConeCountsNoHeadAndMobBasePlusFbi() {
        for (String kind : List.of("airburst", "ap", "inc", "he", "unknown")) for (boolean log : new boolean[] {false, true})
            for (boolean player : new boolean[] {false, true}) for (String kit : List.of("hev", "security", "dns", "missing")) {
                Compare c = new Compare(); c.configure(r -> { r.logging = log; r.head = true; r.player = player; r.kit = kit; r.health = 43; });
                c.pair("gl54", kind);
                Source s = new Source(BULLET, SHOOTER, "PHYS", false);
                c.step(e -> { e.exercise(s); return null; });
                for (Attribute[] attrs : new Attribute[][] {{null, null}, {new Attribute(-3, null), new Attribute(4, null)}, {new Attribute(20, 8.0), new Attribute(0, -2.0)}}) {
                    c.configure(r -> r.attributes = attrs);
                    c.step(e -> e.own(s));
                }
            }
    }

    @Test void vntBulletSelectionRepeatKeysAndMobArmorCatchBoundaries() {
        for (boolean vnt : new boolean[] {false, true}) for (boolean bullet : new boolean[] {false, true}) {
            Compare c = new Compare(); c.configure(r -> { r.vnt = vnt; r.explosionHit = bullet; r.player = false; r.logging = true; }); c.pair("stg77", "r556_ap");
            Source first = new Source(BULLET, SHOOTER, "PHYS", false), next = new Source(BULLET, SHOOTER, "PHYS", false);
            for (Source s : List.of(EXPL, first, next, first, new Source(null, null, null, false))) c.step(e -> { e.exercise(s); return null; });
            c.step(e -> { e.r.tick++; e.r.body(TARGET); e.exercise(next); return null; });
            c.configure(r -> r.failure = "worn"); c.step(e -> e.own(first));
            c.configure(r -> { r.failure = "attribute"; r.error = true; }); c.step(e -> e.own(first));
        }
        Compare c = new Compare(); c.configure(r -> { r.vnt = true; r.logging = true; }); c.pair("tesla_cannon", "capacitor_ir");
        Source fallback = new Source(null, SHOOTER, "EN", false);
        for (int age : new int[] {0, 5, 6}) {
            c.configure(r -> r.tick = 100 + age);
            c.step(e -> { e.exercise(fallback); return null; });
        }
    }

    @Test void guardsMissingPairsImpactAndUnrecognizedGrenades() {
        for (int mode = 0; mode < 10; mode++) {
            final int m = mode;
            Compare c = new Compare(); c.pair(m == 8 ? "unknown" : "gl54", m == 9 ? "unknown" : "he");
            c.configure(r -> { r.client = m == 0; r.enabled = m != 1; r.impact = m == 2; r.ownerPlayer = m != 3; r.filling = m == 4 ? "unknown" : m == 5 ? null : "demo"; });
            c.step(e -> { e.grenade(() -> e.blast(m == 6 ? null : EXPLOSION, null, m == 7 ? null : e.r.target, false, m != 9, () -> { e.probe(); return true; })); return null; });
            c.step(e -> e.blast(() -> { e.probe(); return false; }));
        }
    }

    @Test void blastTablesOffMissingEntriesAndThreeAxisBoxDistance() {
        State original = state();
        try {
            var r = original.blast();
            for (int mode = 0; mode < 5; mode++) {
                var rule = new BalanceBlastRule.Rule(mode != 0, mode == 1 ? Map.of() : r.launchers(),
                        mode == 2 ? Map.of() : r.rounds(), mode == 3 ? Map.of() : r.grenades(), r.shift(), r.cap(), r.hits(), mode == 4 ? Map.of() : r.frags());
                setState(new State(original.version(), original.params(), original.pairs(), original.refs(), original.sets(), original.weights(), original.empty(), new HashMap<>(), rule, original.range(), original.acid()));
                Compare c = new Compare(); c.configure(v -> { v.x = 1.25; v.y = 2.75; v.z = -3.5; v.logging = true; });
                c.pair("gl54", "airburst");
                c.step(e -> { e.exercise(new Source(BULLET, SHOOTER, "PHYS", false)); return null; });
                for (float size : new float[] {0, -0.0F, -1, 2, Float.MIN_NORMAL}) {
                    c.step(e -> e.blast(new Explosion(null, -0.25, 0.75, 1.5, size), BLAST, e.r.target, false, true, () -> { e.exercise(EXPL); return true; }));
                    c.step(e -> { e.grenade(() -> e.blast(new Explosion(null, 1.5, 3, -3.25, size), null, e.r.target, false, true, () -> { e.exercise(EXPL); return true; })); return null; });
                }
            }
        } finally { setState(original); }
    }
}
