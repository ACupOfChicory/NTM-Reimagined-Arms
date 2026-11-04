package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.*;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

// The arm editor's File page and copies: a gun's export holds its arms as the jar has them, loading it on a gun with
// no arms gives back the same hands, a file of another gun or with a broken value is not loaded, arms taken from
// another gun and keys pasted are what the game then reads.
class ArmFilesTest {
    static final LocalDateTime WHEN = LocalDateTime.of(2026, 11, 5, 3, 0, 0);

    // a folder of the test's own, one time for every export
    private static void freshFolder() throws java.io.IOException {
        ArmFiles.dir = Files.createTempDirectory("arm-files");
        ArmEditor.clock = () -> WHEN;
    }

    // the jar's guns, with the guns that share another's arms
    private static Set<String> guns() throws Exception {
        var out = new TreeSet<>(ArmEditorTest.jarGuns().keySet());
        for (String k : jar().stringPropertyNames()) if (k.endsWith(".sameAs")) out.add(k.substring(0, k.length() - 7));
        return out;
    }

    private static Properties jar() throws Exception {
        var p = new Properties();
        try (var in = ArmFilesTest.class.getResourceAsStream("/ntmgunfeel-guns.properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    // what the game reads of a gun's arms: every arm field of Config, the grips of the gun and its variants
    private static String hands(String gun, Collection<String> variants) throws Exception {
        Config.select(gun);
        var sb = new StringBuilder();
        for (var f : Config.class.getFields()) {
            if (!f.getName().startsWith("arm") || !java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            Object v = f.get(null);
            sb.append(f.getName()).append('=').append(v instanceof float[] a ? Arrays.toString(a) : String.valueOf(v)).append('\n');
        }
        var id = Identifier.parse(gun);
        for (String m : variants) {
            var g = Grips.get(id, m);
            sb.append("grip").append(m).append('=').append(Arrays.toString(g.right())).append(Arrays.toString(g.left())).append(Arrays.toString(g.rightAt()))
                    .append(Arrays.toString(g.leftAt())).append('\n');
        }
        return sb.toString();
    }

    private static List<String> variants(String gun) throws Exception {
        var out = new ArrayList<>(List.of(""));
        String t = Grips.armsGun(gun);
        for (String k : jar().stringPropertyNames())
            if (k.startsWith(t + "-")) out.add(k.substring(t.length(), k.lastIndexOf('.')));
        return out;
    }

    @Test
    void aGunsExportLoadedOnTheGunWithNoArmsGivesTheSameHands() throws Exception {
        var opened = Grips.snapshot();
        int guns = 0;
        try {
            for (String gun : guns()) {
                var v = variants(gun);
                String before = hands(gun, v);
                String t = Grips.armsGun(gun);
                String text = ArmFiles.text(t, t, gun, "X0-dev", WHEN, Grips.armsOf(gun));
                var read = ArmFiles.read(text);
                assertNull(read.problem(), gun + ": " + read.problem());
                assertEquals(t, read.gun());
                Grips.putArms(gun, Map.of());
                assertTrue(Grips.armsOf(gun).values().stream().allMatch(String::isEmpty), gun + ": arms left after wiping: " + Grips.armsOf(gun));
                Grips.putArms(gun, read.arms());
                assertEquals(before, hands(gun, v), gun + ": not the same hands after the export was loaded");
                assertEquals(opened, Grips.snapshot(), gun + ": loading its own export left something of the player's");
                guns++;
            }
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
        assertTrue(guns > 40, "guns: " + guns);
    }

    // a gun that shares another's settings plays that gun's hands unless the jar gives it arms of its own; an arm key
    // written the same as the shared gun's (Zebra's grips, a copy of G3's) gives it none
    @Test
    void aSharedGunWithNoArmsOfItsOwnPlaysTheSharedHands() throws Exception {
        int shared = 0;
        var p = jar();
        try {
            for (String gun : guns()) {
                String to = Grips.sharedWith(gun);
                if (to == null) continue;
                // read from the jar here, not from Grips: an arm key of the gun whose value is not the shared gun's
                boolean own = false;
                for (String k : p.stringPropertyNames())
                    if ((k.startsWith(gun + ".") || k.startsWith(gun + "-")) && Grips.armKey(k.substring(k.lastIndexOf('.') + 1)))
                        own |= !p.getProperty(k).trim().equals(p.getProperty(to + k.substring(gun.length()), "").trim());
                assertEquals(own, Grips.ownArmsShipped(gun), gun);
                if (own) continue;
                var v = variants(to);
                assertEquals(hands(to, v), hands(gun, v), gun + " does not play the hands of " + to);
                shared++;
            }
        } finally {
            Config.select(null);
        }
        assertTrue(shared >= 8, "guns: " + shared);
    }

    // what merge_arms.py would put into the jar for a gun with arms of its own is what is there now
    @Test
    void theExportOfAJarGunIsItsArmKeysInTheJar() throws Exception {
        var p = jar();
        for (String gun : ArmEditorTest.jarGuns().keySet()) {
            if (Grips.sharedWith(gun) != null) continue;
            Map<String, String> want = new TreeMap<>();
            for (String k : p.stringPropertyNames()) {
                if (!k.startsWith(gun + ".") && !k.startsWith(gun + "-")) continue;
                String name = k.substring(k.lastIndexOf('.') + 1), value = p.getProperty(k).trim();
                if (Grips.armKey(name) && !value.isEmpty()) want.put(k, value);
            }
            assertEquals(want, Grips.armsOf(gun), gun);
        }
    }

    @Test
    void aFileOfAnotherGunOrWithABrokenValueIsNotLoaded() {
        String sexy = "hbm:gun_autoshotgun_sexy";
        var arms = Grips.armsOf(sexy);
        String good = ArmFiles.text(sexy, "Sexy", sexy, "X0-dev", WHEN, arms);
        assertNull(ArmFiles.read(good).problem());
        assertEquals(arms, ArmFiles.read(good).arms());
        assertNotNull(ArmFiles.read(good.replace("# gun: " + sexy + "\n", "")).problem());
        var bad = ArmFiles.read(good + "hbm\\:gun_g3.armLeftPath = RELOAD 0:grip\n");
        assertTrue(bad.problem().contains("другого оружия"), bad.problem());
        bad = ArmFiles.read(ArmFiles.text(sexy, "Sexy", sexy, "X0-dev", WHEN, Map.of(sexy + ".armRollLeft", "x")));
        assertEquals("строка 7: не число", bad.problem());
        assertNotNull(ArmFiles.read(good + "hbm\\:gun_autoshotgun_sexy.armLeftPath = RELOAD nope:grip\n").problem());
        assertNotNull(ArmFiles.read(good + "hbm\\:gun_autoshotgun_sexy.kickPreset = smg\n").problem(), "not an arm setting");
        assertNotNull(ArmFiles.read(good + "hbm\\:gun_autoshotgun_sexy.leftAt = 1,2\n").problem());
        // a gun without some of its parts: the game reads only its grips
        assertNotNull(ArmFiles.read(good + "hbm\\:gun_autoshotgun_sexy-Barrel.armRollLeft = 42\n").problem());
        assertNull(ArmFiles.read(good + "hbm\\:gun_autoshotgun_sexy-Barrel.leftAt = 1,2,3\n").problem());
    }

    // the File page: an export is written with its head and put on the clipboard; an older file loads back
    @Test
    void theFilePageExportsAndLoadsBack() throws Exception {
        var opened = Grips.snapshot();
        String gun = "hbm:gun_autoshotgun_sexy";
        freshFolder();
        try {
            var e = new ArmEditor(gun, ArmEditorTest.clips(List.of("RELOAD")));
            e.page = ArmEditor.Page.FILE;
            assertTrue(e.press("export.write"));
            String text = e.takeClipboard();
            assertNotNull(text);
            assertNull(e.takeClipboard(), "taken once");
            assertTrue(text.contains("# gun: " + gun + "\n") && text.contains("# date: 2026-11-05 03:00:00\n"), text);
            var files = ArmFiles.files(gun);
            assertEquals(1, files.size());
            assertEquals(text, Files.readString(files.get(0).path()));
            assertFalse(e.action("load.file").active(), "the arms are as in the file");
            e.page = ArmEditor.Page.ARM;
            assertTrue(e.press("set.armRollLeft.plus"));
            e.page = ArmEditor.Page.FILE;
            assertTrue(e.press("load.file"));
            assertEquals(opened, Grips.snapshot());
            assertTrue(e.press("undo"));
            assertNotEquals(opened, Grips.snapshot());
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    // "Взять руки с": Amat's arms on the Subtlety, as the game then reads them; undo gives the Subtlety's back
    @Test
    void armsTakenFromAnotherGunAreTheOnesTheGameReads() throws Exception {
        var opened = Grips.snapshot();
        String gun = "gunfeel:key_probe", from = "hbm:gun_autoshotgun_sexy";
        try {
            var e = new ArmEditor(gun, ArmEditorTest.clips(List.of("RELOAD")));
            e.page = ArmEditor.Page.FILE;
            e.from = e.sources().indexOf(from);
            assertTrue(e.from >= 0);
            String want = hands(from, List.of(""));
            assertTrue(e.press("copy.from.take"));
            assertEquals(want, hands(gun, List.of("")));
            assertFalse(e.action("copy.from.take").active(), "already the same");
            assertTrue(e.press("undo"));
            assertEquals(opened, Grips.snapshot());
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    // G3's arms on the Mare's Leg, whose sawn-off has a grip of its own: every variant of either holds as on the G3
    @Test
    void armsTakenOnAGunWithVariantsHoldAsTheOthersVariants() throws Exception {
        var opened = Grips.snapshot();
        String gun = "hbm:gun_maresleg", from = "hbm:gun_g3";
        try {
            var v = new ArrayList<>(variants(gun));
            for (String m : variants(from)) if (!v.contains(m)) v.add(m);
            assertTrue(v.contains("-Stock-Barrel"), v.toString());
            var e = new ArmEditor(gun, ArmEditorTest.clips(List.of("RELOAD")));
            e.page = ArmEditor.Page.FILE;
            e.from = e.sources().indexOf(from);
            assertTrue(e.press("copy.from.take"));
            assertEquals(hands(from, v), hands(gun, v));
            // and the export of them, loaded, is the same hands
            String want = hands(gun, v);
            var read = ArmFiles.read(ArmFiles.text(gun, gun, gun, "X0-dev", WHEN, Grips.armsOf(gun)));
            assertNull(read.problem());
            Grips.putArms(gun, Map.of());
            Grips.putArms(gun, read.arms());
            assertEquals(want, hands(gun, v));
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    // a hidden range shorter than a step keeps its length when pasted
    @Test
    void aPastedShortHideKeepsItsLength() {
        var k = new ArmKeys(ArmEditor.Track.HIDE, "RELOAD 100-105", "RELOAD", ArmRig.Buses.NONE, Map.of());
        var c = k.copy(k.keys().get(0));
        k.paste(c, 500F, 3000F);
        assertEquals("RELOAD 100-105 500-505", k.write());
    }

    // a key of Sexy's left hand pasted to the right hand of another gun: the same key at the time it was pasted
    @Test
    void aPastedKeyIsTheCopiedOneAtItsNewTime() {
        var opened = Grips.snapshot();
        try {
            var sexy = new ArmEditor("hbm:gun_autoshotgun_sexy", ArmEditorTest.clips(List.of("RELOAD")));
            sexy.page = ArmEditor.Page.KEY;
            sexy.track = ArmEditor.Track.PATH;
            sexy.hand = 1;
            sexy.time = 2410F;
            assertTrue(sexy.press("key.copy"));
            assertFalse(sexy.action("key.copy").active());
            var k = ArmRig.pathKeys(Config.armLeftPath, Config.armPathTimes).get("RELOAD");
            var copied = k.stream().filter(x -> x.ms() == 2410F).findFirst().orElseThrow();
            var e = new ArmEditor("gunfeel:key_probe", ArmEditorTest.clips(List.of("RELOAD")));
            e.page = ArmEditor.Page.KEY;
            e.track = ArmEditor.Track.PATH;
            e.hand = 0;
            e.time = 500F;
            assertTrue(e.press("key.paste"));
            Config.select(e.gun);
            var right = ArmRig.pathKeys(Config.armRightPath, Config.armPathTimes).get("RELOAD");
            assertEquals(1, right.size(), Config.armRightPath);
            assertEquals(500F, right.get(0).ms());
            assertEquals(copied.part(), right.get(0).part());
            assertArrayEquals(copied.at(), right.get(0).at());
            assertFalse(e.action("key.paste").active(), "the same key is there");
            e.track = ArmEditor.Track.CARRY;
            assertFalse(e.action("key.paste").active(), "a path key on the carry track");
        } finally {
            ArmEditor.copied = null;
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }
}
