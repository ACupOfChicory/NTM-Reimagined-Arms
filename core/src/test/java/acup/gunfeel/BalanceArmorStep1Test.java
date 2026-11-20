package acup.gunfeel;

import static acup.gunfeel.BalanceArmorBase.*;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.CapNow;
import acup.gunfeel.host.FinalShots;
import acup.gunfeel.host.HurtNow;
import acup.gunfeel.host.Hurts;
import acup.gunfeel.host.ItemTable;

/** 1dd1056's decisions on fake live reads against core: raw bits, state and ordered calls. */
class BalanceArmorStep1Test {
    private static final Object A = new Object(), B = new Object(), DIRECT = new Object(), BULLET = new Object();
    private static final String[][] KITS = {
        {null, null, null, null}, {"steel", "hev", "dns", "trenchmaster"},
        {"dnt", null, "hev", null}, {"unknown", "dnt", "dnt", "dnt"},
        {"bismuth", "bismuth", "bismuth", "bismuth"}, {"bj", "bj", "bj", "bj"}
    };
    private record Source(int yes, int kind, String cat, String name, Object direct) {}
    private static final Source PHYS = new Source(0, 0, "PHYS", "hbm:physical", DIRECT);
    private static final Source EN = new Source(0, Hurts.NTM_BEAM, "EN", "hbm:laser", DIRECT);
    private static final Source EXPL = new Source(0, 0, "EXPL", "hbm:explosion", DIRECT);
    private static final Source IR = new Source(1 << Hurts.NTM_FIRE, Hurts.NTM_BEAM, "FIRE", "hbm:fire", DIRECT);
    private static final Source[] SOURCES = {PHYS, EN, EXPL, IR,
        new Source(0, 0, "FIRE", "hbm:fire", DIRECT),
        new Source(0, Hurts.LIVING, "PHYS", "minecraft:player_attack", A),
        new Source(0, 0, "SPIKES", null, null), new Source(1, 0, "PHYS", "hbm:physical", DIRECT),
        new Source(1 << Hurts.NTM_NUCLEAR_BLAST, 0, "EXPL", "hbm:nuclear_blast", DIRECT),
        new Source(1 << Hurts.NTM_DIGAMMA, 0, "EN", "hbm:digamma", DIRECT),
        new Source(1 << Hurts.NTM_RADIATION, 0, "PHYS", "hbm:radiation", DIRECT),
        new Source(0, 0, null, null, null)};

