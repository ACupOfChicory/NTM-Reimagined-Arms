package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

// The key page's track view: what it writes is what the game reads (named moments and the keys written from them,
// what a hand carries, the part it lets go at, the magazine in the inspect).
class ArmTrackTest {
    private static ArmEditor track(String gun, ArmEditor.Track t, String... clips) {
        var e = new ArmEditor(gun, ArmEditorTest.clips(List.of(clips)));
        e.page = ArmEditor.Page.KEY;
        e.keyView = ArmEditor.KeyView.TRACK;
        e.hand = 1;
        e.track = t;
        return e;
    }

    private static float keyAt(String clip, int i) {
        return ArmRig.pathKeys(Config.armLeftPath, Config.armPathTimes).get(clip).get(i).ms();
    }

    // Sexy's left hand puts the magazine back 300 ms after "release": the moment moved, the key goes with it
    @Test
    void aMomentMovesTheKeysWrittenFromIt() {
        var opened = Grips.snapshot();
        String gun = "hbm:gun_autoshotgun_sexy";
        try {
            var e = track(gun, ArmEditor.Track.PATH, "RELOAD");
            assertEquals(ArmEditor.KeyView.TRACK, e.shownView());
            Config.select(gun);
            var before = ArmRig.pathKeys(Config.armLeftPath, Config.armPathTimes).get("RELOAD").stream().map(ArmRig.PathKey::ms).toList();
            assertFalse(e.action("moment.0.delete").active(), "keys are written from it");
            assertTrue(e.press("set.moment.0.plus"));
            Config.select(gun);
            assertEquals("release=2420", Config.armPathTimes);
            var after = ArmRig.pathKeys(Config.armLeftPath, Config.armPathTimes).get("RELOAD").stream().map(ArmRig.PathKey::ms).toList();
            assertTrue(after.contains(2720F), after.toString());
            assertEquals(before.stream().filter(x -> x >= 2410F).count(), after.stream().filter(x -> x >= 2420F).count());
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    @Test
    void aKeyWrittenFromAMomentAndBackStaysWhereItWas() {
        var opened = Grips.snapshot();
        String gun = "gunfeel:key_probe";
        try {
            var e = track(gun, ArmEditor.Track.PATH, "RELOAD");
            e.time = 0F;
            assertTrue(e.press("key.add"));
            e.time = 500F;
            assertTrue(e.press("moment.add"));
            e.time = 700F;
            assertTrue(e.press("key.add"));
            Config.select(gun);
            assertEquals("m1=500", Config.armPathTimes);
            assertTrue(e.press("key.moment.next"));
            Config.select(gun);
            assertTrue(Config.armLeftPath.contains(" m1+200:grip"), Config.armLeftPath);
            assertEquals(700F, keyAt("RELOAD", 1));
            assertTrue(e.press("set.moment.0.plus"));
            Config.select(gun);
            assertEquals(710F, keyAt("RELOAD", 1));
            assertFalse(e.action("moment.0.delete").active());
            e.time = 710F;
            assertTrue(e.press("key.moment.prev"));
            Config.select(gun);
            assertTrue(Config.armLeftPath.contains(" 710:grip"), Config.armLeftPath);
            assertTrue(e.press("moment.0.delete"));
            Config.select(gun);
            assertEquals("", Config.armPathTimes);
            assertEquals(710F, keyAt("RELOAD", 1));
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    @Test
    void theGameCarriesWhatTheTrackViewSet() {
        var opened = Grips.snapshot();
        String gun = "gunfeel:key_probe";
        try {
            var e = track(gun, ArmEditor.Track.CARRY, "RELOAD", "INSPECT");
            assertTrue(e.press("carry.add"));
            assertTrue(e.press("carry.bus.add"));
            assertTrue(e.press("set.carry.at.0.plus"));
            assertTrue(e.press("set.carry.grab.plus"));
            Config.select(gun);
            var c = ArmRig.carry(Config.armLeftCarry);
            assertNotNull(c, Config.armLeftCarry);
            assertEquals("Pump", c.part());
            assertEquals(List.of("ROLL", "SHELL"), List.of(c.buses()));
            assertEquals(0.025F, c.ats()[0][0], 1e-6F);
            assertEquals(10, c.grab());
            assertNull(c.dir());
            e.keyView = ArmEditor.KeyView.HOLD;
            assertTrue(e.press("set.carry.dir.1.plus"));
            Config.select(gun);
            assertArrayEquals(new float[] {0F, 0.05F, -1F}, ArmRig.carry(Config.armLeftCarry).dir(), 1e-6F);
            // a reload of its own: the same at first, then apart from the rest
            e.keyView = ArmEditor.KeyView.TRACK;
            assertTrue(e.press("carry.reload.own"));
            Config.select(gun);
            assertEquals(Config.armLeftCarry, Config.armLeftCarryReload);
            assertTrue(e.press("set.carry.at.2.minus"));
            Config.select(gun);
            assertEquals(-0.025F, ArmRig.carry(Config.armLeftCarryReload).ats()[0][2], 1e-6F);
            assertEquals(0F, ArmRig.carry(Config.armLeftCarry).ats()[0][2]);
            assertTrue(e.press("carry.reload.common"));
            Config.select(gun);
            assertEquals("", Config.armLeftCarryReload);
            // nothing carried in the inspect
            e.clip = 1;
            assertTrue(e.press("carry.delete"));
            Config.select(gun);
            assertEquals("", Config.armLeftCarry);
            assertFalse(new ArmRig.Drive(false).carry);
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    @Test
    void theGameLetsGoAtThePartTheTrackViewSet() {
        var opened = Grips.snapshot();
        String gun = "gunfeel:key_probe";
        try {
            var e = track(gun, ArmEditor.Track.RELEASE, "RELOAD");
            assertEquals(ArmEditor.KeyView.TRACK, e.shownView(), "the release has no keys");
            assertNotNull(e.trackNote(ArmEditor.Track.RELEASE), "it says nothing lets the hand go");
            assertFalse(e.action("set.armReleaseMove.0.plus").active());
            assertTrue(e.press("release.next"));
            Config.select(gun);
            assertEquals("ROLL", Config.armReleaseLeft);
            assertEquals("ROLL", new ArmRig.Drive(false).release);
            assertTrue(e.press("set.armReleaseMove.0.plus"));
            Config.select(gun);
            assertArrayEquals(new float[] {4.1F, 0F, 0F}, Config.armReleaseMove, 1e-6F);
            assertTrue(e.press("release.reset"));
            Config.select(gun);
            assertEquals("", Config.armReleaseLeft);
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    @Test
    void theLeftHandHoldsTheMagazineInTheInspectWhenSwitchedOn() {
        var opened = Grips.snapshot();
        String gun = "gunfeel:key_probe";
        try {
            var e = new ArmEditor(gun, ArmEditorTest.clips(List.of("RELOAD", "INSPECT")));
            e.page = ArmEditor.Page.ARM;
            e.hand = 1;
            assertFalse(e.action("set.armInspectReleaseMs.plus").active());
            assertTrue(e.press("set.armInspectReleaseMs.toggle"));
            Config.select(gun);
            assertEquals(2000F, Config.armInspectReleaseMs);
            assertEquals(ArmRig.Driver.MAGAZINE, new ArmRig.Drive(false).at("INSPECT", 100F, ArmRig.Buses.NONE));
            assertTrue(e.press("set.armInspectReleaseMs.toggle"));
            Config.select(gun);
            assertEquals(-1F, Config.armInspectReleaseMs);
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    // keys written for the reload from an empty magazine alone ("RELOAD/PumpGrip"), edited there, then common again
    @Test
    void keysForTheReloadFromEmptyAloneAreWhatTheGamePlaysThere() {
        var opened = Grips.snapshot();
        String gun = "gunfeel:key_probe";
        try {
            var e = new ArmEditor(gun, ArmEditorTest.clips(List.of("RELOAD"), "", true, true));
            e.page = ArmEditor.Page.KEY;
            e.hand = 1;
            e.track = ArmEditor.Track.PATH;
            e.time = 0F;
            assertTrue(e.press("key.add"));
            e.time = 1000F;
            assertTrue(e.press("key.add"));
            e.keyView = ArmEditor.KeyView.TRACK;
            assertFalse(e.action("key.only.next").active(), "the loaded reload has no part of its own");
            e.clip = 1;
            assertTrue(e.clip().empty());
            assertTrue(e.press("key.only.next"));
            Config.select(gun);
            var keys = ArmRig.pathKeys(Config.armLeftPath, Config.armPathTimes);
            assertEquals(2, keys.get("RELOAD/PumpGrip").size(), Config.armLeftPath);
            assertEquals(2, keys.get("RELOAD").size());
            e.keyView = ArmEditor.KeyView.WHERE;
            e.time = 1000F;
            assertTrue(e.press("set.key.time.plus"));
            Config.select(gun);
            keys = ArmRig.pathKeys(Config.armLeftPath, Config.armPathTimes);
            assertEquals(1010F, ArmRig.forAnimation(keys, "RELOAD", e.buses()).get(1).ms());
            assertEquals(1000F, ArmRig.forAnimation(keys, "RELOAD", ArmRig.Buses.NONE).get(1).ms());
            e.keyView = ArmEditor.KeyView.TRACK;
            assertTrue(e.press("key.only.prev"));
            Config.select(gun);
            assertFalse(Config.armLeftPath.contains("PumpGrip"), Config.armLeftPath);
            assertEquals(1000F, ArmRig.forAnimation(ArmRig.pathKeys(Config.armLeftPath, Config.armPathTimes), "RELOAD", e.buses()).get(1).ms());
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    // a hidden range written for the reload from an empty magazine alone is played there and only there
    @Test
    void aHideForTheEmptyReloadAloneIsPlayedThere() {
        var opened = Grips.snapshot();
        String gun = "gunfeel:key_probe";
        try {
            var e = new ArmEditor(gun, ArmEditorTest.clips(List.of("RELOAD"), "", true, true));
            e.page = ArmEditor.Page.KEY;
            e.hand = 1;
            e.track = ArmEditor.Track.HIDE;
            e.clip = 1;
            e.time = 100F;
            assertTrue(e.press("key.add"));
            e.keyView = ArmEditor.KeyView.TRACK;
            assertTrue(e.press("key.only.next"));
            Config.select(gun);
            assertTrue(Config.armLeftHide.contains("RELOAD/PumpGrip"), Config.armLeftHide);
            // the copy is played from an empty magazine: its end moved, the loaded reload's stays
            e.keyView = ArmEditor.KeyView.WHERE;
            assertTrue(e.press("set.key.to.plus"));
            Config.select(gun);
            var hidden = ArmRig.ranges(Config.armLeftHide);
            assertEquals(610F, ArmRig.forAnimation(hidden, "RELOAD", e.buses())[1]);
            assertEquals(600F, ArmRig.forAnimation(hidden, "RELOAD", ArmRig.Buses.NONE)[1]);
            var drive = new ArmRig.Drive(false);
            assertEquals(ArmRig.Driver.HIDDEN, drive.at("RELOAD", 605F, e.buses()));
            assertNotEquals(ArmRig.Driver.HIDDEN, drive.at("RELOAD", 605F, ArmRig.Buses.NONE));
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    // G3's left hand has adjustment keys at 0 and 3.935 ms: the first one's "+" moves it as far as it can go
    @Test
    void aLimitOffTheStepIsReached() {
        var opened = Grips.snapshot();
        String gun = "hbm:gun_g3";
        try {
            var e = new ArmEditor(gun, ArmEditorTest.clips(List.of("RELOAD")));
            e.page = ArmEditor.Page.KEY;
            e.hand = 1;
            e.track = ArmEditor.Track.ADJUST;
            e.time = 0F;
            assertTrue(e.press("set.key.time.plus"));
            Config.select(gun);
            var keys = ArmRig.adjustments(Config.armLeftAdjustments).get("RELOAD").keySet();
            assertTrue(keys.contains(2.935F), keys.toString());
            assertFalse(e.action("set.key.time.plus").active(), "the next key is right after it");
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }
}
