package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import acup.gunfeel.host.Body;

// who fights whom, off the game: bodies of a team, players or not, tagged or not, AI mobs where the level's registry has them
class AiTeamsBaseTest {
    private final Object level = new Object(), elsewhere = new Object();
    private final AiBotsBase.PerLevelBase<AiBotBase> registry = new AiBotsBase.PerLevelBase<>();
    private int ids;

    private Body body(@Nullable String team, boolean player, boolean tagged, Object in) {
        int id = ++ids;
        return (Body) Proxy.newProxyInstance(Body.class.getClassLoader(), new Class<?>[] {Body.class}, (p, m, a) -> switch (m.getName()) {
            case "id" -> id;
            case "team" -> team == null ? null : new String(team);
            case "player" -> player;
            case "tagged" -> tagged && AiTeamsBase.TARGET_TAG.equals(a[0]);
            case "level" -> in;
            case "hashCode" -> id;
            case "equals" -> p == a[0];
            default -> throw new AssertionError("not asked: " + m.getName());
        });
    }

    /** an AI mob of the level: in its registry */
    private Body bot(@Nullable String team) {
        Body b = body(team, false, false, level);
        registry.bots.put(b.id(), AiBotBaseTest.bot(b));
        AiBotsBase.LEVELS.put(level, registry);
        return b;
    }

    @AfterEach
    void forget() {
        AiBotsBase.LEVELS.remove(level);
    }

    @Test
    void players() {
        Body red = bot("red"), loner = bot(null);
        assertTrue(AiTeamsBase.enemy(red, body(null, true, false, level)), "a player with no team");
        assertTrue(AiTeamsBase.enemy(red, body("blue", true, false, level)));
        assertFalse(AiTeamsBase.enemy(red, body("red", true, false, level)), "its team's, by name (another String)");
        assertTrue(AiTeamsBase.enemy(loner, body("red", true, false, level)), "one with no team fights every player");
        assertTrue(AiTeamsBase.own(red, body("red", true, false, level)));
        assertFalse(AiTeamsBase.own(loner, body(null, true, false, level)));
        assertFalse(AiTeamsBase.own(red, body("blue", true, false, level)));
    }

    @Test
    void aiMobs() {
        Body red = bot("red"), red2 = bot("red"), blue = bot("blue"), loner = bot(null);
        assertFalse(AiTeamsBase.enemy(red, red), "not itself");
        assertFalse(AiTeamsBase.own(red, red));
        assertTrue(AiTeamsBase.enemy(red, blue));
        assertFalse(AiTeamsBase.enemy(red, red2));
        assertTrue(AiTeamsBase.own(red, red2));
        assertFalse(AiTeamsBase.enemy(red, loner), "AI mobs with no team are nobody's enemy");
        assertFalse(AiTeamsBase.enemy(loner, red));
        assertTrue(AiTeamsBase.own(red, loner), "kept out of its line of fire");
        assertFalse(AiTeamsBase.own(red, blue));
    }

    @Test
    void notInTheRegistry() {
        Body red = bot("red");
        // a mob of another team that is no AI mob, one in another level, one of an AI mob's id with another body
        assertFalse(AiTeamsBase.enemy(red, body("blue", false, false, level)));
        assertFalse(AiTeamsBase.enemy(red, body("blue", false, false, elsewhere)));
        Body stranger = body("blue", false, false, level);
        registry.bots.put(stranger.id(), AiBotBaseTest.bot(body("blue", false, false, level)));
        assertFalse(AiTeamsBase.enemy(red, stranger), "its id, another body: not that mob");
        assertFalse(AiTeamsBase.own(red, stranger));
    }

    @Test
    void tagged() {
        Body red = bot("red");
        assertTrue(AiTeamsBase.enemy(red, body("red", false, true, level)), "the stands' targets: everyone's, its team or not");
        assertTrue(AiTeamsBase.enemy(bot(null), body(null, false, true, elsewhere)));
    }
}
