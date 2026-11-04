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
        return clips(names, "", true);
    }

    // a stand-in model: a box 4 x 4 x 20, fractions as shares of it; as the real model, it has no section beyond its ends
    // (GunGeometry.fractions then gives the middle and the bottom); two parts a hand can move with
    static final Map<String, int[]> MOVES = Map.of("SHELL", new int[] {200, 800}, "ROLL", new int[] {1000, 2000});

    static ArmEditor.Clips clips(Collection<String> names, String variant, boolean measured) {
        var list = names.stream().map(n -> new ArmEditor.Clip(n, 4000)).toList();
        ArmEditor.Shape box = new ArmEditor.Shape() {
            public float[] min() {
                return new float[] {-2, -2, -10};
            }

            public float[] max() {
                return new float[] {2, 2, 10};
            }

            public float[] fractions(float[] p) {
                float z = (p[2] + 10) / 20;
                return z < 0 || z > 1 ? new float[] {0.5F, 0F, z} : new float[] {(p[0] + 2) / 4, (p[1] + 2) / 4, z};
            }

            public float[] point(float[] f) {
                return new float[] {f[0] * 4 - 2, f[1] * 4 - 2, f[2] * 20 - 10};
            }
        };
        return new ArmEditor.Clips() {
            public List<ArmEditor.Clip> list() {
                return list;
            }

            public ArmEditor.Shape shape() {
                return measured ? box : null;
            }

            public String variant() {
                return variant;
            }

            public List<String> parts() {
                return List.of("Pump", "Lever");
            }

            // two parts the animation moves: a shell early, a turn of the gun later
            public Map<String, int[]> moves(String clip) {
                return MOVES;
            }
        };
    }

    // guns seen as a variant too (the parts they do not draw), and without a measured model
    static final Map<String, String> VARIANTS = Map.of("hbm:gun_double_barrel", "-Barrel", "hbm:gun_maresleg", "-Stock-Barrel",
            "hbm:gun_autoshotgun_heretic", "-Barrel", "hbm:gun_g3", "-Mag_Paddle-Scope-Silencer");

    // presses the action when it can be pressed
    private static void tap(ArmEditor e, String id) {
        var a = e.action(id);
        if (a != null && a.active()) a.run().run();
    }

    // every setting of the gun made its own: lengths and bases 0 first (the thickness that grows with the arm), then
    // each number a step, each part and switch once, each variant its own grip
    // each number of the page a step (down, else up)
    private static void stepAll(ArmEditor e) {
        for (var a : e.actions()) {
            String id = a.id();
            if (!id.startsWith("set.") || id.contains("armBase") || id.contains("armLength") || !id.endsWith(".minus")) continue;
            String plus = id.substring(0, id.length() - 6) + ".plus";
            if (a.active()) a.run().run();
            else tap(e, plus);
        }
    }

    private static void ownEverything(ArmEditor e) {
        for (String k : List.of("armBaseRight", "armBaseLeft", "armLength")) Grips.put(e.gun, k, "0", false);
        Config.select(e.gun);
        for (var p : List.of(ArmEditor.Page.GRIP, ArmEditor.Page.ARM, ArmEditor.Page.BOTH))
            for (int h = 0; h < 2; h++) {
                e.page = p;
                e.hand = h;
                stepAll(e);
                tap(e, "grip.part.next");
                tap(e, "grip.variant.own");
                tap(e, "set.armSlideNearest.toggle");
                tap(e, "set.armSlide.toggle");
            }
        // the key page's track settings: a moment of its own, something carried (a second part too, its own on the
        // reload), a part the hand lets go at, the magazine in the inspect
        e.page = ArmEditor.Page.KEY;
        e.keyView = ArmEditor.KeyView.TRACK;
        for (int h = 0; h < 2; h++) {
            e.hand = h;
            e.track = ArmEditor.Track.PATH;
            e.time = e.duration() / 2F;
            if (h == 1) tap(e, "moment.add");
            e.track = ArmEditor.Track.CARRY;
            tap(e, "carry.add");
            tap(e, "carry.bus.add");
            tap(e, "carry.reload.own");
            e.keyView = ArmEditor.KeyView.HOLD;
            stepAll(e);
            e.keyView = ArmEditor.KeyView.TRACK;
            e.track = ArmEditor.Track.RELEASE;
            tap(e, "release.next");
            stepAll(e);
            e.track = ArmEditor.Track.AROUND;
            stepAll(e);
        }
        e.time = 0F;
        e.keyView = ArmEditor.KeyView.WHERE;
        e.page = ArmEditor.Page.ARM;
        tap(e, "set.armInspectReleaseMs.toggle");
        e.page = ArmEditor.Page.TIME;
        e.hand = 1;
    }

    @Test
    void everyControlDoesSomethingOrSaysWhyNot() throws Exception {
        var opened = Grips.snapshot();
        int pressed = 0, grey = 0, states = 0;
        Set<String> seen = new TreeSet<>(), shown = new TreeSet<>();
        try {
            var guns = jarGuns();
            guns.put("gunfeel:no_animations", Set.of());
            for (var g : guns.entrySet()) {
                List<ArmEditor.Clips> looks = new ArrayList<>(List.of(clips(g.getValue())));
                if (VARIANTS.containsKey(g.getKey())) looks.add(clips(g.getValue(), VARIANTS.get(g.getKey()), false));
                for (var look : looks) {
                    var e = new ArmEditor(g.getKey(), look);
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
                    setups.add(ArmEditorTest::ownEverything);
                    for (var setup : setups) {
                        Grips.restoreUnsaved(opened);
                        var fresh = new ArmEditor(g.getKey(), look);
                        setup.accept(fresh);
                        for (var page : ArmEditor.Page.values()) {
                            fresh.page = page;
                            for (int c = 0; c < Math.max(1, fresh.clipList().size()); c++)
                                for (int h = 0; h < (page == ArmEditor.Page.BOTH ? 1 : 2); h++)
                                    for (var t : lane(page) ? ArmEditor.Track.values() : new ArmEditor.Track[] {fresh.track}) {
                                        fresh.clip = c;
                                        fresh.hand = h;
                                        fresh.track = t;
                                        var times = new TreeSet<Float>(List.of(0F, fresh.duration() / 2F, (float) fresh.duration()));
                                        if (lane(page)) times.addAll(fresh.keys(t));
                                        for (float time : times) {
                                            fresh.time = time;
                                            for (var view : page == ArmEditor.Page.KEY ? ArmEditor.KeyView.values() : new ArmEditor.KeyView[] {ArmEditor.KeyView.WHERE})
                                                for (boolean playing : new boolean[] {false, true}) {
                                                    fresh.keyView = view;
                                                    fresh.playing = playing;
                                                    states++;
                                                    int[] n = check(fresh, g.getKey(), seen, shown);
                                                    pressed += n[0];
                                                    grey += n[1];
                                                }
                                            fresh.keyView = ArmEditor.KeyView.WHERE;
                                        }
                                    }
                            // settings pages: the same for every animation, one is enough
                            if (!lane(page)) fresh.clip = 0;
                        }
                    }
                }
            }
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
        System.out.println("ARMEDITOR states " + states + " pressed " + pressed + " grey " + grey + " controls " + shown.size());
        // every control that was ever shown could be pressed somewhere
        var never = new TreeSet<>(shown);
        never.removeAll(seen);
        assertEquals(Set.of(), never, "shown but never pressable");
        for (String id : List.of("clip.prev", "play", "time.start", "track.around", "key.next", "arms.own", "arms.shared", "undo", "redo", "reset", "close",
                "page.grip", "grip.mode", "grip.reset", "grip.part.next", "grip.variant.own", "grip.variant.common", "set.grip.2.plus",
                "set.armReachLeft.minus", "set.armMaxScale.plus", "set.armSlide.toggle", "set.armSlideNearest.reset", "set.armStretch.reset",
                "page.key", "key.add", "key.delete", "set.key.time.plus", "set.key.from.minus", "set.key.to.plus", "key.part.next", "key.nth",
                "set.key.point.0.plus", "key.contact.next", "set.key.roll.minus", "key.roll.reset", "key.view.approach", "key.view.where",
                "key.side.next", "key.side.reset", "set.key.shoulder.2.plus", "key.follow", "set.key.weight.minus", "key.kind.next",
                "set.key.bus.plus", "set.key.angle.plus", "set.key.adjust.5.minus", "key.view.track", "key.view.hold", "moment.add", "set.moment.0.plus",
                "moment.0.delete", "key.moment.next", "set.armPathMinLength.plus", "set.armPathBlend.toggle", "carry.add", "carry.delete", "carry.reload.own",
                "carry.reload.common", "carry.part.next", "carry.bus.next", "carry.bus.add", "carry.bus.delete", "set.carry.grab.plus", "set.carry.at.0.plus",
                "set.carry.dir.0.plus", "carry.dir.reset", "set.armCarryTurnLeft.plus", "set.armCarryKeepAngle.toggle", "release.next", "release.reset",
                "set.armReleaseMove.0.plus", "set.armAroundShiftReload.plus", "set.armLoadRollLeft.plus", "set.armLoadScaleLeft.reset",
                "set.armInspectReleaseMs.toggle", "set.armInspectReleaseMs.plus"))
            assertTrue(shown.contains(id), id + " was never shown");
    }

    // the rows of buttons at the top of a settings page (ArmEditorScreen.settingsPage)
    static final Set<String> TOP = Set.of("clip.prev", "play", "clip.next", "speed", "hand.right", "hand.left", "undo", "redo", "reset", "arms.own",
            "arms.shared", "close", "key.prev", "key.next", "key.add", "key.delete", "key.view.where", "key.view.approach", "key.view.track", "key.view.hold");

    // the pages that go along the animation: every track, at every key
    private static boolean lane(ArmEditor.Page p) {
        return p == ArmEditor.Page.TIME || p == ArmEditor.Page.KEY;
    }

    // presses each control of one state; returns {pressed, grey}
    private static int[] check(ArmEditor e, String gun, Set<String> seen, Set<String> shown) {
        var before = e.state();
        var undo = e.undoCopy();
        var redo = e.redoCopy();
        int pressed = 0, grey = 0;
        Set<String> ids = new HashSet<>();
        for (var a : e.actions()) {
            String where = gun + e.clips.variant() + " " + e.page + " " + e.clip() + " hand " + e.hand + " " + e.track + " t=" + e.time + " " + a.id();
            assertTrue(ids.add(a.id()), where + ": two controls with one id");
            shown.add(a.id());
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
        // a settings page shows an action in its rows of buttons at the top, or in the row it belongs to
        if (e.page != ArmEditor.Page.TIME) {
            var placed = new HashSet<>(TOP);
            for (var r : e.rows()) placed.addAll(r.ids());
            for (String id : ids)
                assertTrue(id.startsWith("page.") || placed.contains(id), gun + " " + e.page + " " + e.track + " t=" + e.time + ": no place on the screen for " + id);
        }
        for (var r : e.rows()) {
            String where = gun + e.clips.variant() + " " + e.page + " hand " + e.hand + " row";
            String text = r.slider() != null ? r.slider().text() : r.text();
            assertFalse(text.isBlank() || r.hint().isBlank(), where + ": a row without text or hint");
            noEnglish(text, e, where);
            noEnglish(r.hint(), e, where);
            if (r.slider() != null && r.slider().why() != null) noEnglish(r.slider().why(), e, where);
            for (String id : r.ids()) assertTrue(ids.contains(id), where + ": a button of the row is no action: " + id);
        }
        return new int[] {pressed, grey};
    }

    // no Latin letters, apart from names of guns (shown as the game names them)
    private static void noEnglish(String text, ArmEditor e, String where) {
        String t = text;
        String shared = e.sharedWith();
        if (shared != null) t = t.replace(e.clips.gunName(shared), "");
        // the model's part names, as the model names them, the longest first ("BarrelShort" before "Barrel")
        Config.select(e.gun);
        var parts = new ArrayList<String>(e.clips.parts());
        parts.addAll(List.of(e.clips.variant().split("-")));
        parts.addAll(List.of(Config.armPartRight, Config.armPartLeft));
        for (String src : List.of(Config.armRightPath, Config.armLeftPath))
            for (var seg : ArmRig.path(src).segments)
                for (var en : seg.entries) if (en.valid() && !en.grip()) parts.add(en.part);
        // named moments of the path ("release"), as the gun's data names them
        parts.addAll(ArmRig.pathTimes(Config.armPathTimes).keySet());
        // what the hands carry and let go at: the model's parts and NTM's buses, as they are named
        for (String src : List.of(Config.armRightCarry, Config.armLeftCarry, Config.armRightCarryReload, Config.armLeftCarryReload)) {
            var c = ArmRig.carry(src);
            if (c != null) {
                parts.add(c.part());
                parts.addAll(List.of(c.buses()));
            }
        }
        parts.addAll(List.of(Config.armReleaseRight, Config.armReleaseLeft));
        if (e.clip() != null) parts.addAll(e.clips.moves(e.clip().name()).keySet());
        parts.removeIf(String::isEmpty);
        parts.sort(Comparator.comparingInt(String::length).reversed());
        for (String part : parts) t = t.replaceAll("(?i)" + java.util.regex.Pattern.quote(part), "");
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
