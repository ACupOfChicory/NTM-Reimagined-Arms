package acup.gunfeel.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * What every version's EntityTypeTable must say: a type's id is the same String object each time, a number no type has
 * throws; a type is in its tags and not in others, a tag name that is no id takes none. A version's test (26.2: the
 * bullet stand's world stage, the game's types and tags being there only) gives a type number {@link #zombie} of
 * {@code minecraft:zombie}, which the tag {@link #undead} takes and the tag {@link #skeletons} does not.
 */
public abstract class EntityTypeTableContract {
    protected abstract EntityTypeTable table();

    /** the number of minecraft:zombie */
    protected abstract int zombie();

    /** a number no type has */
    protected abstract int none();

    protected String undead() {
        return "minecraft:undead";
    }

    protected String skeletons() {
        return "minecraft:skeletons";
    }

    @Test
    void idOneString() {
        assertEquals("minecraft:zombie", table().name(zombie()));
        assertSame(table().name(zombie()), table().name(zombie()));
    }

    @Test
    void noTypeThrows() {
        assertThrows(IllegalArgumentException.class, () -> table().name(none()));
    }

    @Test
    void tagsTheGames() {
        assertTrue(table().tagged(zombie(), undead()));
        assertFalse(table().tagged(zombie(), skeletons()));
        assertFalse(table().tagged(zombie(), "Not An Id!"));
    }
}
