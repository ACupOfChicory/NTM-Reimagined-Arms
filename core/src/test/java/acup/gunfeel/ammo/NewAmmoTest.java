package acup.gunfeel.ammo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import acup.gunfeel.host.AmmoCatalog;
import acup.gunfeel.host.AmmoCatalogContract;

/** The old matching rules against core, including every live question and short circuit. */
class NewAmmoTest extends AmmoCatalogContract {
    private static final Object[] TOKENS = {new Token("a"), new Token("b"), new Token("c"), new Token("d"), new Token("e"),
            new Token("sp556"), new Token("sp762"), new Token("slug"), new Token("sp50"), new Token("other")};
    private static final RuntimeException FAILURE = new IllegalStateException("catalog failure");

    // Equal values must still be distinguished by identity by the matching rules.
    private record Token(String id) {
        @Override public boolean equals(Object other) { return other instanceof Token; }
        @Override public int hashCode() { return 1; }
        @Override public String toString() { return id; }
    }

    @Override protected AmmoCatalog catalog() { return new Fake(); }
    @Override protected Object[] rounds() { return Arrays.copyOf(TOKENS, 9); }

    private static final class Fake implements AmmoCatalog {
        final Object[] rounds = Arrays.copyOf(TOKENS, 9);
        final List<String> log = new ArrayList<>();
        int failAt = -1;

        void event(String s) {
            log.add(s);
            if (log.size() == failAt) throw FAILURE;
        }
        @Override public Object duplex556() { event("d556"); return rounds[0]; }
        @Override public Object duplex762() { event("d762"); return rounds[1]; }
        @Override public boolean rubber(Object c) { event("rubber:" + c); return c != null && c == rounds[2]; }
        @Override public Object shock() { event("shock"); return rounds[4]; }
        @Override public boolean nuclear(Object c) { event("nuclear:" + c); return c != null && c == rounds[3]; }
        @Override public Object sp556() { event("sp556"); return rounds[5]; }
        @Override public Object sp762() { event("sp762"); return rounds[6]; }
        @Override public Object slug() { event("slug"); return rounds[7]; }
        @Override public Object sp50() { event("sp50"); return rounds[8]; }
    }

    // Copies of a5096a9's expressions; only the game's reads are replaced by the fake catalog.
    private static Object oldLookalike(Object c, AmmoCatalog f) {
        if (c == null) return null;
        if (c == f.duplex556()) return f.sp556();
        if (c == f.duplex762()) return f.sp762();
        if (f.rubber(c) || c == f.shock()) return f.slug();
        if (f.nuclear(c)) return f.sp50();
        return c;
    }

    private static Object oldWaterLike(Object c, AmmoCatalog f) {
        return f.nuclear(c) ? c : oldLookalike(c, f);
    }

    private static boolean oldNoHole(Object c, AmmoCatalog f) {
        return c != null && (f.rubber(c) || f.nuclear(c) || c == f.shock());
    }

    private static Object match(int method, boolean old, Object c, Fake f) {
        return switch (method) {
            case 0 -> old ? oldLookalike(c, f) : NewAmmoBase.lookalike(c, f);
            case 1 -> old ? oldWaterLike(c, f) : NewAmmoBase.waterLike(c, f);
            case 2 -> old ? oldNoHole(c, f) : NewAmmoBase.noHole(c, f);
            default -> throw new AssertionError(method);
        };
    }

    @Test
    void everyRuleAndNullAgainstTheOldCode() {
        for (int method = 0; method < 3; method++) {
            for (Object c : new Object[] {null, TOKENS[0], TOKENS[1], TOKENS[2], TOKENS[3], TOKENS[4], TOKENS[5],
                    TOKENS[6], TOKENS[7], TOKENS[8], TOKENS[9]}) {
                Fake was = new Fake(), now = new Fake();
                assertSame(match(method, true, c, was), match(method, false, c, now));
                assertEquals(was.log, now.log);
            }
        }
    }

    @Test
    void aliasesAndUninitializedConfigsKeepPriorityAndShortCircuit() {
        // Every assignment of the five mod identities to null or one of three shared identities.
        for (int mask = 0; mask < 1024; mask++) {
            Fake was = new Fake(), now = new Fake();
            int bits = mask;
            for (int i = 0; i < 5; i++) {
                int k = bits & 3;
                was.rounds[i] = now.rounds[i] = k == 0 ? null : TOKENS[k - 1];
                bits >>>= 2;
            }
            for (int method = 0; method < 3; method++) for (Object c : new Object[] {null, TOKENS[0], TOKENS[1], TOKENS[2], TOKENS[9]}) {
                was.log.clear();
                now.log.clear();
                assertSame(match(method, true, c, was), match(method, false, c, now));
                assertEquals(was.log, now.log);
            }
        }
    }

    @Test
    void readsAreLiveAcrossCallsAndNullTargetsStayNull() {
        Fake was = new Fake(), now = new Fake();
        for (int i = 0; i < 9; i++) {
            was.rounds[i] = now.rounds[i] = null;
            for (int method = 0; method < 3; method++) for (Object c : TOKENS) {
                assertSame(match(method, true, c, was), match(method, false, c, now));
                assertEquals(was.log, now.log);
            }
        }
    }

    private static Object thrownMatch(int method, boolean old, Object c, Fake f) {
        try { return match(method, old, c, f); }
        catch (RuntimeException e) { return e; }
    }

    @Test
    void exceptionsStopMatchingAtTheSameRead() {
        for (int method = 0; method < 3; method++) for (Object c : TOKENS) {
            Fake complete = new Fake();
            match(method, true, c, complete);
            for (int failAt = 1; failAt <= complete.log.size(); failAt++) {
                Fake was = new Fake(), now = new Fake();
                was.failAt = now.failAt = failAt;
                assertSame(FAILURE, thrownMatch(method, true, c, was));
                assertSame(FAILURE, thrownMatch(method, false, c, now));
                assertEquals(was.log, now.log);
            }
        }
    }
}
