package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

// The words after the gun of /armsai spawn and /armsai kit: armour by set or by slot, the round, the team, in any
// order; what does not make sense is told in words; what may be typed next.
class AiKitTest {
    private static final List<String> ROUNDS = List.of("r556_sp", "r556_fmj", "r556_jhp", "r556_ap");
    private static final List<String> TEAMS = List.of("red", "blue");

    private static AiKit.Parsed parse(String text) {
        try {
            return AiKit.parse(text, true);
        } catch (AiKit.Bad e) {
            throw new AssertionError(text + ": " + e.getMessage());
        }
    }

    private static String bad(String text, boolean team) {
        return assertThrows(AiKit.Bad.class, () -> AiKit.parse(text, team), text).getMessage();
    }

    @Test
    void nothingTypedIsNoKit() {
        assertEquals(new AiKit.Parsed(null, null, null, null), parse(null));
        assertEquals(new AiKit.Parsed(null, null, null, null), parse("  "));
    }

    @Test
    void aSetIsItsFourPiecesByNtmsNames() {
        assertArrayEquals(new String[] {"hbm:hev_helmet", "hbm:hev_plate", "hbm:hev_legs", "hbm:hev_boots"}, parse("armor hev").armor());
        // the table's name of a set and NTM's own are the same set
        assertArrayEquals(parse("armor security").armor(), parse("armor sec").armor());
        assertArrayEquals(new String[] {"hbm:security_helmet", "hbm:security_plate", "hbm:security_legs", "hbm:security_boots"}, parse("armor sec").armor());
        assertEquals("hbm:steamsuit_plate", parse("armor steam").armor()[1]);
        assertEquals("hbm:dieselsuit_legs", parse("armor diesel").armor()[2]);
        assertEquals("hbm:trenchmaster_boots", parse("armor trench").armor()[3]);
        assertArrayEquals(new String[] {"", "", "", ""}, parse("armor none").armor());
    }

    @Test
    void everyColumnOfTheBalanceTableIsASet() {
        // the columns of notes/balance/work/full_table.json (its "arms")
        for (String set : List.of("none", "steel", "taurun", "titan", "diesel", "t51", "ajr", "hev", "sec", "steam", "bismuth", "bj", "ncrpa", "star", "trench",
                "cmb", "rpa", "schrab", "dns", "fau", "euph")) {
            String[] armor = parse("armor " + set).armor();
            for (int i = 0; i < 4; i++) {
                if (set.equals("none")) assertEquals("", armor[i]);
                else {
                    assertTrue(armor[i].startsWith("hbm:") && armor[i].endsWith(AiKit.SUFFIX[i]), set + ": " + armor[i]);
                    // the mod reads the set back off the piece's name (BalanceArmor.setOf), and has numbers for it
                    String own = armor[i].substring(4, armor[i].length() - AiKit.SUFFIX[i].length());
                    assertTrue(acup.gunfeel.BalanceArmor.knownSet(own), set + " -> " + own);
                }
            }
        }
    }

    @Test
    void piecesBySlotLeaveTheOtherSlotsAsTheyAre() {
        assertArrayEquals(new String[] {null, "hbm:hev_plate", "hbm:steel_legs", null}, parse("armor chest=hbm:hev_plate legs=hbm:steel_legs").armor());
        // a set's name for a piece, none for an empty slot, the other names of the slots
        assertArrayEquals(new String[] {"", "hbm:t51_plate", null, "minecraft:iron_boots"}, parse("armor helmet=none plate=t51 boots=minecraft:iron_boots").armor());
        // after a set they change its pieces
        assertArrayEquals(new String[] {"", "hbm:steel_plate", "hbm:steel_legs", "hbm:hev_boots"}, parse("armor steel head=none feet=hev").armor());
    }

    @Test
    void theWordsGoInAnyOrder() {
        AiKit.Parsed a = parse("armor hev ammo r556_ap team red"), b = parse("team red ammo R556_AP armor hev"), c = parse("ammo r556_ap team red armor hev");
        for (AiKit.Parsed p : List.of(a, b, c)) {
            assertEquals("r556_ap", p.ammo());
            assertEquals("red", p.team());
            assertArrayEquals(a.armor(), p.armor());
        }
        AiKit.Parsed only = parse("ammo m44_ap");
        assertNull(only.armor());
        assertNull(only.team());
        assertEquals("m44_ap", only.ammo());
        // the team's name as typed
        assertEquals("Red", parse("team Red").team());
    }

