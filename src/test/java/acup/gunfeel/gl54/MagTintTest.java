package acup.gunfeel.gl54;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

// The colour of each mark on the magazine's icon: the kind of the grenade at that place of the remaining list, the hole's
// colour where there is none (the marks shift up as the gun fires).
class MagTintTest {
    @Test
    void marksFollowTheRemainingList() {
        MagContents c = new MagContents(List.of("airburst", "he", "he"));
        assertEquals(GrenadeKind.AIRBURST.argb(), MagTint.colorOf(c, 0));
        assertEquals(GrenadeKind.HE.argb(), MagTint.colorOf(c, 1));
        assertEquals(GrenadeKind.HE.argb(), MagTint.colorOf(c, 2));
        for (int i = 3; i < 8; i++) assertEquals(MagTint.EMPTY, MagTint.colorOf(c, i), "mark " + i);
        // after a shot the same list is one shorter and every mark has moved up
        MagContents fired = new MagContents(c.rounds().subList(1, 3));
        assertEquals(GrenadeKind.HE.argb(), MagTint.colorOf(fired, 0));
        assertEquals(MagTint.EMPTY, MagTint.colorOf(fired, 2));
    }

    @Test
    void anEmptyMagazineIsAllHoles() {
        for (int i = 0; i < 8; i++) assertEquals(MagTint.EMPTY, MagTint.colorOf(MagContents.EMPTY, i));
    }
}
