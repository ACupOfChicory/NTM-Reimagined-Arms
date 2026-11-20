package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import acup.gunfeel.BalanceFinalBase.Hit;
import acup.gunfeel.BalanceFinalBase.State;
import acup.gunfeel.BalanceFinalRule.Armor;
import acup.gunfeel.BalanceFinalRule.Pair;
import acup.gunfeel.host.Body;
import acup.gunfeel.host.HurtNow;

/** The old calculations against core: raw bits and every live read in order. */
class BalanceFinalStep1Test {
    private static final boolean[] BOOL = {false, true};
    private static final float[] CORROSION = {Float.NaN, -1F, -0F, 0F, 0.001F, 0.01875F, 0.75F, 1F, Float.MAX_VALUE};
    private static final float[] HEALTH = {1F, 20F, 40F, 1024F};
    private static final String[][] KITS = {
        {null, null, null, null},
        {"steel", "hev", "dns", "fau"},
        {"steel", null, "steel", null},
        {"dns", "dns", null, "dns"},
        {"unknown", "unknown", "unknown", "unknown"},
        {"steel", "steel", "steel", "steel"},
        {"hev", "hev", "hev", "hev"},
        {"dns", "dns", "dns", "dns"},
        {"fau", "fau", "fau", "fau"},
        {"euphemium", "euphemium", "euphemium", "euphemium"},
        {"trenchmaster", "trenchmaster", "trenchmaster", "trenchmaster"}
    };

    private static final class Reads implements HurtNow {
        @Override public float headMultiplier() { throw new UnsupportedOperationException(); }
        final List<String> calls = new ArrayList<>();
        final Body body;
        final BiFunction<Body, Integer, String> worn;
        final BooleanSupplier on;
        final float corrosion;

        Reads(boolean player, String[] kit, float corrosion, float health, boolean balance) {
            this.corrosion = corrosion;
            body = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> {
                return switch (m.getName()) {
                    case "player" -> { calls.add("player"); yield player; }
                    case "maxHealth" -> { calls.add("maxHealth"); yield health; }
                    case "worn", "held" -> { calls.add(m.getName() + " " + a[0]); yield (int) a[0]; }
                    default -> throw new UnsupportedOperationException(m.getName());
                };
            });
            worn = (b, slot) -> {
                assertSame(body, b);
                return kit[b.worn(slot)];
            };
            on = () -> { calls.add("balanceOn"); return balance; };
        }

        State state(State s, BalanceFinalRule.Acid acid) {
            Map<String, Pair> pairs = new AbstractMap<>() {
                @Override public Pair get(Object id) { calls.add("pairs.get " + id); return s.pairs().get(id); }
                @Override public Set<Entry<String, Pair>> entrySet() { return s.pairs().entrySet(); }
            };
            return new State(s.version(), s.params(), pairs, s.refs(), s.sets(), s.weights(), s.empty(),
                    s.cache(), s.blast(), s.range(), acid);
        }

