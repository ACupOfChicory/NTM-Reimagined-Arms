package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/** The balance's one switch (owner 2026-11-13): balance = arms | ntm in the server's file, every number fixed in the mod. */
class BalanceKeysTest {
    private static Properties file() throws Exception {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(Path.of("src/main/resources/ntmgunfeel-server.properties"))) {
            p.load(r);
        }
        return p;
    }

    @Test
    void theFileHoldsOnlyTheSwitch() throws Exception {
        Properties p = file();
        assertEquals("arms", p.getProperty("balance"));
        for (String k : p.stringPropertyNames()) assertFalse(BalanceKeys.is(k), "a key of the balance in the server's file: " + k);
        // the keys of the mod's own that stay in the file: not the balance's
        for (String k : new String[] {"balance", "spreadAim", "spreadMove", "damageCap", "blastParts", "modelHitboxes", "gunMechanismRecipes",
                "cameraScaleMin", "hbm:gun_maresleg.spreadAim", "hbm:gun_maresleg.sprintFireTime"})
            assertFalse(BalanceKeys.is(k), k);
    }

    @Test
    void theBalancesKeys() {
        // 104 balance* (117 up to X977, 13 old lines gone), 20 of the choke, 24 of the power armour, 5 of the abilities
        assertEquals(153, BalanceKeys.ARMS.size());
        for (String k : BalanceKeys.ARMS.keySet()) assertTrue(BalanceKeys.is(k), k);
        for (String k : BalanceKeys.NTM.keySet()) assertTrue(BalanceKeys.ARMS.containsKey(k), k);
        // the keys of no line: the readers' own defaults, never the file's
        for (String k : new String[] {"balanceTau.hev", "balance.final.pairTier.g3.r556_sp", "balance.final.armor.hev", "balance.immune.dns",
                "hbm:gun_g3.powerArmorSpread", "hbm:gun_g3.spreadAimKnee"})
            assertTrue(BalanceKeys.is(k), k);
    }

    @Test
    void armsIsX977() throws Exception {
        // the 153 keys as the server's file had them by default at X977 (147ef53), "key=value\n" sorted: the game reads them bit for bit.
        // Not here: the 13 switches whose old lines were gone (balanceIrBeams, balanceBlastFloorShare, balanceBulletBlastCap,
        // balanceGunUnder, balanceArmorFloor, balance.final.tesla.ir, .explosive, .character.few, .hits, .table.pairs, .range.shells,
        // .pools.gas, balance.immune.reloadHold): what they had on is fixed in the code
        StringBuilder s = new StringBuilder();
        new java.util.TreeMap<>(BalanceKeys.ARMS).forEach((k, v) -> s.append(k).append('=').append(v).append('\n'));
        assertEquals("3043e90283f80b63e3c28fce3bea6d1d743d2fccb89e7bfc31d70f71e298c7f5",
                PinnedTest.sha256(s.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void armsAndNtm() {
        assertEquals("true", BalanceKeys.value("balance.final", false));
        assertEquals("186", BalanceKeys.value("balanceS.dns", false));
        assertEquals("0.4", BalanceKeys.value("powerArmorRecoil.fau", false));
        assertEquals("0.015", BalanceKeys.value("hbm:gun_spas12.spreadHipKnee", false));
        assertNull(BalanceKeys.value("balanceTau.hev", false));
        // ntm: the balance's switches off, no bonus of the power armour, no choke, NTM's abilities
        for (String k : new String[] {"balanceArmor", "balanceGuns", "balance.final", "balanceRecipes", "balance.shield", "balance.medx", "balance.superStimpak",
                "balance.inserts", "balance.vanilla0", "abilities.mobilityCap", "abilities.bismuth"})
            assertEquals("false", BalanceKeys.value(k, true), k);
        assertEquals("ntm", BalanceKeys.value("balance.immune.mode", true));
        assertEquals("ntm", BalanceKeys.value("balance.immune.dns.mode", true));
        assertEquals("formula", BalanceKeys.value("balance.immune.fau.mode", true));
        assertEquals("0", BalanceKeys.value("abilities.strengthCap", true));
        assertEquals("all", BalanceKeys.value("abilities.esp.players", true));
        assertEquals("1", BalanceKeys.value("powerArmorRecoil.fau", true));
        assertEquals("1", BalanceKeys.value("powerArmorSpread", true));
        assertEquals("1", BalanceKeys.value("hbm:gun_g3.powerArmorSpread", true));
        assertEquals("0", BalanceKeys.value("hbm:gun_spas12.spreadHipKnee", true));
        assertEquals("0", BalanceKeys.value("spreadAimKnee", true));
        // the numbers under the switches stay the mod's
        assertEquals("186", BalanceKeys.value("balanceS.dns", true));
        assertTrue(BalanceKeys.ntm(" NTM "));
        assertFalse(BalanceKeys.ntm("arms"));
        assertFalse(BalanceKeys.ntm(null));
    }

    @Test
    void theGameReadsTheMods() {
        // no file here (no game): the jar's switch, arms
        assertFalse(BalanceKeys.dev());
        assertEquals(186F, ReaimTimes.number("balanceS.dns", 0F));
        assertEquals(0.4F, ReaimTimes.number("powerArmorRecoil.fau", 1F));
        assertEquals("autoshotgun_shredder:beam", ReaimTimes.text("balance.final.range.lineOf", ""));
        assertTrue(ReaimTimes.flag("balance.final", false));
        assertEquals(7F, ReaimTimes.number("balanceTau.nothing", 7F));
        assertFalse(ReaimTimes.inFile("balance.final"));
    }
}
