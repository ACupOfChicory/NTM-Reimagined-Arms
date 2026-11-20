package acup.gunfeel;

import static acup.gunfeel.EuphImmunityBase.*;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;

import acup.gunfeel.host.Body;
import acup.gunfeel.host.BodyControl;
import acup.gunfeel.host.FakeHurts;
import acup.gunfeel.host.HurtNow;
import acup.gunfeel.host.Hurts;
import acup.gunfeel.host.WorldView;
import acup.gunfeel.host.Worlds;

/** The old decisions against core: separate UUIDs, every state field and every read in order. */
class AbsorbsTest {
    private static final boolean[] BOOL = {false, true};
    private static final int[] AMPS = {-1, 50, 100, 127};
    private record Input(Kit kit, boolean player, boolean bypass, boolean radon, boolean gas, boolean balance,
            String cat, boolean shot, int phase, int regen, int res) {
    }

    private static final class Reads implements HurtNow, Hurts {
        final Input in;
        final List<String> calls = new ArrayList<>();
        final UUID id = UUID.randomUUID();
        final Object level = new Object();
        final Body body;
        final Worlds.Port worlds;
        final BodyControl control;
        final BooleanSupplier balance;
        final FakeHurts fake = new FakeHurts();
        long tick = 100;
        int regen, res;
        final Object source;

        Reads(Input in) {
            this.in = in;
            regen = in.regen;
            res = in.res;
            source = source();
            body = (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> {
                return switch (m.getName()) {
                    case "player" -> { calls.add("player"); yield in.player; }
                    case "uuid" -> { calls.add("uuid"); yield id; }
                    case "level" -> { calls.add("level"); yield level; }
                    case "maxHealth" -> { calls.add("maxHealth"); yield 40F; }
                    case "effectAmplifier" -> {
                        int which = (int) a[0];
                        calls.add("effectAmplifier " + which);
                        yield which == Body.EFFECT_REGENERATION ? regen : res;
                    }
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                };
            });
            WorldView world = (WorldView) Proxy.newProxyInstance(WorldView.class.getClassLoader(), new Class<?>[] {WorldView.class}, (p, m, a) -> {
                if (!m.getName().equals("gameTime")) throw new UnsupportedOperationException(m.getName());
                calls.add("time");
                return tick;
            });
            worlds = l -> { assertSame(level, l); return world; };
            control = (BodyControl) Proxy.newProxyInstance(BodyControl.class.getClassLoader(), new Class<?>[] {BodyControl.class}, (p, m, a) -> {
                assertSame(body, a[0]);
                switch (m.getName()) {
                    case "removeEffect" -> {
                        int which = (int) a[1];
                        calls.add("removeEffect " + which);
                        if (which == Body.EFFECT_REGENERATION) regen = -1;
                        else if (which == Body.EFFECT_RESISTANCE) res = -1;
                        else throw new IllegalArgumentException();
                    }
                    case "tellBar" -> calls.add("tellBar " + a[1] + " " + a[2]);
                    default -> throw new UnsupportedOperationException(m.getName());
                }
                return null;
            });
            balance = () -> { calls.add("balanceOn"); return in.balance; };
        }

        Object source() {
            return new FakeHurts.Hurt(in.bypass ? 1 << Hurts.BYPASSES_INVULNERABILITY : 0, 0,
                    in.cat, in.shot ? "hbm:physical" : "minecraft:player_attack");
        }

        @Override public boolean gas() { calls.add("gas"); return in.gas; }
        @Override public int reach(Object hurt) { calls.add("reach"); return 450; }
        @Override public float schemeDrain(Body b, Object hurt) { assertSame(body, b); calls.add("schemeDrain"); return 13.25F; }
        @Override public boolean onHead(Body b) { calls.add("onHead"); return false; }
        @Override public float[] floorExtra(Object hurt, boolean blast) { throw new UnsupportedOperationException(); }
        @Override public float ntmPierce(Body b, int which) { throw new UnsupportedOperationException(); }
        @Override public boolean is(Object hurt, int which) { calls.add("is " + which); return fake.is(hurt, which); }
        @Override public int directKind(Object hurt) { calls.add("directKind"); return fake.directKind(hurt); }
        @Override public String ntmCategory(Object hurt) { calls.add("ntmCategory"); return fake.ntmCategory(hurt); }
        @Override public String typeName(Object hurt) { calls.add("typeName"); return fake.typeName(hurt); }
    }

    private record Decided(int source, long tick, boolean off) {
    }

    private static final class Old {
        final Map<UUID, Decided> decided = new HashMap<>();
        final Reads r;

        Old(Reads r) { this.r = r; }

