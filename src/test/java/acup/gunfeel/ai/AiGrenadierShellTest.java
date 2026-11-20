package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.host.Shell;

class AiGrenadierShellTest {
    @Test void loadedShellWritesEveryNumberAndRefreshesTheSameHolder() {
        Shell into = new Shell();
        BulletConfig bullet = new BulletConfig();
        bullet.velocity = 5.123456F;
        bullet.gravity = 0.123456789012345;
        bullet.expires = 137;
        assertTrue(AiGrenadier.shellNumbers(bullet, into));
        assertEquals(bullet.velocity, into.velocity);
        assertEquals(bullet.gravity, into.gravity);
        assertEquals(bullet.expires, into.expires);
        bullet.velocity = -0.0F;
        bullet.gravity = -0.0;
        bullet.expires = -1;
        assertTrue(AiGrenadier.shellNumbers(bullet, into));
        assertEquals(bullet.velocity, into.velocity);
        assertEquals(bullet.gravity, into.gravity);
        assertEquals(bullet.expires, into.expires);
    }

    @Test void missingShellReturnsFalseAndLeavesNumbersUntouched() {
        Shell into = new Shell();
        into.velocity = 5.123456F;
        into.gravity = 0.123456789012345;
        into.expires = 137;
        assertFalse(AiGrenadier.shellNumbers(null, into));
        assertEquals(5.123456F, into.velocity);
        assertEquals(0.123456789012345, into.gravity);
        assertEquals(137, into.expires);
    }
}
