package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import acup.gunfeel.EuphImmunityBase.Pool;
// Stage 2 of the balance: the kit's shipped keys; the kit's numbers are BalanceKitBaseTest's and euphemium's pool
// EuphImmunityBaseTest's (core).
class BalanceKitTest {
    @Test
    void shippedKeys() throws IOException {
        Properties p = SpreadModelTest.shipped();
        assertEquals("true", p.getProperty("balance.shield"));
        assertEquals("10", p.getProperty("balance.shield.mod"));
        assertEquals("2", p.getProperty("balance.shield.flask"));
        assertEquals("16", p.getProperty("balance.shield.cap"));
        assertEquals("100", p.getProperty("balance.shield.delay"));
        assertEquals("0.002", p.getProperty("balance.shield.rate"));
        assertEquals("true", p.getProperty("balance.medx"));
        assertEquals("true", p.getProperty("balance.superStimpak"));
        assertEquals("true", p.getProperty("balance.inserts"));
        assertEquals("0.9", p.getProperty("balance.inserts.bullets"));
        assertEquals("true", p.getProperty("balance.vanilla0"));
        assertEquals("pool", p.getProperty("balance.immune.mode"));
        assertEquals("150", p.getProperty("balance.immune.pool"));
        assertEquals("15", p.getProperty("balance.immune.window"));
        assertEquals("60", p.getProperty("balance.immune.recover"));
        assertEquals("10", p.getProperty("balance.immune.hold"));
        // balance.immune.refillPause 5 s, as X875 (Henry and Lincoln: their reload's hold)
        assertEquals("5", p.getProperty("balance.immune.refillPause"));
        assertEquals(EuphImmunity.REFILL_PAUSE, EuphImmunity.refillPause());
        assertEquals(200, Pool.DEFAULT.hold());
        assertEquals("pool", p.getProperty("balance.immune.dns.mode"));
        assertEquals("45", p.getProperty("balance.immune.dns.pool"));
        assertEquals("15", p.getProperty("balance.immune.dns.window"));
        assertEquals("60", p.getProperty("balance.immune.dns.recover"));
        assertEquals("pool", p.getProperty("balance.immune.fau.mode"));
        assertEquals("40", p.getProperty("balance.immune.fau.pool"));
        assertEquals("15", p.getProperty("balance.immune.fau.window"));
        assertEquals("60", p.getProperty("balance.immune.fau.recover"));
        assertEquals(null, p.getProperty("balance.immune.dns"), "the old DNS switch is balance.immune.dns.mode now");
        // the kits' defaults are the shipped keys' and their drain sets the table's
        assertEquals(45F, EuphImmunity.Kit.DNS.size);
        assertEquals(40F, EuphImmunity.Kit.FAU.size);
        assertEquals(186F, BalanceArmor.SETS.get(EuphImmunity.Kit.DNS.set).s());
        assertEquals(168F, BalanceArmor.SETS.get(EuphImmunity.Kit.FAU.set).s());
    }
}
