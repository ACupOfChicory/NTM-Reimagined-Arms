package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

// NTM's old bullet past a fresh hit (LegacyBullets.hurt): which of NTM's two hurts takes the head's or a limb's
// change is decided by NTM's own damage, so the head is never counted lower than the body there, nor a limb higher.
class LegacyHurtTest {
    // what the creature loses: the game in its cooldown takes a hit only past the last one (lastHurt), and only what
    // it is past it by; NTM hurts again with its damage plus the last hit when the first fails
    static float lost(float damage, float extra, float lastHurt) {
        var h = LegacyBullets.hurt(damage, extra, true, lastHurt);
        float first = h == LegacyBullets.Hurt.SECOND ? damage : damage + extra;
        if (first > lastHurt) return first - lastHurt;
        if (h == LegacyBullets.Hurt.NONE) return 0F;
        float second = damage + lastHurt + extra;
        return second > lastHurt ? second - lastHurt : 0F;
    }

    @Test
    void theBodyAsNtm() {
        assertEquals(4F, lost(4F, 0F, 5F), 1e-6); // the first fails, the second lands whole
        assertEquals(1F, lost(6F, 0F, 5F), 1e-6); // the first lands past the last hit
    }

    @Test
    void theHeadUnderTheLastHitLandsWhole() {
        // 4 x 1.75 = 7 past a hit of 5: NTM's 4 fails and its second lands 4, so the head's lands 7 (before: 7 - 5 = 2)
        assertEquals(7F, lost(4F, 3F, 5F), 1e-6);
        assertEquals(LegacyBullets.Hurt.SECOND, LegacyBullets.hurt(4F, 3F, true, 5F));
        // past the last hit: the first one, with the head
        assertEquals(5.5F, lost(6F, 4.5F, 5F), 1e-6);
    }

    @Test
    void aLimbPastTheLastHitAddsNothing() {
        // 6 x 0.5 = 3 under a hit of 5: NTM's 6 lands 1, so the limb's lands nothing (before: its second one, 3)
        assertEquals(0F, lost(6F, -3F, 5F), 1e-6);
        assertEquals(LegacyBullets.Hurt.NONE, LegacyBullets.hurt(6F, -3F, true, 5F));
        assertEquals(2F, lost(4F, -2F, 5F), 1e-6);
    }

    @Test
    void headBodyLimbInOrder() {
        for (int d = 1; d <= 40; d++)
            for (int l = 0; l <= 40; l++) {
                float damage = d * .5F, last = l * .5F;
                float head = lost(damage, damage * .75F, last), body = lost(damage, 0F, last), limb = lost(damage, -damage * .5F, last);
                assertTrue(head >= body && body >= limb, "damage " + damage + ", last hit " + last + ": " + head + " / " + body + " / " + limb);
            }
    }

    @Test
    void noCooldownTheFirst() {
        assertEquals(LegacyBullets.Hurt.FIRST, LegacyBullets.hurt(4F, 3F, false, 5F));
        assertEquals(LegacyBullets.Hurt.FIRST, LegacyBullets.hurt(4F, 0F, true, 5F));
    }
}
