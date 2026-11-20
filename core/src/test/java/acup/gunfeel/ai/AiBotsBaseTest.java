package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

// the AI mobs of each level, off the game: a level is any object, its bots kept per level till it is forgotten
class AiBotsBaseTest {
    private final Object level = new Object(), other = new Object();

    @AfterEach
    void forget() {
        AiBotsBase.LEVELS.remove(level);
        AiBotsBase.LEVELS.remove(other);
    }

    @Test
    void perLevelTillForgotten() {
        assertTrue(AiBotsBase.bots(level).isEmpty());
        AiBotsBase.PerLevelBase<AiBotBase> l = new AiBotsBase.PerLevelBase<>();
        AiBotsBase.LEVELS.put(level, l);
        AiBotBase a = AiBotBaseTest.bot(), b = AiBotBaseTest.bot();
        l.bots.put(7, a);
        l.bots.put(3, b);
        assertEquals(2, AiBotsBase.bots(level).size());
        assertTrue(AiBotsBase.bots(other).isEmpty());
        // the map's own order: the same as the map's values
        assertEquals(List.copyOf(l.bots.values()), List.copyOf(AiBotsBase.bots(level)));
        AiBotsBase.LEVELS.remove(level);
        assertTrue(AiBotsBase.bots(level).isEmpty());
    }
}
