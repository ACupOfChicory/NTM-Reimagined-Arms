package acup.gunfeel.gl54;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.Receiver;
import com.hbm.items.weapon.sedna.mags.IMagazine;

// The GL-54's numbers as they stand in design.md: they are read back out of the real GunConfig the item is made with.
class Gl54GunTest {
    static GunConfig config() {
        return Gl54Gun.config();
    }

    @Test
    void durabilityDrawAndDelay() {
        GunConfig c = config();
        assertEquals(800F, c.getDurability(null));
        assertEquals(7, c.getDrawDuration(null));
        Receiver r = c.getReceivers(null)[0];
        assertEquals(14, r.getDelayAfterFire(null));
        assertFalse(r.getRefireOnHold(null), "semi-automatic");
        assertEquals(45F, r.getBaseDamage(null));
    }

    @Test
    void reloadIsFortyTicksAndFiftyWhenEmpty() {
        Receiver r = config().getReceivers(null)[0];
        // the swap is where begin ends; then end; an empty gun is cocked after it (post)
        assertEquals(28, r.getReloadBeginDuration(null));
        assertEquals(12, r.getReloadEndDuration(null));
        assertEquals(0, r.getReloadCockOnEmptyPre(null));
        assertEquals(10, r.getReloadCockOnEmptyPost(null));
        assertEquals(40, r.getReloadBeginDuration(null) + r.getReloadEndDuration(null));
        assertEquals(50, r.getReloadBeginDuration(null) + r.getReloadEndDuration(null) + r.getReloadCockOnEmptyPost(null));
    }

    @Test
    void magazineOfEightTakingTheGrenade() {
        Receiver r = config().getReceivers(null)[0];
        IMagazine<?> mag = r.getMagazine(null);
        assertEquals(8, mag.getCapacity(null));
        assertEquals(8, Gl54Ammo.CAPACITY);
        BulletConfig he = Gl54Ammo.he();
        assertSame(he, Gl54Ammo.he());
        assertEquals(Gl54Grenade.SPEED, he.velocity);
        assertEquals(Gl54Grenade.GRAVITY, he.gravity);
        assertEquals(Gl54Grenade.LIFE, he.expires);
        assertNotNull(he.casing);
        assertNotNull(he.onImpact);
        assertNotNull(he.onUpdate);
    }

    private static void assertSame(Object a, Object b) {
        assertTrue(a == b);
    }

    @Test
    void cameraKickIsNtmsFourDegrees() {
        assertEquals(4F, Gl54Gun.KICK_UP);
        assertEquals(0.5F, Gl54Gun.KICK_UP_SPREAD);
        assertEquals(1F, Gl54Gun.KICK_SIDE_SPREAD);
    }
}
