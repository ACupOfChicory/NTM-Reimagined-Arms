package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import acup.gunfeel.host.Body;

import org.junit.jupiter.api.Test;

// A mob lying down, off the game: the terms it may lie on, read in the game's order and no further than the first
// that fails; the shooter of a near miss told once.
class AiProneBaseTest {
    /** a Body answering these reads, each read written down */
    private static Body body(Map<String, Object> answers, List<String> read) {
        return (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> {
            read.add(m.getName());
            Object v = answers.get(m.getName());
            if (v == null) throw new UnsupportedOperationException(m.getName());
            return v;
        });
    }

    private static Map<String, Object> standing() {
        return Map.of("alive", true, "onGround", true, "inWater", false, "passenger", false, "sleeping", false, "stance", Stance.STAND);
    }

    @Test
    void itMayLieOnAPlayersTermsReadInTheGamesOrder() {
        List<String> read = new ArrayList<>();
        assertFalse(AiProneBase.may(body(standing(), read), false));
        assertEquals(List.of(), read, "the prone key off: nothing of the mob read");
        assertTrue(AiProneBase.may(body(standing(), read), true));
        assertEquals(List.of("alive", "onGround", "inWater", "passenger", "sleeping"), read);
        read.clear();
        Map<String, Object> wet = new java.util.HashMap<>(standing());
        wet.put("inWater", true);
        assertFalse(AiProneBase.may(body(wet, read), true));
        assertEquals(List.of("alive", "onGround", "inWater"), read);
    }

    @Test
    void theShooterOfANearMissToldOnce() {
        List<String> read = new ArrayList<>();
        AiProneBase p = new AiProneBase(body(standing(), read), () -> true);
        Body shooter = body(standing(), new ArrayList<>());
        p.pass(shooter, 1.0, 3.0, false, 100);
        p.pass(null, 1.5, 3.0, false, 100);
        assertSame(shooter, p.watchShots(100));
        assertNull(p.watchShots(101));
        p.pass(shooter, AiProneBase.NEAR + 0.5, 3.0, false, 102);
        assertNull(p.watchShots(102), "past NEAR: no near miss");
        assertEquals(2, p.nearMisses);
        assertFalse(p.lying());
    }
}