        // The old absorbs body, with only the game's reads replaced by the fake ports.
        boolean absorbs(Kit kit, Body player, Object source, float amount) {
            if (!player.player() && r.is(source, Hurts.BYPASSES_INVULNERABILITY)) return false;
            long now = r.worlds.world(player.level()).gameTime();
            if (radonOf(player, now)) {
                if (kit == Kit.EUPHEMIUM) dropEffects(player);
                return false;
            }
            boolean gas = r.gas() && r.balance.getAsBoolean();
            String cat = kit == Kit.EUPHEMIUM ? categoryAny(source) : category(source);
            if (kit != Kit.EUPHEMIUM && cat == null && !gas) return false;
            State s = stateOf(kit, player, now);
            Pool p = pool(kit);
            float drain = gas ? amount * (float) BalanceFinalRule.HP / player.maxHealth() : drain(kit, player, source, amount, cat);
            boolean keeps = gas || (kit == Kit.EUPHEMIUM ? category(source) != null : cat != null);
            Phase before;
            boolean off;
            synchronized (EuphImmunityBase.class) {
                before = phase(s, now);
                off = hit(s, now, drain, gas || cat != null, keeps, p, keeps && !gas ? r.reach(source) : 0);
            }
            if (before == Phase.POOL && phase(s, now + 1) == Phase.WINDOW) {
                if (kit == Kit.EUPHEMIUM) dropEffects(player);
                r.control.tellBar(player, kit.message, p.window() / 20);
            }
            return off;
        }

        float drain(Kit kit, Body player, Object source, float amount, String cat) {
            if (cat == null) return 0F;
            float scheme = r.schemeDrain(player, source);
            return drainOf(r, r, kit, player, source, amount, cat, scheme);
        }

        String categoryAny(Object s) {
            if (r.is(s, Hurts.BYPASSES_EFFECTS)) return null;
            if (r.is(s, Hurts.NTM_NUCLEAR_BLAST) || r.is(s, Hurts.NTM_DIGAMMA) || r.is(s, Hurts.NTM_RADIATION)) return null;
            String cat = r.ntmCategory(s);
            if ("FIRE".equals(cat) && irBeam(s)) return "EN";
            if ("EXPL".equals(cat)) return ReaimTimes.flag("balanceArmorBlasts", true) ? cat : null;
            return "EN".equals(cat) || "PHYS".equals(cat) ? cat : null;
        }

        boolean irBeam(Object s) {
            return (r.directKind(s) & Hurts.NTM_BEAM) != 0 && r.is(s, Hurts.NTM_FIRE);
        }

        String category(Object s) {
            String cat = categoryAny(s);
            return cat == null || "EXPL".equals(cat) || shot(s) || irBeam(s) ? cat : null;
        }

        boolean shot(Object s) {
            String name = r.typeName(s);
            return name != null && BalanceArmorRule.shotName(name, (r.directKind(s) & Hurts.LIVING) != 0);
        }

        void dropEffects(Body player) {
            int regen = player.effectAmplifier(Body.EFFECT_REGENERATION), res = player.effectAmplifier(Body.EFFECT_RESISTANCE);
            if (regen >= 100) r.control.removeEffect(player, Body.EFFECT_REGENERATION);
            if (res >= 100) r.control.removeEffect(player, Body.EFFECT_RESISTANCE);
        }

        boolean early(Kit kit, Body player, Object source, float amount) {
            boolean off = absorbs(kit, player, source, amount);
            synchronized (EuphImmunityBase.class) {
                decided.put(player.uuid(), new Decided(System.identityHashCode(source), r.worlds.world(player.level()).gameTime(), off));
            }
            return off;
        }

        boolean accepted(Kit kit, Body player, Object source, float amount) {
            Decided d;
            synchronized (EuphImmunityBase.class) {
                d = decided.get(player.uuid());
            }
            if (d != null && d.source() == System.identityHashCode(source) && d.tick() == r.worlds.world(player.level()).gameTime()) return d.off();
            return absorbs(kit, player, source, amount);
        }
    }

    private static void prepare(Reads r) {
        State s = stateOf(r.in.kit, r.body, r.tick);
        switch (r.in.phase) {
            case 0 -> { }
            case 1 -> s.pool = 0.25F;
            case 2 -> { s.windowStart = r.tick - 1; s.windowEnd = r.tick + 20; s.recoverEnd = r.tick + 1220; }
            case 3 -> { s.windowStart = r.tick - 301; s.windowEnd = r.tick - 1; s.recoverEnd = r.tick + 1199; }
            default -> throw new AssertionError();
        }
        if (r.in.radon) inRadonOf(r.body, r.tick);
        r.calls.clear();
    }

    @SuppressWarnings("unchecked")
    private static List<Number> state(Reads r) throws Exception {
        Field f = EuphImmunityBase.class.getDeclaredField("STATES");
        f.setAccessible(true);
        State s = ((Map<Kit, Map<UUID, State>>) f.get(null)).get(r.in.kit).get(r.id);
        return s == null ? List.of() : List.of(Float.floatToRawIntBits(s.pool), s.at, s.lastHit, s.windowStart, s.windowEnd, s.recoverEnd, s.pause);
    }

