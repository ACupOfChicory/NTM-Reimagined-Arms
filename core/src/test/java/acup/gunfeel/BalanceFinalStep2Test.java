package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;
import static acup.gunfeel.BalanceFinalBase.*;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.FinalShots;
import acup.gunfeel.host.HurtNow;

/** The pre-transfer algorithm, raw keys, raw bits and live calls after every action. */
class BalanceFinalStep2Test {
    record Entity(int id, String name) {}
    record Source(Object direct, Object shooter, String cat, boolean explosion) {}
    static final Object GUN = new Object(), AMMO = new Object(), IR = new Object();
    static final Entity SHOOTER = new Entity(7, "shooter"), TARGET = new Entity(8, "target");
    static final Entity BULLET = new Entity(9, "bullet"), SAME_ID = new Entity(9, "other");
    static final String[] MAPS = {"PAIR", "SHARE", "LAST", "IR_SUBS"};

    private static Map<Object, Object> map(String name) {
        try {
            var field = BalanceFinalBase.class.getDeclaredField(name);
            field.setAccessible(true);
            return (Map<Object, Object>) field.get(null);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    static class Reads implements FinalShots, HurtNow {
        @Override public float floorOf(Object direct, int tier) { throw new UnsupportedOperationException(); }
        @Override public float[] underOf(Object direct, int tier) { throw new UnsupportedOperationException(); }
        final List<Object> calls = new ArrayList<>();
        final Map<Body, Object> entities = new IdentityHashMap<>();
        final Map<Object, Integer> count = new HashMap<>();
        final Map<Object, List<Object>> hits = new HashMap<>();
        final Body shooter = body(SHOOTER), target = body(TARGET);
        Legacy legacy;
        boolean client, enabled = true, guns = true, logging, head, vnt, explosionHit = true, irFail, mob = true, player = true;
        boolean badNames, badRatio, linkage, noGun;
        String gun = "stg77", ammo = "r556_ap", loaded, kit = "hev";
        float health = 20, blast = Float.NaN, ratio = 0.375F;
        long tick = 100;
        double distance = 40;

        Body body(Object entity) {
            Body b = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> switch (m.getName()) {
                case "maxHealth" -> { calls.add("maxHealth"); yield health; }
                case "player" -> { calls.add("player"); yield player; }
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p == a[0];
                default -> throw new AssertionError(m.getName());
            });
            entities.put(b, entity);
            return b;
        }
        void fail() { if (linkage) throw new NoClassDefFoundError("table"); else throw new IllegalStateException("table"); }
        boolean enabled() { calls.add("on"); return enabled; }
        @Override public Object raw(Body b) { return entities.get(b); }
        @Override public boolean client(Body b) { calls.add(List.of("client", raw(b))); return client; }
        @Override public boolean gunsOn() { calls.add("gunsOn"); return guns; }
        @Override public Object gunOf(Body b) { calls.add("gunOf"); return noGun ? null : GUN; }
        @Override public String gunName(Object item) { calls.add("gunName"); if (badNames) fail(); return gun; }
        @Override public String ammoName(Object config) { calls.add("ammoName"); return ammo; }
        @Override public float as(Object gun, Object from, Object as) { calls.add("as"); if (badRatio) fail(); return ratio; }
        @Override public String loadedAmmo(Body b, Object gun) { calls.add("loadedAmmo"); return loaded; }
        @Override public long time(Object entity) { calls.add(List.of("time", entity)); return tick; }
        @Override public boolean irConfig(Object config) { calls.add("irConfig"); if (irFail) fail(); return config == IR; }
        @Override public Object direct(Object s) { calls.add("direct"); return ((Source) s).direct(); }
        @Override public Object entity(Object s) { calls.add("entity"); return ((Source) s).shooter(); }
        @Override public String category(Object s) { calls.add("category"); return ((Source) s).cat(); }
        @Override public boolean vnt() { calls.add("vnt"); return vnt; }
        @Override public boolean bulletBlast() { calls.add("bulletBlast"); return explosionHit; }
        boolean struck(Body b) { calls.add("struck"); return explosionHit; }
        Object bullet() { calls.add("bullet"); return BULLET; }
        boolean again(Body b, Object s) {
            boolean ir = legacy == null ? BalanceFinalBase.irSub(s, this) : legacy.irSub(s);
            if (!(vnt() || ir) || explosion(s) || "EXPL".equals(category(s))) return false;
            long now = time(raw(b));
            List<Object> was = hits.get(raw(b));
            int id = System.identityHashCode(s);
            Object owner = entity(s);
            int shooter = owner == null ? Integer.MIN_VALUE : ((Entity) entity(s)).id();
            if (was != null && (long) was.get(2) == now && (int) was.get(1) == shooter && (int) was.get(0) != id) return true;
            hits.put(raw(b), List.of(id, shooter, now));
            return false;
        }
        @Override public boolean explosion(Object s) { calls.add("isExplosion"); return ((Source) s).explosion(); }
        float blastOrFrag(Body b, Object s) {
            if (!Float.isNaN(blast) && "EXPL".equals(category(s))) {
                if (logOn()) logBlastHit(b, s, null, "test", 0, false);
                return blast;
            }
            if (client(b) || !enabled()) return Float.NaN;
            String cat = category(s);
            if (cat == null || "EXPL".equals(cat)) return Float.NaN;
            Object projectile = direct(s);
            String id = legacy.pairOf(projectile);
            if (id == null || !id.startsWith("gl54.")) return Float.NaN;
            throw new AssertionError("step 2 has no fragments");
        }
        @Override public Object struckNow() { calls.add("struck"); return explosionHit ? TARGET : null; }
        @Override public Object bulletNow() { return bullet(); }
        @Override public int entityId(Object entity) { return ((Entity) entity).id(); }
        @Override public boolean rawClient(Object entity) { throw new AssertionError(); }
        @Override public String grenadeFilling(Object grenade) { throw new AssertionError(); }
        @Override public Object boundBullet(Object blast) { throw new AssertionError(); }
        @Override public Object projectileOwner(Object bullet) { throw new AssertionError(); }
        @Override public Object currentImpact() { throw new AssertionError(); }
        @Override public Object exploder(Object explosion) { throw new AssertionError(); }
        @Override public boolean playerEntity(Object entity) { throw new AssertionError(); }
        @Override public double boxDistance(Body target, Object explosion) { throw new AssertionError(); }
        @Override public double blastSize(Object explosion) { throw new AssertionError(); }
        @Override public Object ownAttribute(Body target, int which) { throw new AssertionError(); }
        @Override public Object ownModifier(Object attribute) { throw new AssertionError(); }
        @Override public double attributeBase(Object attribute) { throw new AssertionError(); }
        @Override public double modifierAmount(Object modifier) { throw new AssertionError(); }
        @Override public void logBlastHit(Body b, Object source, Object projectile, String id, double tier, boolean head) {
            calls.add(Arrays.asList("logHit", raw(b), source, projectile, id, Double.doubleToRawLongBits(tier), head));
        }
        @Override public boolean mobShooter(Object s) { entity(s); calls.add("mobShooterType"); return mob; }
        @Override public double distance(Body b, Object s) { entity(s); calls.add("position.shooter"); calls.add("position.target"); return distance; }
        @Override public boolean logOn() { calls.add("logOn"); return logging; }
        @Override public void logShot(Object p, Body b, Object gun) { calls.add(List.of("logShot", p, raw(b), gun)); }
        @Override public void logHit(Body b, Object s, String id, double tier, boolean head) { calls.add(Arrays.asList("logHit", raw(b), s, null, id, Double.doubleToRawLongBits(tier), head)); }
        @Override public void countShot(Body b) { count.merge(raw(b), 1, Integer::sum); }
        @Override public float headCap() { calls.add("headCap"); return 0.23F; }
        @Override public boolean balanceOn() { calls.add("balanceOn"); return true; }
        @Override public String worn(Body b, int slot) { calls.add("worn " + slot); return kit; }
        @Override public boolean onHead(Body b) { calls.add("head"); return head; }
        @Override public float headMultiplier() { calls.add("headMultiplier"); return 1.75F; }
        @Override public float corrosion(Body b) { calls.add("corrosion"); return 0.2F; }
        @Override public boolean gas() { throw new AssertionError(); }
        @Override public int reach(Object s) { throw new AssertionError(); }
        @Override public float schemeDrain(Body b, Object s) { throw new AssertionError(); }
        @Override public float[] floorExtra(Object s, boolean blast) { throw new AssertionError(); }
        @Override public float ntmPierce(Body b, int which) { throw new AssertionError(); }
    }

    // Copied from 0d4b482; game reads alone are replaced by the fake's live getters.
    static class Legacy {
        final Reads reads;
        final Map<Object, String> PAIR = Collections.synchronizedMap(new WeakHashMap<>());
        final Map<Object, Float> SHARE = Collections.synchronizedMap(new WeakHashMap<>());
        record Last(String pair, long tick) {}
        final Map<Object, Last> LAST = Collections.synchronizedMap(new WeakHashMap<>());
        final Map<Object, Boolean> IR_SUBS = Collections.synchronizedMap(new WeakHashMap<>());
        Legacy(Reads r) { reads = r; r.legacy = this; }
    float hitAs(Object projectile, Body shooter, Object from, Object as,
            float share) {
        if (projectile == null || shooter == null || reads.client(shooter) || !reads.gunsOn()) return 1F;
        Object gunItem = reads.gunOf(shooter);
        if (gunItem == null) return 1F;
        try {
            String gun = reads.gunName(gunItem);
            String id = gun + "." + reads.ammoName(as);
            if (reads.enabled() && gun != null && state().pairs().containsKey(id)) {
                PAIR.put(projectile, id);
                SHARE.put(projectile, share);
            }
            return share * reads.as(gunItem, from, as);
        } catch (RuntimeException | LinkageError e) {
            // a hit must never fail over the tables: the shell's own damage
            return 1F;
        }
    }

    float shareOf(Object projectile, Body shooter, Object from,
            Object as, float share) {
        if (projectile == null || shooter == null || reads.client(shooter) || !reads.gunsOn()) return share;
        Object gunItem = reads.gunOf(shooter);
        if (gunItem == null) return share;
        // hitAs's own, but a table failing leaves the share (hitAs would give the whole damage: two bullets of a full SP)
        try {
            String gun = reads.gunName(gunItem);
            String id = gun + "." + reads.ammoName(as);
            if (reads.enabled() && gun != null && state().pairs().containsKey(id)) {
                PAIR.put(projectile, id);
                SHARE.put(projectile, share);
            }
            return share * reads.as(gunItem, from, as);
        } catch (RuntimeException | LinkageError e) {
            return share;
        }
    }

    float share(Object source) {
        Float k = SHARE.get(reads.direct(source));
        return k == null ? 1F : k;
    }

    void fired(Object projectile, Body shooter, Object gunItem, Object ammo) {
        if (projectile == null || shooter == null || reads.client(shooter) || !reads.enabled()) return;
        try {
            fired0(projectile, shooter, gunItem, ammo);
        } catch (RuntimeException | LinkageError e) {
            // the shot goes on without a pair: NTM's machine takes it
        }
    }

    void fired0(Object projectile, Body shooter, Object gunItem, Object ammo) {
        String gun = reads.gunName(gunItem);
        if (gun == null || ammo == null) return;
        String id = gun + "." + reads.ammoName(ammo);
        if (!state().pairs().containsKey(id)) {
            String loaded = reads.loadedAmmo(shooter, gunItem);
            if (loaded != null && state().pairs().containsKey(gun + "." + loaded)) id = gun + "." + loaded;
        }
        PAIR.put(projectile, id);
        LAST.put(reads.raw(shooter), new Last(id, reads.time(reads.raw(shooter))));
        reads.countShot(shooter);
        if (reads.logOn()) reads.logShot(projectile, shooter, gunItem);
    }

    void submunition(Object projectile, Body thrower) {
        if (projectile == null || thrower == null || reads.client(thrower) || !reads.enabled() || PAIR.containsKey(projectile)) return;
        Last l = LAST.get(reads.raw(thrower));
        if (l != null && reads.time(reads.raw(thrower)) - l.tick() <= 2) PAIR.put(projectile, l.pair());
    }

    void beamSub(Object beam, Body thrower, Object config) {
        if (beam == null || thrower == null || reads.client(thrower) || !reads.enabled()) return;
        try {
            if (!reads.irConfig(config)) return;
        } catch (RuntimeException | LinkageError e) {
            return;
        }
        Last l = LAST.get(reads.raw(thrower));
        if (l == null || reads.time(reads.raw(thrower)) - l.tick() > 2) return;
        PAIR.put(beam, l.pair());
        IR_SUBS.put(beam, Boolean.TRUE);
    }

    String blastPair(Object source) {
        Object shooter = reads.entity(source);
        Last l = shooter == null ? null : LAST.get(shooter);
        if (l == null || reads.time(shooter) - l.tick() > 5) return null;
        // a beam's pair alone: an NTM blast after a bullet's shot (a fire's, a launcher's) is not the pair's
        var pr = state().pairs().get(l.pair());
        return pr != null && !pr.cat().equals("PHYS") ? l.pair() : null;
    }

    boolean irSub(Object source) {
        return reads.direct(source) != null && IR_SUBS.containsKey(reads.direct(source));
    }

    String pairOf(Object projectile) {
        return projectile == null ? null : PAIR.get(projectile);
    }

    Hit hit(Body target, Object source) {
        if (reads.client(target) || !reads.enabled()) return null;
        String cat = reads.category(source);
        if (cat == null) return null;
        State s = state();
        Object projectile;
        if ("EXPL".equals(cat)) {
            // an explosive bullet's blast on the creature it struck: its direct hit (variant C); the rest of the blast as before
            if (!reads.bulletBlast() || !reads.struck(target)) return null;
            projectile = reads.bullet();
        } else projectile = reads.direct(source);
        String id = pairOf(projectile);
        // an NTM blast of a beam or a bullet of a pair (the Tesla Cannon's arc): the pair its shooter just fired
        if (id == null && reads.vnt() && !"EXPL".equals(cat)) id = blastPair(source);
        if (id == null) return null;
        var pr = s.pairs().get(id);
        if (pr == null) return null;
        var armor = BalanceFinalBase.armorOf(target, s, reads, reads::balanceOn, reads::worn);
        if (armor == null) return null;
        BalanceFinalRule.Armor fau = s.sets().get("fau");
        double click = s.cache().computeIfAbsent(new CacheKey(id, armor), k -> BalanceFinalRule.clickDamage(pr, armor, fau, s.refs(), s.params()));
        return new Hit(pr, armor, click);
    }

    float damage(Body target, Object source) {
        float b = reads.blastOrFrag(target, source);
        if (!Float.isNaN(b)) return b * target.maxHealth() / (float) BalanceFinalRule.HP;
        Hit h = hit(target, source);
        if (h == null) return Float.NaN;
        if (reads.again(target, source)) return 0F;
        if (reads.logOn()) reads.logHit(target, source, h.pair().id(), h.pair().tier(), reads.onHead(target));
        float scale = target.maxHealth() / (float) BalanceFinalRule.HP;
        return h.projectile() * scale * range(target, source, h) * share(source);
    }

    float range(Body target, Object source, Hit h) {
        var r = state().range();
        String role = BalanceFinalRule.roleOf(h.pair(), state().params());
        boolean own = r.lineOf().containsKey(h.pair().gun());
        // an explosive shell's direct hit takes the shell's line, or its role's (Fable №2, 2.1; №3); other blasts none
        boolean shell = !own && "EXPL".equals(reads.category(source)) && h.pair().explosive() && (role.equals("shotgun") || role.equals("slug"));
        // the IR arc's beam: no range, like the arc (Fable's answer 6, 5)
        // the shell's direct hit comes in NTM's blast (vntBlastNow): the shell's line all the same; so does a drop's blast of a gun's own line
        if (!r.on() || (reads.vnt() || "EXPL".equals(reads.category(source))) && !shell && !(own && "EXPL".equals(reads.category(source))) || irSub(source) || reads.entity(source) == null) return 1F;
        double d = reads.distance(target, source);
        // the gun's own line; the shell's (Fable №3), or its role's when the rule has none (X942)
        return (float) BalanceRangeRule.hit(r, h.pair().gun(), role, shell, d);
    }

    float drain(Body target, Object source) {
        float b = reads.blastOrFrag(target, source);
        if (!Float.isNaN(b)) return b;
        Hit h = hit(target, source);
        if (h == null) return Float.NaN;
        if (reads.again(target, source)) return 0F;
        boolean head = reads.onHead(target);
        if (reads.logOn()) reads.logHit(target, source, h.pair().id(), h.pair().tier(), head);
        float d = h.projectile() * range(target, source, h) * share(source);
        return head ? Math.min(d * reads.headMultiplier(), reads.headCap() * (float) BalanceFinalRule.HP) : d;
    }

    boolean ntmShot(Body target, Object source) {
        if (!reads.enabled()) return false;
        String cat = reads.category(source);
        if (cat == null || "EXPL".equals(cat)) return false;
        // a player's shot of no pair (a launcher's submunition, another game's gun) keeps X908's machine
        if (!reads.mobShooter(source)) return false;
        String id = pairOf(reads.direct(source));
        if (id == null && reads.vnt()) id = blastPair(source);
        if (id == null) return true;
        State s = state();
        // a GL-54 fragment grenade's (BalanceFinal.frag): the scheme's, like a pair's
        if (s.blast().on() && id.startsWith("gl54.") && s.blast().frags().containsKey(id.substring(5))) return false;
        return !s.pairs().containsKey(id);
    }

    }

    static final class Compare {
        final Reads old = new Reads(), now = new Reads();
        final Legacy ref = new Legacy(old);
        final Map<CacheKey, Double> oldCache = new HashMap<>(), newCache = new HashMap<>();
        Compare() { for (String name : MAPS) map(name).clear(); map("BLAST_HITS").clear(); state().cache().clear(); }
        void step(Supplier<Object> expected, Supplier<Object> actual) {
            old.calls.clear(); now.calls.clear();
            State s = state();
            Object a, b;
            try {
                setState(withCache(s, oldCache));
                a = expected.get();
                setState(withCache(s, newCache));
                b = actual.get();
            } finally { setState(s); }
            assertEquals(bits(a), bits(b), "result");
            assertEquals(oldCache.keySet(), newCache.keySet(), "cache keys");
            oldCache.forEach((k, v) -> assertEquals(bits(v), bits(newCache.get(k)), "cache bits"));
            assertEquals(old.calls, now.calls, "live calls in order");
            assertEquals(ref.PAIR, map("PAIR"), "PAIR");
            assertEquals(ref.SHARE, map("SHARE"), "SHARE");
            assertEquals(ref.IR_SUBS, map("IR_SUBS"), "IR_SUBS");
            Map<Object, String> last = new HashMap<>();
            ref.LAST.forEach((k, v) -> last.put(k, v.toString()));
            Map<Object, String> current = new HashMap<>();
            map("LAST").forEach((k, v) -> current.put(k, v.toString()));
            assertEquals(last, current, "LAST");
            Map<Object, List<Object>> hits = new HashMap<>();
            map("BLAST_HITS").forEach((k, v) -> {
                try {
                    var type = v.getClass();
                    hits.put(k, List.of(type.getDeclaredMethod("source").invoke(v), type.getDeclaredMethod("shooter").invoke(v), type.getDeclaredMethod("tick").invoke(v)));
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            });
            assertEquals(old.hits, hits, "BLAST_HITS state");
            assertEquals(old.count, now.count, "standShots callback state");
        }
        void fire(Object p, Body a, Body b) {
            step(() -> { ref.fired(p, a, GUN, AMMO); return null; }, () -> { BalanceFinalBase.fired(p, b, GUN, AMMO, now, now::enabled); return null; });
        }
        void fire() { fire(BULLET, old.shooter, now.shooter); }
        void hit(Source s) {
            Source source = Float.isNaN(now.blast) ? s : new Source(s.direct(), s.shooter(), "EXPL", true);
            try {
                if (!Float.isNaN(now.blast)) {
                    Class<?> type = Class.forName("acup.gunfeel.BalanceFinalBase$BlastNow");
                    var ctor = type.getDeclaredConstructor(Object.class, double.class, String.class, double.class, Object.class);
                    ctor.setAccessible(true);
                    var field = BalanceFinalBase.class.getDeclaredField("BLAST_NOW");
                    field.setAccessible(true);
                    ((ThreadLocal<Object>) field.get(null)).set(ctor.newInstance(TARGET, (double) now.blast, "test", 0.0, null));
                }
                step(() -> ref.hit(old.target, source), () -> BalanceFinalBase.hit(now.target, source, now, now::enabled, now));
                step(() -> ref.damage(old.target, source), () -> BalanceFinalBase.damage(now.target, source, now, now::enabled, now));
                step(() -> ref.drain(old.target, source), () -> BalanceFinalBase.drain(now.target, source, now, now::enabled, now));
                step(() -> ref.ntmShot(old.target, source), () -> BalanceFinalBase.ntmShot(now.target, source, now, now::enabled));
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            finally {
                try {
                    var field = BalanceFinalBase.class.getDeclaredField("BLAST_NOW");
                    field.setAccessible(true);
                    ((ThreadLocal<?>) field.get(null)).remove();
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            }
        }
    }
    static Object bits(Object x) {
        if (x instanceof Float f) return Float.floatToRawIntBits(f);
        if (x instanceof Double d) return Double.doubleToRawLongBits(d);
        if (x instanceof Hit h) return List.of(h.pair(), h.armor(), Double.doubleToRawLongBits(h.clickDamage()), Float.floatToRawIntBits(h.projectile()));
        return x;
    }

    static State withCache(State s, Map<CacheKey, Double> cache) {
        return new State(s.version(), s.params(), s.pairs(), s.refs(), s.sets(), s.weights(), s.empty(), cache, s.blast(), s.range(), s.acid());
    }
    static void setState(State s) {
        try {
            var field = BalanceFinalBase.class.getDeclaredField("state");
            field.setAccessible(true); field.set(null, s);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    @Test void guardsAndTableFailures() {
        for (int mode = 0; mode < 13; mode++) for (boolean linkage : new boolean[] {false, true}) {
            Compare c = new Compare();
            for (Reads r : List.of(c.old, c.now)) {
                r.client = mode == 1; r.enabled = mode != 2; r.guns = mode != 3;
                r.noGun = mode == 4; r.gun = mode == 5 ? null : "stg77";
                r.badNames = mode == 6; r.badRatio = mode == 7; r.linkage = linkage;
                if (mode == 8 || mode == 9) { r.ammo = "unknown"; r.loaded = mode == 8 ? "r556_ap" : "missing"; }
            }
            Object p = mode == 10 ? null : BULLET;
            Body a = mode == 11 ? null : c.old.shooter, b = mode == 11 ? null : c.now.shooter;
            Object ammo = mode == 12 ? null : AMMO;
            c.step(() -> c.ref.hitAs(p, a, AMMO, AMMO, 0.2F), () -> BalanceFinalBase.hitAs(p, b, AMMO, AMMO, 0.2F, c.now, c.now::enabled));
            c.step(() -> c.ref.shareOf(p, a, AMMO, AMMO, 0.2F), () -> BalanceFinalBase.shareOf(p, b, AMMO, AMMO, 0.2F, c.now, c.now::enabled));
            c.step(() -> { c.ref.fired(p, a, GUN, ammo); return null; }, () -> { BalanceFinalBase.fired(p, b, GUN, ammo, c.now, c.now::enabled); return null; });
            c.hit(new Source(p, SHOOTER, mode == 12 ? null : "PHYS", false));
        }
    }

    @Test void ordinaryHitsRangeHeadLogAndHealth() {
        for (String[] pair : new String[][] {{"stg77", "r556_ap"}, {"spas12", "g12"}, {"autoshotgun_shredder", "g12_explosive"}, {"tesla_cannon", "capacitor_ir"}, {"spas12", "g12_explosive"}})
            for (boolean log : new boolean[] {false, true}) for (boolean head : new boolean[] {false, true})
                for (float hp : new float[] {1, 20, 40, 1024}) for (int mode = 0; mode < 7; mode++) {
                    Compare c = new Compare();
                    for (Reads r : List.of(c.old, c.now)) {
                        r.gun = pair[0]; r.ammo = pair[1]; r.head = head; r.logging = log; r.health = hp;
                        r.vnt = mode == 1; r.distance = mode == 2 ? 0 : mode == 3 ? 200 : 40;
                        r.kit = mode == 4 ? null : "hev"; r.player = mode != 4;
                        r.blast = mode == 5 ? 0.625F : Float.NaN;
                    }
                    assertTrue(state().pairs().containsKey(pair[0] + "." + pair[1]));
                    c.fire();
                    Source s = new Source(BULLET, mode == 6 ? null : SHOOTER, "PHYS", false);
                    c.step(() -> c.ref.shareOf(BULLET, c.old.shooter, AMMO, AMMO, 0.25F), () -> BalanceFinalBase.shareOf(BULLET, c.now.shooter, AMMO, AMMO, 0.25F, c.now, c.now::enabled));
                    c.hit(s);
                }
    }

    @Test void agesIrAndRawKeys() {
        String[] beamPair = state().pairs().values().stream().filter(p -> !p.cat().equals("PHYS")).findFirst().map(p -> new String[] {p.gun(), p.ammo()}).orElseThrow();
        for (int age : new int[] {0, 2, 3, 5, 6}) for (int mode = 0; mode < 4; mode++) {
            Compare c = new Compare();
            for (Reads r : List.of(c.old, c.now)) { r.gun = beamPair[0]; r.ammo = beamPair[1]; r.irFail = mode == 2; r.linkage = mode == 3; }
            c.fire();
            // Two wrappers of one shooter must address the same LAST key.
            c.fire(SAME_ID, c.old.body(SHOOTER), c.now.body(SHOOTER));
            c.old.tick += age; c.now.tick += age;
            c.step(() -> { c.ref.submunition(SAME_ID, c.old.shooter); return null; }, () -> { BalanceFinalBase.submunition(SAME_ID, c.now.shooter, c.now, c.now::enabled); return null; });
            Object sub = new Entity(10, "sub");
            c.step(() -> { c.ref.submunition(sub, c.old.shooter); return null; }, () -> { BalanceFinalBase.submunition(sub, c.now.shooter, c.now, c.now::enabled); return null; });
            Object beam = new Entity(11, "beam");
            Object config = mode == 1 ? AMMO : IR;
            c.step(() -> { c.ref.beamSub(beam, c.old.shooter, config); return null; }, () -> { BalanceFinalBase.beamSub(beam, c.now.shooter, config, c.now, c.now::enabled); return null; });
            Source s = new Source(beam, SHOOTER, "EN", false), repeated = new Source(beam, SHOOTER, "EN", false);
            c.hit(s); c.hit(repeated);
            c.step(() -> c.ref.blastPair(s), () -> BalanceFinalBase.blastPair(s, c.now));
            c.old.vnt = c.now.vnt = true;
            c.hit(new Source(null, SHOOTER, "EN", false));
            c.step(() -> c.ref.pairOf(new Entity(9, "untracked")), () -> BalanceFinalBase.pairOf(new Entity(9, "untracked")));
        }
    }

    @Test void explosionDelegationAndNtmFallbacks() {
        for (int mode = 0; mode < 5; mode++) {
            Compare c = new Compare(); c.fire();
            c.old.explosionHit = c.now.explosionHit = mode != 1;
            c.old.mob = c.now.mob = mode != 2;
            c.hit(new Source(BULLET, SHOOTER, "EXPL", true));
            c.hit(new Source(null, mode == 3 ? null : SHOOTER, mode == 4 ? null : "PHYS", false));
        }
    }

    @Test void rangeOffAndOwnLineBlasts() {
        State s = state();
        try {
            for (boolean rangeOn : new boolean[] {false, true}) {
                var range = rangeOn ? s.range() : new BalanceRangeRule.Rule(Map.of(), s.range().close(), s.range().home(), s.range().lineOf());
                setState(new State(s.version(), s.params(), s.pairs(), s.refs(), s.sets(), s.weights(), s.empty(), new HashMap<>(), s.blast(), range, s.acid()));
                for (String[] pair : new String[][] {{"autoshotgun_shredder", "g12_explosive"}, {"spas12", "g12_explosive"}}) {
                    Compare c = new Compare();
                    for (Reads r : List.of(c.old, c.now)) { r.gun = pair[0]; r.ammo = pair[1]; r.vnt = true; }
                    c.fire();
                    c.hit(new Source(BULLET, SHOOTER, "EXPL", true));
                }
            }
        } finally { setState(s); }
    }

    @Test void failingPairTablesKeepFallbackAndState() {
        State s = state();
        try {
            for (boolean linkage : new boolean[] {false, true}) {
                Map<String, BalanceFinalRule.Pair> bad = new AbstractMap<>() {
                    @Override public boolean containsKey(Object k) {
                        if (linkage) throw new NoClassDefFoundError("pairs");
                        throw new IllegalStateException("pairs");
                    }
                    @Override public Set<Entry<String, BalanceFinalRule.Pair>> entrySet() { return s.pairs().entrySet(); }
                };
                setState(new State(s.version(), s.params(), bad, s.refs(), s.sets(), s.weights(), s.empty(), new HashMap<>(), s.blast(), s.range(), s.acid()));
                Compare c = new Compare();
                c.step(() -> c.ref.hitAs(BULLET, c.old.shooter, AMMO, AMMO, 0.25F), () -> BalanceFinalBase.hitAs(BULLET, c.now.shooter, AMMO, AMMO, 0.25F, c.now, c.now::enabled));
                c.step(() -> c.ref.shareOf(BULLET, c.old.shooter, AMMO, AMMO, 0.25F), () -> BalanceFinalBase.shareOf(BULLET, c.now.shooter, AMMO, AMMO, 0.25F, c.now, c.now::enabled));
                c.fire();
            }
        } finally { setState(s); }
    }

    @Test void missingPortsThrow() {
        assertThrows(UnsupportedOperationException.class, HurtNow.MISSING::headMultiplier);
        assertThrows(UnsupportedOperationException.class, () -> FinalShots.MISSING.direct(null));
    }
}
