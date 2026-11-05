package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

// The arm editor's key page: the keys it edits are the ones the game plays, a key added and deleted leaves the gun's
// text as it was, a key written from a named moment stays written from it, and the game reads each edit.
class ArmKeysTest {
    static final ArmEditor.Track[] KEYED = {ArmEditor.Track.HIDE, ArmEditor.Track.PATH, ArmEditor.Track.CARRY, ArmEditor.Track.AROUND,
            ArmEditor.Track.ADJUST};

    private static List<Float> times(ArmKeys k) {
        var out = new ArrayList<Float>();
        for (var key : k.keys()) {
            out.add(key.ms());
            if (k.track == ArmEditor.Track.HIDE) out.add(key.to());
        }
        Collections.sort(out);
        return out;
    }

    @Test
    void theKeysAreTheOnesTheGamePlays() throws Exception {
        int compared = 0;
        try {
            for (var g : ArmEditorTest.jarGuns().entrySet()) {
                var e = new ArmEditor(g.getKey(), ArmEditorTest.clips(g.getValue()));
                for (int c = 0; c < e.clipList().size(); c++)
                    for (int h = 0; h < 2; h++)
                        for (var t : KEYED) {
                            e.clip = c;
                            e.hand = h;
                            var k = e.keysOf(t);
                            if (k == null) continue;
                            assertEquals(e.keys(t), times(k), g.getKey() + " " + e.clip() + " hand " + h + " " + t);
                            compared += k.keys().size();
                        }
            }
        } finally {
            Config.select(null);
        }
        assertTrue(compared > 100, "keys compared: " + compared);
    }

    @Test
    void aKeyAddedAndDeletedLeavesTheTextAsItWas() throws Exception {
        var opened = Grips.snapshot();
        int tried = 0;
        try {
            for (var g : ArmEditorTest.jarGuns().entrySet()) {
                var e = new ArmEditor(g.getKey(), ArmEditorTest.clips(g.getValue()));
                e.page = ArmEditor.Page.KEY;
                for (int c = 0; c < e.clipList().size(); c++)
                    for (int h = 0; h < 2; h++)
                        for (var t : KEYED) {
                            e.clip = c;
                            e.hand = h;
                            e.track = t;
                            // a time no key of the jar sits on
                            e.time = 5F;
                            var before = Grips.snapshot();
                            if (!e.press("key.add")) continue;
                            assertNotEquals(before, Grips.snapshot());
                            assertTrue(e.press("key.delete"), g.getKey() + " " + t);
                            assertEquals(before, Grips.snapshot(), g.getKey() + " " + e.clip() + " hand " + h + " " + t + ": not as it was");
                            tried++;
                        }
            }
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
        assertTrue(tried > 100, "tried: " + tried);
    }

    // Sexy's left hand puts the magazine back 300 ms after the moment "release"
    @Test
    void aKeyFromANamedMomentStaysFromIt() {
        var opened = Grips.snapshot();
        String gun = "hbm:gun_autoshotgun_sexy";
        try {
            var e = new ArmEditor(gun, ArmEditorTest.clips(List.of("RELOAD")));
            e.page = ArmEditor.Page.KEY;
            e.track = ArmEditor.Track.PATH;
            e.hand = 1;
            e.time = 2710F;
            assertTrue(e.press("set.key.time.plus"));
            assertEquals(2720F, e.time);
            Config.select(gun);
            assertTrue(Config.armLeftPath.contains(" release+310:Gun@"), Config.armLeftPath);
            assertTrue(Config.armLeftPath.contains(" release:Magazine@"), "the key at the moment itself stays");
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    @Test
    void theGameReadsWhatTheKeyPageWrote() {
        var opened = Grips.snapshot();
        String gun = "gunfeel:key_probe";
        try {
            var e = new ArmEditor(gun, ArmEditorTest.clips(List.of("RELOAD")));
            e.page = ArmEditor.Page.KEY;
            e.hand = 1;
            // hidden from 100 ms for half a second
            e.track = ArmEditor.Track.HIDE;
            e.time = 100F;
            assertTrue(e.press("key.add"));
            Config.select(gun);
            assertEquals(ArmRig.Driver.HIDDEN, new ArmRig.Drive(false).at("RELOAD", 150F, ArmRig.Buses.NONE));
            assertNotEquals(ArmRig.Driver.HIDDEN, new ArmRig.Drive(false).at("RELOAD", 700F, ArmRig.Buses.NONE));
            assertTrue(e.press("key.delete"));
            // a path: the grip at 0, the pump at 1000 a little to the side
            e.track = ArmEditor.Track.PATH;
            e.time = 0F;
            assertTrue(e.press("key.add"));
            e.time = 1000F;
            assertTrue(e.press("key.add"));
            assertTrue(e.press("key.part.next"));
            assertTrue(e.press("set.key.point.0.plus"));
            Config.select(gun);
            var keys = ArmRig.pathKeys(Config.armLeftPath, Config.armPathTimes).get("RELOAD");
            assertEquals(2, keys.size(), Config.armLeftPath);
            assertNull(keys.get(0).part());
            assertEquals("Pump", keys.get(1).part());
            assertEquals(0.025F, keys.get(1).at()[0], 1e-6F);
            assertEquals(ArmRig.Driver.PATH, new ArmRig.Drive(false).at("RELOAD", 1000F, ArmRig.Buses.NONE));
            // a key does not go past its neighbour
            e.slide("key.time", 0F, true);
            assertEquals(1F, e.time);
            Config.select(gun);
            assertEquals(1F, ArmRig.pathKeys(Config.armLeftPath, Config.armPathTimes).get("RELOAD").get(1).ms());
            // carry keys: one added between two is played in its place
            e.track = ArmEditor.Track.CARRY;
            for (float t : new float[] {200F, 800F, 500F}) {
                e.time = t;
                assertTrue(e.press("key.add"));
            }
            Config.select(gun);
            var carry = ArmRig.carryKeys(Config.armLeftCarryKeys).get("RELOAD");
            assertEquals(List.of(200F, 500F, 800F), carry.stream().map(k -> k[0]).toList(), Config.armLeftCarryKeys);
            // the left hand around its part, and a nudge of the hand
            e.track = ArmEditor.Track.AROUND;
            e.time = 300F;
            assertTrue(e.press("key.add"));
            assertTrue(e.press("set.key.angle.plus"));
            Config.select(gun);
            assertArrayEquals(new float[] {300F, 1F}, ArmRig.angleKeys(Config.armLeftKeys).get("RELOAD"));
            e.track = ArmEditor.Track.ADJUST;
            e.time = 0F;
            assertTrue(e.press("key.add"));
            assertTrue(e.press("set.key.adjust.0.plus"));
            Config.select(gun);
            assertEquals(0.1F, ArmRig.adjustments(Config.armLeftAdjustments).get("RELOAD").get(0F)[0], 1e-6F);
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }
}