        @Override public float corrosion(Body b) {
            assertSame(body, b);
            calls.add("corrosion");
            return corrosion;
        }
        @Override public boolean gas() { throw new UnsupportedOperationException(); }
        @Override public int reach(Object hurt) { throw new UnsupportedOperationException(); }
        @Override public float schemeDrain(Body b, Object hurt) { throw new UnsupportedOperationException(); }
        @Override public boolean onHead(Body b) { throw new UnsupportedOperationException(); }
        @Override public float[] floorExtra(Object hurt, boolean blast) { throw new UnsupportedOperationException(); }
        @Override public float ntmPierce(Body b, int which) { throw new UnsupportedOperationException(); }
    }

    // The old armorOf0, armorOf, clickOn, describe and Hit, with the game's reads made the fakes'.
    private static Armor oldWorn(Reads r, State s) {
        Armor[] slots = new Armor[4];
        String first = null;
        boolean same = true, any = false;
        for (int i = 0; i < 4; i++) {
            String set = r.worn.apply(r.body, i);
            var a = set == null ? null : s.sets().get(set);
            slots[i] = a;
            if (a != null) any = true;
            if (i == 0) first = set;
            if (a == null || set == null || !set.equals(first)) same = false;
        }
        if (!any) return r.body.player() ? BalanceFinalRule.MODEL_SETS.get("none") : null;
        if (same) return slots[0].pool() > 0 && !oldPooled(r, first) ? new Armor(slots[0].kind(), slots[0].tier(), slots[0].hours(), slots[0].en(), 0) : slots[0];
        return BalanceFinalRule.mix(slots, s.weights(), s.empty());
    }

    private static boolean oldPooled(Reads r, String set) {
        if (!r.body.player()) return false;
        for (var kit : EuphImmunityBase.Kit.values())
            if (kit.set.equals(set)) return r.on.getAsBoolean() && kit.mode().equals("pool");
        return false;
    }

    private static Armor oldArmor(Reads r, State s) {
        var a = oldWorn(r, s);
        if (a == null || !s.acid().on()) return a;
        float k = r.corrosion(r.body);
        if (!(k > 0F)) return a;
        double t = s.acid().tierOf(a.tier(), k);
        return t == a.tier() ? a : new Armor(a.kind(), t, a.hours(), a.en(), a.pool());
    }

    private static float oldClick(Reads r, String id, State s) {
        var pr = s.pairs().get(id);
        var armor = pr == null ? null : oldArmor(r, s);
        if (armor == null) return Float.NaN;
        double click = BalanceFinalRule.clickDamage(pr, armor, s.sets().get("fau"), s.refs(), s.params());
        return (float) (click / pr.projectiles()) * r.body.maxHealth() / (float) BalanceFinalRule.HP;
    }

    private record OldHit(Pair pair, Armor armor, double clickDamage) {
        float projectile() { return (float) (clickDamage / pair.projectiles()); }
    }

    private static String oldDescribe(OldHit h) {
        return h == null ? null : String.format(Locale.ROOT, "%s.%s on %s, %.3f a click", h.pair().gun(), h.pair().ammo(), h.armor().kind(), h.clickDamage());
    }

    private static void sameArmor(Armor expected, Armor actual, String label) {
        if (expected == null) {
            assertNull(actual, label);
            return;
        }
        assertNotNull(actual, label);
        assertEquals(expected.kind(), actual.kind(), label);
        sameDouble(expected.tier(), actual.tier(), label + " tier");
        sameDouble(expected.hours(), actual.hours(), label + " hours");
        sameDouble(expected.en(), actual.en(), label + " en");
        sameDouble(expected.pool(), actual.pool(), label + " pool");
    }

    private static void sameDouble(double expected, double actual, String label) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), label);
    }

    @Test
    void armorMatchesOldBitsAndReads() {
        State base = BalanceFinalBase.state();
        for (boolean player : BOOL) for (String[] kit : KITS) for (boolean acid : BOOL)
            for (float k : CORROSION) for (boolean balance : BOOL) {
                Reads old = new Reads(player, kit, k, 20F, balance), now = new Reads(player, kit, k, 20F, balance);
                var rule = acid ? BalanceFinalRule.Acid.DEFAULT : BalanceFinalRule.Acid.OFF;
                String label = player + " " + java.util.Arrays.toString(kit) + " " + acid + " " + k + " " + balance;
                sameArmor(oldArmor(old, old.state(base, rule)),
                        BalanceFinalBase.armorOf(now.body, now.state(base, rule), now, now.on, now.worn), label);
                assertEquals(old.calls, now.calls, label + " armor reads");
            }
    }

    @Test
    void clickMatchesOldBitsAndReads() {
        State base = BalanceFinalBase.state();
        String[] ids = {"stg77.r556_ap", "spas12.g12", "missing"};
        assertTrue(base.pairs().containsKey(ids[0]));
        assertTrue(base.pairs().containsKey(ids[1]));
        assertFalse(base.pairs().containsKey(ids[2]));
        // Include every pair too, so fractional pellet counts and burst/akimbo counts keep their rounding.
        List<String> all = new ArrayList<>(base.pairs().keySet());
        for (String id : ids) if (!all.contains(id)) all.add(id);
        for (boolean player : BOOL) for (String[] kit : KITS) for (boolean acid : BOOL)
            for (float k : CORROSION) for (float health : HEALTH) for (boolean balance : BOOL)
                for (String id : ids) checkClick(base, player, kit, acid, k, health, balance, id);
        for (String id : all) for (float health : HEALTH)
            checkClick(base, true, KITS[6], true, 0.75F, health, true, id);
    }

    private static void checkClick(State base, boolean player, String[] kit, boolean acid, float k,
            float health, boolean balance, String id) {
        Reads old = new Reads(player, kit, k, health, balance), now = new Reads(player, kit, k, health, balance);
        var rule = acid ? BalanceFinalRule.Acid.DEFAULT : BalanceFinalRule.Acid.OFF;
        String label = id + " " + player + " " + java.util.Arrays.toString(kit) + " " + acid + " " + k + " " + health + " " + balance;
        float expected = oldClick(old, id, old.state(base, rule));
        float actual = BalanceFinalBase.clickOn(now.body, id, now.state(base, rule), now, now.on, now.worn);
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual), label + " click bits");
        assertEquals(old.calls, now.calls, label + " click reads");
    }

    @Test
    void describeAndProjectileMatchOld() {
        assertEquals(oldDescribe(null), BalanceFinalBase.describeOf(null));
        for (Pair pair : BalanceFinalBase.state().pairs().values())
            for (Armor armor : BalanceFinalBase.DEFAULT_SETS.values())
                for (double click : new double[] {0, -0.0, 1.23456789, 20.0 / 3, 1.00000006, Double.NaN,
                        Double.POSITIVE_INFINITY, Double.MAX_VALUE}) {
                    OldHit old = new OldHit(pair, armor, click);
                    Hit now = new Hit(pair, armor, click);
                    assertEquals(oldDescribe(old), BalanceFinalBase.describeOf(now), pair.id() + " describe");
                    sameDouble(old.clickDamage(), now.clickDamage(), pair.id() + " clickDamage");
                    assertEquals(Float.floatToRawIntBits(old.projectile()), Float.floatToRawIntBits((float) now.projectile()), pair.id() + " projectile float bits");
                    sameDouble(old.projectile(), now.projectile(), pair.id() + " projectile bits");
                    sameDouble(old.projectile() * 1.75F, now.projectile() * 1.75F, pair.id() + " scaled projectile bits");
                }
    }

    @Test
    void zeroCorrosionKeepsTheArmorEvenWithNanTier() {
        State base = BalanceFinalBase.state();
        Armor armor = new Armor("sentinel", Double.NaN, -0.0, 0, 0);
        State s = new State(base.version(), base.params(), base.pairs(), base.refs(), Map.of("steel", armor),
                base.weights(), base.empty(), base.cache(), base.blast(), base.range(), BalanceFinalRule.Acid.DEFAULT);
        for (float k : new float[] {0F, -0F}) {
            Reads old = new Reads(true, KITS[5], k, 20F, true), now = new Reads(true, KITS[5], k, 20F, true);
            Armor expected = oldArmor(old, s), actual = BalanceFinalBase.armorOf(now.body, s, now, now.on, now.worn);
            sameArmor(expected, actual, "zero corrosion");
            assertSame(expected, actual, "zero corrosion must return the worn armor itself");
            assertEquals(old.calls, now.calls, "zero corrosion reads");
        }
    }

    @Test
    void missingCorrosionThrows() {
        assertThrows(UnsupportedOperationException.class, () -> HurtNow.MISSING.corrosion(null));
    }
}