    @SuppressWarnings("unchecked")
    private static <T> T fake(Class<T> type, java.lang.reflect.InvocationHandler calls) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, calls);
    }

    private static final class Reads {
        final List<String> calls = new ArrayList<>();
        final Map<Body, Object> raw = new IdentityHashMap<>();
        final Body a, b;
        final Hurts hurts;
        final FinalShots shots;
        final HurtNow now;
        final CapNow cap;
        final BiFunction<Body, Integer, String> worn;
        final BooleanSupplier enabled;
        boolean client, scheme, mob;
        float corrosion;
        float floor = 0.27F;
        float[] under;
        Object bullet;
        final boolean old;

        Reads(boolean old, String[] kit) {
            this.old = old;
            List<String> names = new ArrayList<>(List.of("minecraft:air"));
            int[] items = new int[4];
            for (int i = 0; i < 4; i++) {
                if (kit[i] == null) continue;
                items[i] = names.size();
                names.add("hbm:" + kit[i] + (kit[i].equals("bj") && i == 1 ? "_plate_jetpack" : SUFFIX[i]));
            }
            a = body(A, items);
            b = body(B, items);
            ItemTable table = n -> { calls.add("item " + n); return names.get(n); };
            SetsOf sets = new SetsOf(table);
            worn = (target, slot) -> sets.of(target.worn(slot), slot);
            enabled = () -> { calls.add("schemeOn"); return scheme; };
            hurts = fake(Hurts.class, (p, m, args) -> {
                Source s = (Source) args[0];
                calls.add("hurts." + m.getName() + (args.length > 1 ? " " + args[1] : ""));
                return switch (m.getName()) {
                    case "is" -> (s.yes & 1 << (int) args[1]) != 0;
                    case "directKind" -> s.kind;
                    case "ntmCategory" -> s.cat;
                    case "typeName" -> s.name;
                    default -> throw new UnsupportedOperationException(m.getName());
                };
            });
            cap = fake(CapNow.class, (p, m, args) -> {
                calls.add("cap." + m.getName() + " " + label(raw.get(args[0])));
                return switch (m.getName()) {
                    case "client" -> client;
                    case "entity" -> raw.get(args[0]);
                    default -> throw new UnsupportedOperationException(m.getName());
                };
            });
            now = fake(HurtNow.class, (p, m, args) -> {
                calls.add("now." + m.getName() + (m.getName().equals("corrosion") ? "" : " " + label(args[0]) + " " + args[1]));
                return switch (m.getName()) {
                    case "corrosion" -> corrosion;
                    case "floorOf" -> floor;
                    case "underOf" -> under;
                    default -> throw new UnsupportedOperationException(m.getName());
                };
            });
            shots = fake(FinalShots.class, (p, m, args) -> {
                calls.add("shots." + m.getName());
                return switch (m.getName()) {
                    case "client" -> client;
                    case "category" -> old ? oldCategory(this, args[0]) : categoryOf(hurts, args[0]);
                    case "direct" -> ((Source) args[0]).direct;
                    case "bulletNow" -> bullet;
                    case "raw" -> raw.get(args[0]);
                    case "struckNow" -> A;
                    case "entity" -> null;
                    case "mobShooter" -> mob;
                    case "bulletBlast" -> bulletBlastNow();
                    case "vnt", "logOn", "explosion" -> false;
                    case "balanceOn" -> true;
                    case "worn" -> worn.apply((Body) args[0], (int) args[1]);
                    default -> throw new UnsupportedOperationException(m.getName());
                };
            });
        }

        private Body body(Object entity, int[] items) {
            Body b = fake(Body.class, (p, m, args) -> {
                calls.add("body." + label(entity) + "." + m.getName() + (args == null ? "" : " " + args[0]));
                return switch (m.getName()) {
                    case "worn" -> items[(int) args[0]];
                    case "player" -> true;
                    case "maxHealth" -> 40F;
                    default -> throw new UnsupportedOperationException(m.getName());
                };
            });
            raw.put(b, entity);
            return b;
        }

        Object raw(Body b) { return cap.entity(b); }
    }

    private static String label(Object o) {
        return o == A ? "a" : o == B ? "b" : o == DIRECT ? "direct" : o == BULLET ? "bullet" : o == null ? "null" : "other";
    }

    private static boolean oldIR(Reads r, Object s) {
        return (r.hurts.directKind(s) & Hurts.NTM_BEAM) != 0 && r.hurts.is(s, Hurts.NTM_FIRE);
    }

    private static boolean oldShot(Reads r, Object s) {
        String name = r.hurts.typeName(s);
        return name != null && BalanceArmorRule.shotName(name, (r.hurts.directKind(s) & Hurts.LIVING) != 0);
    }

    private static String oldCategory(Reads r, Object s) {
        String cat = oldAny(r, s);
        return cat == null || "EXPL".equals(cat) || oldShot(r, s) || oldIR(r, s) ? cat : null;
    }

    private static String oldAny(Reads r, Object s) {
        if (r.hurts.is(s, Hurts.BYPASSES_EFFECTS)) return null;
        if (r.hurts.is(s, Hurts.NTM_NUCLEAR_BLAST) || r.hurts.is(s, Hurts.NTM_DIGAMMA) || r.hurts.is(s, Hurts.NTM_RADIATION)) return null;
        String cat = r.hurts.ntmCategory(s);
        if ("FIRE".equals(cat) && oldIR(r, s)) return "EN";
        if ("EXPL".equals(cat)) return ReaimTimes.flag("balanceArmorBlasts", true) ? cat : null;
        return "EN".equals(cat) || "PHYS".equals(cat) ? cat : null;
    }

    private static final class Old {
        final Reads r;
        Old(Reads r) { this.r = r; }
        String category(Object s) { return oldCategory(r, s); }
        boolean rated(Body b) {
            for (int i = 0; i < 4; i++) {
                String set = r.worn.apply(b, i);
                if (set != null && (rofl(set) || rating(set) > 0F)) return true;
            }
            return false;
        }
        /**
         * The hit is the balance's: NTM's early check ({@code allowsAttack}: its threshold or resistance would leave nothing,
         * the hit is called off before it is dealt) must not stop it, or no hit would ever get the formula's least share.
         */
        boolean covers(Body target, Object source) {
            if (!on() || category(source) == null) return false;
            // the final scheme: its hits get through, a shot it leaves to NTM meets NTM's own check
            if (BalanceFinalBase.takes(target, source, r.shots, r.enabled, r.now)) return true;
            if (BalanceFinalBase.ntmShot(null, source, r.shots, r.enabled)) return false;
            return rated(target);
        }

        /**
         * NTM's {@code calculateDamage} has left {@code ntm} of {@code amount}; what the balance leaves instead (or
         * {@code ntm} itself).
         */
        float apply(Body target, Object source, float amount, float neg, float pen, float ntm) {
            float out = apply0(target, source, amount, neg, pen, ntm);
            Seen seen = WATCHED.isEmpty() ? null : WATCHED.get(r.raw(target));
            if (seen != null) {
                seen.hits++;
                seen.in += amount;
                seen.ntm += ntm;
                seen.out += out;
            }
            return out;
        }

        /** the stand: the hits on a watched creature NTM's calculateDamage saw, their damage before, NTM's and ours */
        static final class Seen {
            int hits;
            float in, ntm, out;
        }

        /** the creatures the stand watches, each with its own count (the balance stand's lanes: one each) */
        private final java.util.Map<Object, Seen> WATCHED = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

        /** the stand: {@code target}'s count from naught, {@code previous} (the lane's last victim) watched no more */
        Seen watch(Object previous, Object target) {
            if (previous != null) WATCHED.remove(previous);
            Seen seen = new Seen();
            WATCHED.put(target, seen);
            return seen;
        }

        /** the hit's damage NTM's calculateDamage took last, and on whom (after NTM's shield: what the formula sees) */
        private Object amountOf;
        private float amount;

        /**
         * the damage this hurt on {@code target} came to NTM's armour with (after NTM's shield and armour mods), read once;
         * {@code fallback} when NTM's armour did not see it
         */
        float takeAmount(Object target, float fallback) {
            float out = amountOf == target ? amount : fallback;
            amountOf = null;
            return out;
        }

        /**
         * an explosive bullet's blast is the formula's and the cap's at any raw damage. Its blast is the bullet's whole
         * damage (NTM's EntityProcessorCrossSmooth): up to X865 a heavy gun's HE round came past balanceExplRawLimit and
         * killed any set from full health with one click.
         */
        /**
         * the shot whose pair's floor a hit takes: the bullet itself, or for an explosive bullet's blast
         * the bullet whose impact set it off (the blast's source has the shooter, not the bullet)
         */
        Object floorShot(Object source, boolean blast) {
            var bullet = blast ? r.shots.bulletNow() : null;
            return bullet != null ? bullet : r.shots.direct(source);
        }

        private float apply0(Body target, Object source, float amount, float neg, float pen, float ntm) {
            if (!r.cap.client(target)) {
                amountOf = r.raw(target);
                this.amount = amount;
            }
            if (!on() || !(amount > 0F)) return ntm;
            String cat = category(source);
            if (cat == null) return ntm;
            // the final scheme (balance.final): its pairs' bullets and beams, an explosive bullet's blast on the creature it
            // struck; a shot of no pair of the table is NTM's
            float scheme = BalanceFinalBase.damage(target, source, r.shots, r.enabled, r.now);
            if (!Float.isNaN(scheme)) return scheme;
            if (BalanceFinalBase.ntmShot(null, source, r.shots, r.enabled)) return ntm;
            boolean en = "EN".equals(cat), expl = "EXPL".equals(cat);
            float[] byPiece = new float[4];
            boolean any = false, rofl = false;
            float k = r.now.corrosion(target);
            float knee = positive("balanceFleshKnee", BalanceArmorRule.Tune.DEFAULT.knee()), most = positive("balanceFleshMax", BalanceArmorRule.Tune.DEFAULT.most());
            // a most not above the knee: the defaults
            var tune = most > knee ? new BalanceArmorRule.Tune(knee, most) : BalanceArmorRule.Tune.DEFAULT;
            // a blast of more than this raw damage (a mine, C4, a block of TNT: building, not a weapon) is not squeezed
            boolean squeeze = !expl || amount <= explRawLimit() || bulletBlastNow();
            // a weapon's blast (a grenade, a 40 mm shell, a rocket) hits the set stronger (balanceBlastPower): its damage
            // times the power into the formula, the limit above and the cap by its own; never more than with no armour
            float power = expl && squeeze ? BLAST_POWER.get() : 1F;
            var hit = new BalanceArmorRule.Hit(amount * power, neg, pen, squeeze);
            float enShare = Math.min(1F, positive("balanceEnShare", 0.75F));
            for (int i = 0; i < 4; i++) {
                byPiece[i] = Float.NaN;
                String set = r.worn.apply(target, i);
                if (set == null) continue;
                if (rofl(set)) rofl = true;
                float s = rating(set);
                if (!(s > 0F)) continue;
                if (en) s *= enShare;
                if (k > 0F) s = acup.gunfeel.gas.GasModel.corrode(s, k);
                if (!(s > 0F)) s = 1e-3F;
                int tier = SETS.get(set).tier();
                // the weapon's blast's power by the set's tier (balanceBlastPowerLow, balanceBlastPowerRocketHigh,
                // balanceBlastLowFloor; Trenchmaster by balanceBlastTrenchTier)
                int bt = blastTier(set, tier);
                float pk = power > 1F ? pieceBlastPower(bt, amount, power) : 1F;
                float into = power > 1F ? blastInto(bt, amount, pk, s) : amount * power;
                // the floor of balanceBlastLowFloor lifts only the share that passes (rho), the flesh is the blast's own damage
                // times its power: with the floor's flesh too a set of a greater rating took more of a weak blast (X875)
                boolean share = power > 1F && into > amount * pk;
                float fleshOf = share ? amount * pk : into;
                var pieceHit = fleshOf == amount * power ? hit : new BalanceArmorRule.Hit(fleshOf, neg, pen, squeeze);
                // the gun table's floor of the shot's pair: its bullet's hit, an explosive bullet's blast
                float floor = !expl || bulletBlastNow() ? r.now.floorOf(floorShot(source, expl), tier) : 1F;
                // the pair's numbers below a tier (under=): a bullet's or a beam's hit, not a blast
                float[] u = expl ? null : r.now.underOf(r.shots.direct(source), tier);
                float top = amount, pieceInto = into;
                if (u != null) {
                    pieceHit = new BalanceArmorRule.Hit(pieceHit.damage() * u[0], Math.max(0F, neg + u[1]), Math.max(0F, Math.min(1F, pen + u[2])), squeeze);
                    top = amount * u[0];
                    pieceInto = into * u[0];
                }
                byPiece[i] = Math.min(top, BalanceArmorRule.full(pieceHit, s, tau(set), tune, floor, pieceInto));
                any = true;
            }
            float rest = ntm;
            if (rofl) rest = Math.max(rest, Math.min(1F, positive("balanceRoflFloor", 0.18F)) * amount);
            return any ? BalanceArmorRule.blend(byPiece, rest) : rest;
        }
    }

    private static Object field(String name) throws Exception {
        Field f = BalanceArmorBase.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, BalanceArmorBase.Seen> watched() throws Exception {
        return (Map<Object, BalanceArmorBase.Seen>) field("WATCHED");
    }

    @BeforeEach
    @AfterEach
    void clear() throws Exception {
        watched().clear();
        takeAmount(A, 0F);
        ServerSettings.useRemote(null);
    }

    private static void bits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }

    private static final class SeenView extends BalanceArmorBase.Seen {}

    private static final class Run {
        final Reads before, after;
        final Old old;

        Run(String[] kit) throws Exception {
            before = new Reads(true, kit);
            after = new Reads(false, kit);
            old = new Old(before);
            old.amount = (float) field("amount");
        }

        void same() throws Exception {
            assertEquals(before.calls, after.calls, "ordered port reads");
            before.calls.clear();
            after.calls.clear();
            assertSame(old.amountOf, field("amountOf"), "raw amount key");
            bits(old.amount, (float) field("amount"));
            assertEquals(old.WATCHED.keySet(), watched().keySet(), "raw watch keys");
            for (Object key : old.WATCHED.keySet()) {
                Old.Seen a = old.WATCHED.get(key);
                BalanceArmorBase.Seen b = watched().get(key);
                assertEquals(a.hits, b.hits);
                bits(a.in, b.in);
                bits(a.ntm, b.ntm);
                bits(a.out, b.out);
            }
        }

        void apply(boolean second, Object source, float amount, float neg, float pen, float ntm) throws Exception {
            bits(old.apply(second ? before.b : before.a, source, amount, neg, pen, ntm),
                    applyOf(second ? after.b : after.a, source, amount, neg, pen, ntm,
                            after.hurts, after.shots, after.now, after.cap, after.enabled, after.worn));
            same();
        }

        void watch(Object previous, Object target) throws Exception {
            Old.Seen expected = old.watch(previous, target);
            BalanceArmorBase.Seen actual = target == B
                    ? BalanceArmorBase.watch(previous, target, SeenView::new) : BalanceArmorBase.watch(previous, target);
            assertEquals(expected.hits, actual.hits);
            bits(expected.in, actual.in);
            bits(expected.ntm, actual.ntm);
            bits(expected.out, actual.out);
            same();
        }

        void take(Object target, float fallback) throws Exception {
            bits(old.takeAmount(target, fallback), takeAmount(target, fallback));
            assertNull(field("amountOf"));
            same();
        }
    }

    @Test
    void applyMatchesOldBitsStateAndReads() throws Exception {
        float[] damage = {-1F, -0F, 0F, 1F, 60F, 61F, Float.NaN, Float.POSITIVE_INFINITY};
        float[] modifiers = {-1F, -0F, 0F, 0.37F, 1F, Float.NaN, Float.POSITIVE_INFINITY};
        float[] ntms = {-1F, -0F, 0F, 1F, Float.NaN};
        for (String[] kit : KITS) for (Source source : new Source[] {PHYS, EN, EXPL, IR}) {
            Run r = new Run(kit);
            r.watch(null, A);
            for (float amount : damage) for (float neg : modifiers) for (float pen : modifiers) for (float ntm : ntms) {
                r.apply(false, source, amount, neg, pen, ntm);
                r.take(A, 7F);
                r.take(A, 0F);
            }
            r.watch(A, B);
            r.apply(false, PHYS, 5F, 0F, 1F, 2F);
            r.apply(true, PHYS, 7F, 1F, 0F, 3F);
            r.take(A, 9F);
            r.take(B, 0F);
            clear();
        }
    }

    @Test
    void blastUnderCorrosionAndClientMatchOld() throws Exception {
        for (String[] kit : KITS) for (Source s : new Source[] {PHYS, EN, EXPL, IR})
            for (float k : new float[] {-1F, 0F, 0.5F, 1F, Float.NaN, Float.POSITIVE_INFINITY})
                for (boolean bullet : new boolean[] {false, true}) for (boolean client : new boolean[] {false, true})
                    for (boolean rocket : new boolean[] {false, true}) {
                        Run r = new Run(kit);
                        r.before.client = r.after.client = client;
                        r.before.corrosion = r.after.corrosion = k;
                        r.before.under = r.after.under = new float[] {0.31F, 0.23F, -0.45F};
                        r.before.bullet = r.after.bullet = bullet ? BULLET : null;
                        r.watch(null, A);
                        // Use the production context guard, including its finally restoration.
                        withBlastPower(!bullet, rocket, bullet, 20F, () -> {
                            assertEquals(bullet, bulletBlastNow());
                            try {
                                for (float amount : new float[] {1F, 20F, 60F, Math.nextUp(60F), 100F}) {
                                    r.apply(false, s, amount, 0.72F, 0.64F, 3.5F);
                                    r.take(A, -0F);
                                    r.take(A, 0F);
                                }
                            } catch (Exception e) { throw new AssertionError(e); }
                            return 0F;
                        });
                        assertFalse(bulletBlastNow());
                        assertEquals(1F, BLAST_POWER.get());
                        clear();
                    }
    }

    @Test
    void coversCategoriesAndSchemeMatchOld() throws Exception {
        for (boolean ntm : new boolean[] {false, true})
            for (String[] kit : KITS) for (Source s : SOURCES) for (boolean client : new boolean[] {false, true})
                for (boolean scheme : new boolean[] {false, true}) for (boolean mob : new boolean[] {false, true}) {
                    ServerSettings.useRemote(new ServerSettings.Snapshot("", Map.of("balance", ntm ? "ntm" : "arms"), Map.of(), ""));
                    Run r = new Run(kit);
                    r.before.client = r.after.client = client;
                    r.before.scheme = r.after.scheme = scheme;
                    r.before.mob = r.after.mob = mob;
                    assertEquals(r.old.covers(r.before.a, s),
                            coversOf(r.after.a, s, r.after.hurts, r.after.shots, r.after.now, r.after.enabled, r.after.worn));
                    r.same();
                    r.apply(false, s, 12F, 0.1F, 0.7F, 4F);
                    r.take(A, 0F);
                }
    }

    @Test
    @SuppressWarnings("unchecked")
    void pairedSchemeMatchesOld() throws Exception {
        Field f = BalanceFinalBase.class.getDeclaredField("PAIR");
        f.setAccessible(true);
        Map<Object, String> pairs = (Map<Object, String>) f.get(null);
        Object projectile = new Object();
        pairs.put(projectile, "stg77.r556_ap");
        try {
            for (String[] kit : KITS) {
                Run r = new Run(kit);
                r.before.scheme = r.after.scheme = true;
                Source s = new Source(0, 0, "PHYS", "hbm:physical", projectile);
                assertEquals(r.old.covers(r.before.a, s),
                        coversOf(r.after.a, s, r.after.hurts, r.after.shots, r.after.now, r.after.enabled, r.after.worn));
                r.same();
                r.apply(false, s, 20F, 0F, 1F, 3F);
                r.take(A, 0F);
            }
        } finally {
            pairs.remove(projectile);
        }
    }

    @Test
    void watchResetsAndAmountIsConsumedAtEveryTarget() throws Exception {
        Run r = new Run(KITS[1]);
        r.watch(null, A);
        r.apply(false, PHYS, 13F, 0.2F, 0.8F, 7F);
        r.watch(A, B);
        r.apply(false, PHYS, 17F, 0.3F, 0.7F, 8F);
        r.apply(true, PHYS, 23F, 0.4F, 0.6F, 9F);
        r.watch(B, B);
        r.apply(true, PHYS, 29F, 0.5F, 0.5F, 10F);
        r.take(A, 41F);
        r.take(B, 0F);
        r.apply(true, PHYS, 31F, 0.6F, 0.4F, 11F);
        r.before.client = r.after.client = true;
        r.apply(false, PHYS, 37F, 0.7F, 0.3F, 12F);
        r.take(B, -1F);
        r.take(B, 0F);
        r.watch(null, A);
        r.take(null, -0F);
        r.take(null, 0F);
    }

    @Test
    void floorShotIrAndShotMatchOldCalls() throws Exception {
        Run r = new Run(KITS[0]);
        for (Source s : SOURCES) for (boolean blast : new boolean[] {false, true})
            for (Object bullet : new Object[] {null, BULLET}) {
                r.before.bullet = r.after.bullet = bullet;
                assertSame(r.old.floorShot(s, blast), floorShotOf(s, blast, r.after.shots));
                assertEquals(oldIR(r.before, s), irBeamOf(r.after.hurts, s));
                assertEquals(oldShot(r.before, s), shotTypeOf(r.after.hurts, s));
                r.same();
            }
    }

    @Test
    void setsAndMissingReads() {
        TreeSet<String> expected = new TreeSet<>(SETS.keySet());
        expected.addAll(BalanceFinalBase.DEFAULT_SETS.keySet());
        assertEquals(expected, knownSets());
        Set<String> names = new TreeSet<>(expected);
        names.addAll(List.of("dnt", "missing", ""));
        for (String name : names) assertEquals(expected.contains(name), knownSet(name));
        knownSets().clear();
        assertEquals(expected, knownSets());
        String[] items = {"minecraft:air", "other:hev_helmet", "hbm:hev_helmet", "hbm:bj_plate_jetpack",
                "hbm:unknown_boots", "hbm:dnt_legs", "hbm:steel_plate", "hbm:steel_plate_jetpack"};
        List<String> reads = new ArrayList<>();
        SetsOf sets = new SetsOf(n -> { reads.add(items[n]); return items[n]; });
        for (int repeat = 0; repeat < 2; repeat++) for (int i = 0; i < items.length; i++) for (int slot = 0; slot < 4; slot++) {
            String path = items[i].startsWith("hbm:") ? items[i].substring(4) : null;
            if (path != null && slot == 1 && path.endsWith("_plate_jetpack")) path = path.substring(0, path.length() - 8);
            String expectedSet = path != null && path.endsWith(SUFFIX[slot]) ? path.substring(0, path.length() - SUFFIX[slot].length()) : null;
            assertEquals(expectedSet, sets.of(i, slot));
        }
        assertEquals(items.length * 4, reads.size());
        assertThrows(UnsupportedOperationException.class, () -> HurtNow.MISSING.floorOf(null, 1));
        assertThrows(UnsupportedOperationException.class, () -> HurtNow.MISSING.underOf(null, 1));
    }
}