    private static void compare(Reads a, Reads b, boolean want, boolean got) throws Exception {
        assertEquals(want, got, a.in::toString);
        assertEquals(a.calls, b.calls, () -> "ordered reads " + a.in);
        assertEquals(state(a), state(b), () -> "state bits " + a.in);
        assertEquals(a.regen, b.regen, "regeneration after removal");
        assertEquals(a.res, b.res, "resistance after removal");
    }

    @Test
    void absorbsMatchesOldInBitsStateAndReads() throws Exception {
        int transitions = 0, melee = 0;
        try {
            for (Kit kit : Kit.values())
                for (boolean player : BOOL)
                    for (boolean bypass : BOOL)
                        for (boolean radon : BOOL)
                            for (boolean gas : BOOL)
                                for (boolean balance : BOOL)
                                    for (String cat : new String[] {"PHYS", "EN", "EXPL", "FIRE", null})
                                        for (boolean shot : BOOL)
                                            for (int phase = 0; phase < 4; phase++)
                                                for (int regen : AMPS)
                                                    for (int res : AMPS) {
                                                        clearAll();
                                                        Input in = new Input(kit, player, bypass, radon, gas, balance, cat, shot, phase, regen, res);
                                                        Reads a = new Reads(in), b = new Reads(in);
                                                        assertNotEquals(a.id, b.id);
                                                        prepare(a);
                                                        prepare(b);
                                                        Old old = new Old(a);
                                                        compare(a, b, old.absorbs(kit, a.body, a.source, 50F),
                                                                absorbsOf(kit, b.body, b.source, 50F, b, b, b.worlds, b.control, b.balance));
                                                        if (a.calls.stream().anyMatch(c -> c.startsWith("tellBar "))) transitions++;
                                                        if (kit == Kit.EUPHEMIUM && "PHYS".equals(cat) && !shot && a.calls.contains("schemeDrain") && !a.calls.contains("reach")) melee++;
                                                    }
            assertTrue(transitions > 0, "POOL to WINDOW was exercised");
            assertTrue(melee > 0, "categoryAny drains melee while category does not keep the window");
        } finally {
            clearAll();
        }
    }

    @Test
    void earlyAndAcceptedRecognizeTheGameSourceOnlyInItsTick() throws Exception {
        try {
            clearAll();
            Input in = new Input(Kit.DNS, true, false, false, false, true, "PHYS", true, 0, -1, -1);
            Reads a = new Reads(in), b = new Reads(in);
            prepare(a);
            prepare(b);
            Old old = new Old(a);
            compare(a, b, old.early(in.kit, a.body, a.source, 50F),
                    earlyOf(in.kit, b.body, b.source, 50F, b, b, b.worlds, b.control, b.balance));
            for (int step = 0; step < 4; step++) {
                a.calls.clear();
                b.calls.clear();
                Object sa = step == 2 ? a.source() : a.source, sb = step == 2 ? b.source() : b.source;
                if (step == 3) { a.tick++; b.tick++; }
                compare(a, b, old.accepted(in.kit, a.body, sa, 50F),
                        acceptedOf(in.kit, b.body, sb, 50F, b, b, b.worlds, b.control, b.balance));
                if (step < 2) assertEquals(List.of("uuid", "level", "time"), b.calls, "same source reuses early twice");
                else assertTrue(b.calls.contains("gas"), "different source or tick runs absorbs again");
            }
        } finally {
            clearAll();
        }
    }

    @Test
    void dropsOnlySetEffectsAndReadsBothBeforeRemoval() {
        for (int regen : AMPS)
            for (int res : AMPS) {
                Reads a = new Reads(new Input(Kit.EUPHEMIUM, true, false, false, false, true, null, false, 0, regen, res));
                Reads b = new Reads(a.in);
                new Old(a).dropEffects(a.body);
                dropSetEffects(b.body, b.control);
                assertEquals(a.calls, b.calls, "effect reads and removals");
                assertEquals(a.regen, b.regen);
                assertEquals(a.res, b.res);
            }
    }

    @Test
    void forgetAndClearDiscardEarlyDecisions() throws Exception {
        try {
            for (boolean forget : BOOL) {
                clearAll();
                Reads r = new Reads(new Input(Kit.DNS, true, false, false, false, true, "PHYS", true, 0, -1, -1));
                earlyOf(r.in.kit, r.body, r.source, 50F, r, r, r.worlds, r.control, r.balance);
                if (forget) forgetOf(r.body); else clearAll();
                r.calls.clear();
                acceptedOf(r.in.kit, r.body, r.source, 50F, r, r, r.worlds, r.control, r.balance);
                assertTrue(r.calls.contains("gas"), "forgotten early decision must be computed anew");
            }
        } finally {
            clearAll();
        }
    }
}
