package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AiRadiationTest {
    // as NTM's calculateRadiationMod for a player: none 1, a full DNS set (2 + 1.5 + 1 + 0.5) 1e-5
    @Test
    void asAPlayer() {
        assertEquals(1.0, AiRadiation.mod(0), 0);
        assertEquals(1e-5, AiRadiation.mod(2 + 1.5 + 1 + 0.5), 1e-12);
        assertEquals(0.1, AiRadiation.mod(1), 1e-12);
    }
}
