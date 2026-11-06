package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class ArmDeadSliderTest {
    private static final List<String> GUNS = List.of("hbm:gun_greasegun", "hbm:gun_star_f");
    // a path of the hand along the parts, as shipped for a gun that has one
    private static final String PATH = Grips.shipped("hbm:gun_g3", "armLeftPath");

    private static ArmEditor.Setting setting(ArmEditor editor, String id) {
        for (var row : editor.rows()) if (row.slider() != null && row.slider().id().equals(id)) return row.slider();
        fail("Missing slider " + id);
        return null;
    }

    private static void put(ArmEditor editor, ArmEditor.Setting s, float v) {
        String id = s.id();
        int dot = id.lastIndexOf('.');
        String key = dot < 0 ? id : id.substring(0, dot);
        if (dot >= 0) {
            int c = Integer.parseInt(id.substring(dot + 1));
            float[] values = (float[]) config(key);
            values = values.clone();
            values[c] = v;
            Grips.put(editor.gun, key, values[0] + "," + values[1] + "," + values[2], false);
        } else {
            Grips.put(editor.gun, key, Float.toString(v), false);
        }
        Config.select(editor.gun);
    }

    private static Object config(String key) {
        try {
            return Config.class.getField(key).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(String gun, String key, String value) {
        Grips.put(gun, key, value, false);
        Config.select(gun);
    }

    // 0 the gun's own settings; 1 left base 0; 2 right base 0; 3 both 0; 4 bases > 0, length 0; 5 both 0, length 0;
    // 6 both 0 and a right arm thick enough (0.8 x 2.5) to be drawn shorter than 11.5 of its pixels, so cut by armLength
    private static final int STATES = 7;

    private static void armState(String gun, int state) {
        if (state == 0) return;
        float left = state == 1 || state == 3 || state == 5 || state == 6 ? 0F : 2F;
        float right = state == 2 || state == 3 || state == 5 || state == 6 ? 0F : 2F;
        float length = state == 4 || state == 5 ? 0F : 1F;
        set(gun, "armBaseLeft", Float.toString(left));
        set(gun, "armBaseRight", Float.toString(right));
        set(gun, "armLength", Float.toString(length));
        if (state == 6) {
            set(gun, "armScale", "0.8");
            set(gun, "armScaleRight", "2.5");
        }
    }

    private static void armDirection(String gun, boolean right) {
        String side = right ? "Right" : "Left";
        float[] values = right ? new float[] {-0.25F, 0.1F, -1F} : new float[] {0.25F, -0.3F, -1F};
        set(gun, "armDir" + side, values[0] + "," + values[1] + "," + values[2]);
    }

    private static void resetState(Map<String, String> opened, String gun, int state) {
        Grips.restoreUnsaved(opened);
        Config.select(null);
        Config.select(gun);
        armState(gun, state);
    }

    private record Pose(float length, float scale, Vector3f hand, Vector3f shoulder, int crop, float[] matrix) {
        boolean same(Pose other) {
            if (Math.abs(length - other.length) > 1e-6F || Math.abs(scale - other.scale) > 1e-6F || crop != other.crop
                    || hand.distance(other.hand) > 1e-6F || shoulder.distance(other.shoulder) > 1e-6F) return false;
            for (int i = 0; i < matrix.length; i++) if (Math.abs(matrix[i] - other.matrix[i]) > 1e-6F) return false;
            return true;
        }
    }

    private static Pose pose(boolean right, boolean loading, float w) {
        var down = new Vector3f(0F, -1F, 0F);
        var grip = new Vector3f(right ? -0.28F : 0.30F, -0.2F, 0.72F);
        var rest = new Arms.ArmRest();
        float[] dir = right ? Config.armDirRight : Config.armDirLeft;
        float reach = right ? Config.armReachRight : Config.armReachLeft;
        float holdScale = loading ? right ? Config.armLoadScaleRight : Config.armLoadScaleLeft : Arms.holdScale(right, w);
        float wp = pistolWeight();
        Arms.restArm(rest, right, false, grip, down, grip, new Vector3f(0F, 0F, 0F), dir, reach, holdScale, wp > 0F, wp);
        Arms.rollNow = loading ? (right ? Config.armLoadRollRight : Config.armLoadRollLeft) : Float.NaN;
        Matrix4f local = Arms.local(rest.hand, new Quaternionf(), new Vector3f(dir).normalize(), right, false, rest.s, rest.length);
        float[] matrix = new float[16];
        local.get(matrix);
        Arms.rollNow = Float.NaN;
        return new Pose(rest.length, rest.s, new Vector3f(rest.hand), new Vector3f(rest.shoulder), rest.crop, matrix);
    }

    // the weight of a pistol's arms sized by their hands' depth, as the game draws this gun from the hip
    // (MixinItemRenderWeaponBase: pistolHold on a pistol, armPistolScale; no scope on the test guns)
    private static float pistolWeight() {
        return Config.enabled && Config.armPistolScale && Config.pistolHold > 0F && WalkSprint.pistol(Config.kickPreset)
                ? Math.min(1F, Config.pistolHold) : 0F;
    }

    private static boolean dead(ArmEditor editor, ArmEditor.Setting s, boolean right, boolean loading) {
        Pose[] first = new Pose[3];
        boolean changed = false;
        for (int n = 0; n < 9; n++) {
            put(editor, s, s.min() + (s.max() - s.min()) * n / 8F);
            int i = 0;
            for (float w : new float[] {0F, 0.5F, 1F}) {
                Pose now = pose(right, loading, w);
                if (n == 0) first[i] = now;
                else changed |= !first[i].same(now);
                i++;
            }
        }
        return !changed;
    }

    @Test
    void deadArmSlidersMatchTheRestPoseAcrossTheWholeRange() {
        Map<String, String> opened = Grips.snapshot();
        try {
            for (String gun : GUNS) {
                for (int state = 0; state < STATES; state++) {
                    resetState(opened, gun, state);
                    // The first state is the gun's shipped defaults. Remaining states isolate each base/length branch.
                    var editor = new ArmEditor(gun, ArmEditorTest.clips(List.of("RELOAD")));
                    for (boolean right : new boolean[] {true, false}) {
                        editor.page = ArmEditor.Page.ARM;
                        editor.hand = right ? 0 : 1;
                        for (String id : List.of("armDir" + (right ? "Right" : "Left") + ".0", "armDir" + (right ? "Right" : "Left") + ".1",
                                "armDir" + (right ? "Right" : "Left") + ".2", "armRoll" + (right ? "Right" : "Left"),
                                "armScale" + (right ? "Right" : "Left"), "armBase" + (right ? "Right" : "Left"),
                                "armAimScale" + (right ? "Right" : "Left"),
                                "armReach" + (right ? "Right" : "Left"), "armLoadRoll" + (right ? "Right" : "Left"),
                                "armLoadScale" + (right ? "Right" : "Left"))) {
                            resetState(opened, gun, state);
                            armDirection(gun, right);
                            boolean isLoad = id.startsWith("armLoad");
                            // the loading pose is drawn only while the hand goes along a path or carries
                            // (Arms.pathState / carryState are null for an empty source): give it a path
                            if (isLoad) set(gun, right ? "armRightPath" : "armLeftPath", PATH);
                            ArmEditor.Setting s = setting(editor, id);
                            boolean isDead = dead(editor, s, right, isLoad);
                            assertEquals(isDead, s.why() != null,
                                    gun + " " + state + " " + id + " grey iff pose is unchanged");
                            if (s.why() != null) assertTrue(s.text().length() <= 34, s.text());
                            if (isLoad) {
                                set(gun, right ? "armRightPath" : "armLeftPath", "");
                                set(gun, right ? "armRightCarryKeys" : "armLeftCarryKeys", "");
                                assertNotNull(setting(editor, id).why(), gun + " " + state + " " + id + ": grey with no path and no carrying");
                            }
                        }
                        // the arm runs forward from the hand: its length does not depend on how far back it goes
                        resetState(opened, gun, state);
                        String side = right ? "Right" : "Left";
                        set(gun, "armDir" + side, (right ? "-0.25" : "0.25") + ",0.1,0.5");
                        ArmEditor.Setting reach = setting(editor, "armReach" + side);
                        assertEquals(dead(editor, reach, right, false), reach.why() != null, gun + " " + state + " armReach" + side + " with the arm going forward");
                        if (state == 3) assertNotNull(reach.why(), gun + ": a forward arm with base 0 has no reach to take");
                    }
                    editor.page = ArmEditor.Page.BOTH;
                    for (String id : List.of("armScale", "armLength", "armMaxScale")) {
                        resetState(opened, gun, state);
                        armDirection(gun, true);
                        armDirection(gun, false);
                        ArmEditor.Setting s = setting(editor, id);
                        // These shared values affect either hand, holding or loading; compare all four poses at each value.
                        boolean dead = true;
                        Pose[] first = null;
                        for (int n = 0; n < 9; n++) {
                            put(editor, s, s.min() + (s.max() - s.min()) * n / 8F);
                            Pose[] now = new Pose[12];
                            int at = 0;
                            for (float w : new float[] {0F, 0.5F, 1F}) {
                                now[at++] = pose(true, false, w);
                                now[at++] = pose(false, false, w);
                                now[at++] = pose(true, true, w);
                                now[at++] = pose(false, true, w);
                            }
                            if (first == null) first = now;
                            else for (int i = 0; i < now.length; i++) dead &= first[i].same(now[i]);
                        }
                        // (by its hand's depth the arm is no thicker than the depth makes it)
                        if (state == 6 && id.equals("armLength") && pistolWeight() == 0F) assertFalse(dead, gun + ": a thick arm is cut by armLength");
                        // an arm sized by its hand's depth has no bound on its thickness the editor could read: there
                        // the length is never grey, and only a grey one must be dead
                        if (id.equals("armLength") && pistolWeight() > 0F) assertNull(s.why(), gun + " " + state + " armLength by the hands' depth");
                        else assertEquals(dead, s.why() != null, gun + " " + state + " " + id + " grey iff both poses are unchanged");
                        if (s.why() != null) assertTrue(s.text().length() <= 34, s.text());
                    }
                    if (state == 6) {
                        // the same thick arm held to a small thickness by armMaxScale is long in its own pixels again
                        resetState(opened, gun, state);
                        armDirection(gun, true);
                        armDirection(gun, false);
                        set(gun, "armMaxScale", "0.5");
                        // the arms' thickness by the hand's depth would not take armMaxScale
                        set(gun, "armPistolScale", "false");
                        ArmEditor.Setting length = setting(editor, "armLength");
                        assertNotNull(length.why(), gun + ": armLength with a small armMaxScale");
                        Pose[] first = null;
                        for (int n = 0; n < 9; n++) {
                            put(editor, length, length.min() + (length.max() - length.min()) * n / 8F);
                            Pose[] now = new Pose[12];
                            int at = 0;
                            for (float w : new float[] {0F, 0.5F, 1F}) {
                                now[at++] = pose(true, false, w);
                                now[at++] = pose(false, false, w);
                                now[at++] = pose(true, true, w);
                                now[at++] = pose(false, true, w);
                            }
                            if (first == null) first = now;
                            else for (int i = 0; i < now.length; i++) assertTrue(first[i].same(now[i]), gun + ": armLength moves an arm held thin");
                        }
                    }
                }
            }
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
            Arms.rollNow = Float.NaN;
        }
    }

    @Test
    void conditionalSlidersHaveShortReasonsAndTheEditorOnlyGreysKnownPoseControls() {
        Map<String, String> opened = Grips.snapshot();
        var oldFiles = ArmFiles.dir;
        try {
            ArmFiles.dir = java.nio.file.Path.of("build/tmp/dead-slider-files");
            for (String gun : GUNS) {
                for (boolean slide : new boolean[] {false, true}) for (float inspect : new float[] {-1F, 500F})
                    for (boolean releasePart : new boolean[] {false, true}) {
                        Grips.restoreUnsaved(opened);
                        Grips.put(gun, "armSlide", Boolean.toString(slide), false);
                        Grips.put(gun, "armInspectReleaseMs", Float.toString(inspect), false);
                        Grips.put(gun, "armReleaseLeft", releasePart ? "ROLL" : "", false);
                        Grips.put(gun, "armReleaseRight", releasePart ? "ROLL" : "", false);
                        Config.select(gun);
                        var editor = new ArmEditor(gun, ArmEditorTest.clips(List.of("RELOAD")));
                        for (ArmEditor.Page page : ArmEditor.Page.values()) {
                            editor.page = page;
                            for (ArmEditor.KeyView view : page == ArmEditor.Page.KEY ? ArmEditor.KeyView.values()
                                    : new ArmEditor.KeyView[] {ArmEditor.KeyView.WHERE})
                            for (ArmEditor.Track track : page == ArmEditor.Page.KEY ? ArmEditor.Track.values()
                                    : new ArmEditor.Track[] {ArmEditor.Track.PATH}) {
                            editor.keyView = view;
                            editor.track = track;
                            for (int hand = 0; hand < (page == ArmEditor.Page.BOTH || page == ArmEditor.Page.FILE ? 1 : 2); hand++) {
                                editor.hand = hand;
                                for (var row : editor.rows()) if (row.slider() != null && row.slider().why() != null) {
                                    var s = row.slider();
                                    assertTrue(s.off() != null, gun + " " + page + " " + s.id() + " missing short reason");
                                    assertEquals(s.label() + ": " + s.off(), s.text());
                                    assertTrue(s.text().length() <= 34, gun + " " + page + " " + s.text());
                                    assertTrue(Set.of("armReachRight", "armReachLeft", "armInspectReleaseMs", "armMaxScale", "armSlideTo",
                                            "armLength", "armLoadRollRight", "armLoadRollLeft", "armLoadScaleRight", "armLoadScaleLeft", "armReleaseMove.0", "armReleaseMove.1", "armReleaseMove.2", "armReleaseMoveRight.0",
                                            "armReleaseMoveRight.1", "armReleaseMoveRight.2").contains(s.id()),
                                            "Unexpected conditional slider without a pose probe: " + page + " " + s.id());
                                }
                            }
                            }
                        }
                    }
            }
        } finally {
            ArmFiles.dir = oldFiles;
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    @Test
    void pistolThicknessSwitchIsOnlyActiveWhenPistolHoldCanApply() {
        Map<String, String> opened = Grips.snapshot();
        float hold0 = Config.pistolHold;
        try {
            String gun = GUNS.get(0);
            Config.select(gun);
            Config.kickPreset = "rifle";
            var editor = new ArmEditor(gun, ArmEditorTest.clips(List.of("RELOAD")));
            editor.page = ArmEditor.Page.BOTH;
            var row = editor.rows().stream().filter(r -> r.text() != null && r.text().startsWith("Руки пистолета по глубине:"))
                    .findFirst().orElseThrow();
            assertEquals("Руки пистолета по глубине: да", row.text());
            var toggle = editor.actions().stream().filter(a -> a.id().equals("set.armPistolScale.toggle")).findFirst().orElseThrow();
            assertFalse(toggle.active());
            assertEquals("действует только у пистолета, когда включён «пистолет у бедра»", toggle.why());
            assertTrue(toggle.why().length() <= 64);

            // a pistol aimed through a scope keeps NTM's hold (MixinItemRenderWeaponBase), so the rule does not act
            String pistol = GUNS.get(1);
            Config.select(pistol);
            assertTrue(WalkSprint.pistol(Config.kickPreset), pistol + " is a pistol");
            var plain = ArmEditorTest.clips(List.of("RELOAD"));
            var scoped = new ArmEditor.Clips() {
                public List<ArmEditor.Clip> list() {
                    return plain.list();
                }

                public boolean scoped() {
                    return true;
                }
            };
            for (boolean scope : new boolean[] {false, true}) {
                var e = new ArmEditor(pistol, scope ? scoped : plain);
                e.page = ArmEditor.Page.BOTH;
                var t = e.actions().stream().filter(a -> a.id().equals("set.armPistolScale.toggle")).findFirst().orElseThrow();
                assertEquals(!scope, t.active(), "scope " + scope);
                if (scope) assertEquals("не действует у оружия с оптическим прицелом", t.why());
            }

            // armMaxScale with both bases 0: the hold's full weight leaves it nothing, a half weight still reads it
            set(pistol, "armBaseRight", "0");
            set(pistol, "armBaseLeft", "0");
            for (float hold : new float[] {0.5F, 1F}) {
                Config.pistolHold = hold;
                var e = new ArmEditor(pistol, plain);
                e.page = ArmEditor.Page.BOTH;
                var max = setting(e, "armMaxScale");
                assertEquals(hold >= 1F, max.why() != null, "pistolHold " + hold);
                if (max.why() != null) assertEquals("Предел толщины: по глубине кисти", max.text());
            }
        } finally {
            Config.pistolHold = hold0;
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    @Test
    void disabledMagazineTimingAndSlideSwitchHaveNoEffect() {
        for (int n = 0; n < 9; n++) assertFalse(MagazineInspect.enabled(-5000F + 4999F * n / 8F));
        for (int n = 0; n < 9; n++) assertTrue(MagazineInspect.enabled(5000F * n / 8F));
        assertNotEquals(MagazineInspect.handTime(700, 0F), MagazineInspect.handTime(700, 5000F));
        assertNotEquals(MagazineInspect.handWeight(1600, 0F), MagazineInspect.handWeight(1600, 5000F));
    }

    @Test
    void releaseMoveIsDeadWithoutABusAndChangesTheReleasedArmWithOne() {
        Map<String, String> opened = Grips.snapshot();
        try {
            String gun = GUNS.get(0);
            var editor = new ArmEditor(gun, ArmEditorTest.clips(List.of("RELOAD")));
            editor.page = ArmEditor.Page.KEY;
            editor.keyView = ArmEditor.KeyView.TRACK;
            editor.track = ArmEditor.Track.RELEASE;
            editor.hand = 1;
            set(gun, "armReleaseLeft", "");
            var rest = new Arms.ArmRest();
            rest.s = 1F;
            rest.local.identity();
            float[] off = null;
            for (int n = 0; n < 9; n++) {
                var s = setting(editor, "armReleaseMove." + (n % 3));
                assertNotNull(s.why());
                put(editor, s, s.min() + (s.max() - s.min()) * n / 8F);
                float progress = Arms.release("", new double[] {0D, 0D, 180D});
                assertEquals(0F, progress, 0F);
                // as Arms.draw: the moved pose only while the turn is on, else the arm at rest
                Matrix4f matrix = progress > 0F ? Arms.releasePose(rest, false, progress) : new Matrix4f(rest.local);
                float[] now = new float[16];
                matrix.get(now);
                if (off == null) off = now;
                else assertArrayEquals(off, now, 1e-6F);
            }
            set(gun, "armReleaseLeft", "ROLL");
            for (int c = 0; c < 3; c++) {
                var s = setting(editor, "armReleaseMove." + c);
                assertNull(s.why());
                float[] first = null;
                boolean changed = false;
                for (int n = 0; n < 9; n++) {
                    put(editor, s, s.min() + (s.max() - s.min()) * n / 8F);
                    float progress = Arms.release("ROLL", new double[] {0D, 0D, 180D});
                    assertEquals(0.5F, progress, 1e-6F);
                    float[] now = new float[16];
                    Arms.releasePose(rest, false, progress).get(now);
                    if (first == null) first = now;
                    else for (int i = 0; i < now.length; i++) changed |= Math.abs(first[i] - now[i]) > 1e-6F;
                }
                assertTrue(changed, "release axis " + c + " moves the arm");
            }
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    @Test
    void slideLimitMovesTheHandOnlyWhenSlidingIsEnabledAndItHasToReach() {
        Map<String, String> opened = Grips.snapshot();
        try {
            String gun = GUNS.get(0);
            Grips.put(gun, "armSlide", "true", false);
            Grips.put(gun, "armStretch", "0", false);
            Config.select(gun);
            var editor = new ArmEditor(gun, ArmEditorTest.clips(List.of("RELOAD")));
            editor.page = ArmEditor.Page.BOTH;
            var s = setting(editor, "armSlideTo");
            assertNull(s.why());
            var rest = new Arms.ArmRest();
            rest.length = 0.1F;
            rest.s = 1F;
            rest.hand.set(0F, 0F, 0F);
            rest.shoulder.set(0F, 0F, 2F);
            rest.axis.set(0F, 0F, 0F);
            float[] first = null;
            boolean changed = false;
            for (int n = 0; n < 9; n++) {
                put(editor, s, s.min() + (s.max() - s.min()) * n / 8F);
                Matrix4f matrix = Arms.pose(true, false, new Vector3f(), new float[][] {{0F, 0F, 0F}, {0F, 0F, 2F}}, 1F,
                        new Matrix4f(), new Vector3f(0F, -1F, 0F), new Vector3f(), false, new Quaternionf(), 0F, rest);
                float[] now = new float[16];
                matrix.get(now);
                if (first == null) first = now;
                else for (int i = 0; i < now.length; i++) changed |= Math.abs(first[i] - now[i]) > 1e-6F;
            }
            assertTrue(changed, "a reaching hand's stop point follows the slider range");
            set(gun, "armSlide", "false");
            s = setting(editor, "armSlideTo");
            assertNotNull(s.why());
            first = null;
            for (int n = 0; n < 9; n++) {
                put(editor, s, s.min() + (s.max() - s.min()) * n / 8F);
                float[] now = new float[16];
                Arms.pose(true, false, new Vector3f(), new float[][] {{0F, 0F, 0F}, {0F, 0F, 2F}}, 1F, new Matrix4f(),
                        new Vector3f(0F, -1F, 0F), new Vector3f(), false, new Quaternionf(), 0F, rest).get(now);
                if (first == null) first = now;
                else assertArrayEquals(first, now, 1e-6F, "disabled sliding ignores its distance setting");
            }
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }
}
