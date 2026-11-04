package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

// The arm editor has no dead control: in every state of every gun, each control that can be pressed changes
// something (and a change of the gun's settings can be undone), each one that cannot says why, and nothing on
// it speaks English.
class ArmEditorTest {
    // the guns of the jar with any arm setting, and the animations their keys name
    static Map<String, Set<String>> jarGuns() throws Exception {
        var p = new Properties();
        try (var in = ArmEditorTest.class.getResourceAsStream("/ntmgunfeel-guns.properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Map<String, Set<String>> out = new TreeMap<>();
        for (String key : p.stringPropertyNames()) {
            int dot = key.lastIndexOf('.');
            String gun = key.substring(0, dot), k = key.substring(dot + 1);
            if (gun.contains("-") || !(Grips.armKey(k) || k.equals("sameAs"))) continue;
            var names = out.computeIfAbsent(gun, g -> new TreeSet<>(List.of("RELOAD", "INSPECT")));
            if (k.endsWith("Path") || k.endsWith("CarryKeys") || k.endsWith("Hide") || k.endsWith("Adjustments") || k.equals("armLeftKeys"))
                for (String s : p.getProperty(key).split(";")) {
                    String head = s.trim().split("\\s+")[0];
                    if (head.isEmpty()) continue;
                    int slash = head.indexOf('/');
                    names.add((slash < 0 ? head : head.substring(0, slash)).toUpperCase(Locale.ROOT));
                }
        }
        return out;
    }

    // animations long enough for every key the gun has
    static ArmEditor.Clips clips(Collection<String> names) {
        return () -> names.stream().map(n -> new ArmEditor.Clip(n, 4000)).toList();
    }

    @Test
    void everyControlDoesSomethingOrSaysWhyNot() throws Exception {
        var opened = Grips.snapshot();
        int pressed = 0, grey = 0, states = 0;
        Set<String> seen = new TreeSet<>();
        try {
            var guns = jarGuns();
            guns.put("gunfeel:no_animations", Set.of());
            for (var g : guns.entrySet()) {
                var e = new ArmEditor(g.getKey(), clips(g.getValue()));
                List<java.util.function.Consumer<ArmEditor>> setups = new ArrayList<>();
                setups.add(x -> {});
                // an edit made: undo has something; then undone: redo has something
                if (e.sharedWith() != null) {
                    setups.add(x -> assertTrue(x.press("arms.own")));
                    setups.add(x -> {
                        assertTrue(x.press("arms.own"));
                        assertTrue(x.press("undo"));
                    });
                }
                for (var setup : setups) {
                    Grips.restoreUnsaved(opened);
                    var fresh = new ArmEditor(g.getKey(), e.clips);
                    setup.accept(fresh);
                    for (int c = 0; c < Math.max(1, fresh.clipList().size()); c++)
                        for (int h = 0; h < 2; h++)
                            for (var t : ArmEditor.Track.values()) {
                                fresh.clip = c;
                                fresh.hand = h;
                                fresh.track = t;
                                var times = new TreeSet<Float>(List.of(0F, fresh.duration() / 2F, (float) fresh.duration()));
                                times.addAll(fresh.keys(t));
                                for (float time : times) {
                                    fresh.time = time;
                                    for (boolean playing : new boolean[] {false, true}) {
                                        fresh.playing = playing;
                                        states++;
                                        int[] n = check(fresh, g.getKey(), seen);
                                        pressed += n[0];
                                        grey += n[1];
                                    }
                                }
                            }
                }
            }
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
        System.out.println("ARMEDITOR states " + states + " pressed " + pressed + " grey " + grey + " controls " + seen);
        // every control was pressable somewhere
        for (String id : List.of("clip.prev", "clip.next", "play", "speed", "time.start", "time.back", "time.forward", "time.end",
                "hand.right", "hand.left", "track.hide", "track.path", "track.carry", "track.release", "track.around", "track.adjust",
                "key.prev", "key.next", "arms.own", "arms.shared", "undo", "redo", "reset", "close"))
            assertTrue(seen.contains(id), id + " was never pressable");
    }

    // presses each control of one state; returns {pressed, grey}
    private static int[] check(ArmEditor e, String gun, Set<String> seen) {
        var before = e.state();
        var undo = e.undoCopy();
        var redo = e.redoCopy();
        int pressed = 0, grey = 0;
        Set<String> ids = new HashSet<>();
        for (var a : e.actions()) {
            String where = gun + " " + e.clip() + " hand " + e.hand + " " + e.track + " t=" + e.time + " " + a.id();
            assertTrue(ids.add(a.id()), where + ": two controls with one id");
            assertFalse(a.label().isBlank(), where + ": no label");
            assertFalse(a.hint().isBlank(), where + ": no hint");
            noEnglish(a.label(), e, where);
            noEnglish(a.hint(), e, where);
            if (!a.active()) {
                grey++;
                assertFalse(a.why().isBlank(), where + ": grey without a reason");
                noEnglish(a.why(), e, where);
                continue;
            }
            seen.add(a.id());
            a.run().run();
            pressed++;
            var after = e.state();
            assertNotEquals(before, after, where + ": pressing changed nothing");
            if (!after.data().equals(before.data())) {
                // the way back: undo, or for undo itself redo
                String back = a.id().equals("undo") ? "redo" : "undo";
                assertTrue(e.press(back), where + ": a change that cannot be undone");
                assertEquals(before.data(), e.state().data(), where + ": " + back + " did not bring the settings back");
            }
            e.restore(before, undo, redo);
            assertEquals(before, e.state(), where + ": the test could not go back");
        }
        return new int[] {pressed, grey};
    }

    // no Latin letters, apart from names of guns (shown as the game names them)
    private static void noEnglish(String text, ArmEditor e, String where) {
        String t = text;
        String shared = e.sharedWith();
        if (shared != null) t = t.replace(e.clips.gunName(shared), "");
        assertFalse(t.matches("(?s).*[A-Za-z].*"), where + ": English in «" + text + "»");
    }

    // G3's inspect: the left hand holds the magazine there, so a path the old editor wrote for it is never played
    @Test
    void aTrackTheGameNeverPlaysSaysWhatCoversIt() {
        var opened = Grips.snapshot();
        try {
            Grips.put("hbm:gun_g3", "armLeftPath", "INSPECT 0:grip 300:Rifle@0,0,0 600:grip", false);
            var e = new ArmEditor("hbm:gun_g3", clips(List.of("RELOAD", "INSPECT")));
            e.clip = 1;
            e.hand = 1;
            assertEquals(ArmRig.Driver.MAGAZINE, e.driver(300));
            assertEquals(3, e.keys(ArmEditor.Track.PATH).size());
            assertEquals("Всю эту дорожку перекрывает: рука держит магазин (осмотр).", e.trackNote(ArmEditor.Track.PATH));
            e.hand = 0;
            assertNull(e.trackNote(ArmEditor.Track.PATH));
            assertEquals("обходит деталь только левая рука", e.trackNote(ArmEditor.Track.AROUND));
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    // a gun that shares its arms (Heretic with Sexy): own arms copy the source's keys, edits then stay with the
    // gun, undo brings the shared arms back
    @Test
    void ownArmsStopWritingToTheSharedGun() {
        var opened = Grips.snapshot();
        String heretic = "hbm:gun_autoshotgun_heretic", sexy = "hbm:gun_autoshotgun_sexy";
        try {
            var e = new ArmEditor(heretic, clips(List.of("RELOAD")));
            assertEquals(sexy, e.sharedWith());
            String sexyPath = Grips.gunValue(sexy, "armLeftPath");
            assertNotNull(sexyPath);
            assertTrue(e.press("arms.own"));
            assertTrue(Grips.ownArms(heretic));
            Grips.put(heretic, "armLeftHide", "RELOAD 0-100", false);
            assertEquals("RELOAD 0-100", Grips.gunValue(heretic, "armLeftHide"));
            assertNotEquals("RELOAD 0-100", Grips.gunValue(sexy, "armLeftHide"), "the shared gun is untouched");
            assertEquals(sexyPath, Grips.gunValue(heretic, "armLeftPath"));
            assertTrue(e.press("undo"));
            assertFalse(Grips.ownArms(heretic));
            assertTrue(e.press("redo"));
            assertTrue(e.press("arms.shared"));
            assertFalse(Grips.ownArms(heretic));
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }
}