    @Test
    void aRoleIsOneOfTheRolesOrAuto() {
        assertEquals("base", parse("role base").role());
        assertEquals("rifle", parse("armor hev role Rifle team red").role());
        assertEquals("auto", parse("role auto").role());
        assertNull(parse("ammo m44_ap").role());
        // a role is named with a kit too (/armsai kit), unlike a team
        assertTrue(bad("role nosuch", false).contains("no role 'nosuch'"));
        assertTrue(bad("role nosuch", false).contains("rifle, base"));
        assertTrue(bad("role", true).contains("role needs a role"));
        assertTrue(bad("role base role rifle", true).contains("role is named twice"));
        assertTrue(next("role ").containsAll(List.of("rifle", "base", "auto")));
        assertTrue(next("armor hev ").contains("role"));
        assertFalse(next("role base ").contains("role"));
    }

    @Test
    void whatMakesNoSenseIsToldInWords() {
        assertTrue(bad("armor nosuch", true).contains("unknown armour set 'nosuch'"));
        assertTrue(bad("armor nosuch", true).contains("hev"), "the sets there are, named");
        assertTrue(bad("armor", true).contains("armor needs a set"));
        assertTrue(bad("armor hev steel", true).contains("one set"));
        assertTrue(bad("armor chest=hev hev", true).contains("one set"));
        assertTrue(bad("armor hand=hbm:hev_plate", true).contains("unknown armour slot 'hand'"));
        assertTrue(bad("armor chest=hev plate=steel", true).contains("chest is named twice"));
        assertTrue(bad("armor chest=", true).contains("needs an item"));
        assertTrue(bad("armor chest=nosuch", true).contains("unknown armour set"));
        assertTrue(bad("ammo", true).contains("ammo needs a round"));
        assertTrue(bad("ammo r556_ap r556_sp", true).contains("one word"));
        assertTrue(bad("ammo r556_ap ammo r556_sp", true).contains("ammo is named twice"));
        assertTrue(bad("armor hev armor steel", true).contains("armor is named twice"));
        assertTrue(bad("hev", true).contains("unknown word 'hev'"));
        // /armsai kit names no team
        assertTrue(bad("armor hev team red", false).contains("a team is named when the mobs are spawned"));
        assertFalse(bad("hev", false).contains("team"));
    }

    private static List<String> next(String text) {
        return AiKit.next(text, ROUNDS, TEAMS).words();
    }

    @Test
    void theNextWordIsOffered() {
        // the gun's rounds after ammo, the teams after team
        assertEquals(ROUNDS, next("ammo "));
        assertEquals(ROUNDS, next("armor hev ammo r5"));
        assertEquals(TEAMS, next("team "));
        // sets, none and the slots after armor
        List<String> armor = next("armor ");
        assertTrue(armor.containsAll(List.of("hev", "sec", "security", "none", "head=", "chest=", "legs=", "feet=")));
        assertFalse(armor.contains("ammo"));
        // after a set: the slots, and the words not used yet
        assertEquals(List.of("head=", "chest=", "legs=", "feet=", "ammo", "role", "team"), next("armor hev "));
        assertEquals(List.of("head=", "legs=", "feet=", "ammo", "role", "team"), next("armor chest=hev "));
        // a slot's pieces
        List<String> chest = next("armor chest=h");
        assertTrue(chest.containsAll(List.of("chest=hev", "chest=none")));
        // a value given: the words left
        assertEquals(List.of("armor", "role", "team"), next("ammo r556_ap "));
        assertEquals(List.of("head=", "chest=", "legs=", "feet=", "role", "team"), next("ammo r556_ap armor hev "));
        assertEquals(List.of("role", "team"), next("armor hev ammo r556_ap "));
        assertEquals(List.of("armor", "role"), AiKit.next("ammo r556_ap ", ROUNDS, null).words());
        // where the word being typed starts
        assertEquals(15, AiKit.next("armor hev ammo r5", ROUNDS, TEAMS).from());
        assertEquals(6, AiKit.next("armor ", ROUNDS, TEAMS).from());
    }
}
