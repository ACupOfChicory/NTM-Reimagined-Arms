package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

// The arm editor's settings pages: a drag is one step of undo, a setting that does not act cannot be dragged, the grip
// changes way (point or fractions) without moving the hand, and a variant's grip is written where it is read.
class ArmSettingsTest {
    static final String GUN = "hbm:gun_greasegun";

    private static ArmEditor.Setting setting(ArmEditor e, String id) {
        for (var r : e.rows()) if (r.slider() != null && r.slider().id().equals(id)) return r.slider();
        return null;
    }

    @Test
    void aDragIsOneStepOfUndo() {
        var opened = Grips.snapshot();
        try {
            var e = new ArmEditor(GUN, ArmEditorTest.clips(List.of("RELOAD")));
            e.page = ArmEditor.Page.BOTH;
            float was = setting(e, "armFollow").value();
            for (float v : new float[] {0.1F, 0.2F, 0.3F}) e.slide("armFollow", v, false);
            e.slide("armFollow", 0.4F, true);
            assertEquals(0.4F, setting(e, "armFollow").value(), 1e-4);
            assertEquals("0.4", Grips.own(GUN, "armFollow"));
            assertEquals(1, e.undoSize());
            assertTrue(e.press("undo"));
            assertEquals(was, setting(e, "armFollow").value(), 1e-4);
            // past the end: held at the end
            e.slide("armFollow", 7F, true);
            assertEquals(1F, setting(e, "armFollow").value(), 1e-4);
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    // a key or a button pressed while the mouse still holds the slider (the screen is rebuilt and the drag is never let
    // go): the drag so far is its own step, and the next drag is undone to where it began
    @Test
    void aDragCutShortIsItsOwnStep() {
        var opened = Grips.snapshot();
        try {
            var e = new ArmEditor(GUN, ArmEditorTest.clips(List.of("RELOAD")));
            e.page = ArmEditor.Page.BOTH;
            float was = setting(e, "armFollow").value();
            e.slide("armFollow", 0.5F, false);
            assertTrue(e.press("undo"));
            assertEquals(was, setting(e, "armFollow").value(), 1e-4);
            e.slide("armFollow", 0.3F, false);
            e.slide("armFollow", 0.2F, true);
            assertTrue(e.press("undo"));
            assertEquals(was, setting(e, "armFollow").value(), 1e-4, "undone to where the second drag began");
            assertTrue(e.press("redo"));
            assertEquals(0.2F, setting(e, "armFollow").value(), 1e-4);
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    @Test
    void aSettingThatDoesNotActIsGreyAndCannotBeDragged() {
        var opened = Grips.snapshot();
        try {
            var e = new ArmEditor(GUN, ArmEditorTest.clips(List.of("RELOAD")));
            e.page = ArmEditor.Page.BOTH;
            // both arms have a fixed thickness: the limit of the growing one does nothing
            var max = setting(e, "armMaxScale");
            assertNotNull(max.why());
            float was = max.value();
            e.slide("armMaxScale", 1F, true);
            assertEquals(was, setting(e, "armMaxScale").value(), 1e-4);
            assertEquals(0, e.undoSize());
            assertFalse(e.action("set.armMaxScale.plus").active());
            // one arm's thickness grows with its length: now it acts
            Grips.put(GUN, "armBaseLeft", "0", false);
            assertNull(setting(e, "armMaxScale").why());
            assertTrue(e.press("set.armMaxScale.plus"));
            // no sliding of the hands: how far to slide does nothing; the stretch still holds the arm's reach (Arms.pose)
            assertTrue(e.press("set.armSlide.toggle"));
            assertNotNull(setting(e, "armSlideTo").why());
            assertNull(setting(e, "armStretch").why());
            assertFalse(e.action("set.armSlideNearest.toggle").active());
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    @Test
    void theGripChangesWayWithoutMovingTheHand() {
        var opened = Grips.snapshot();
        try {
            var e = new ArmEditor(GUN, ArmEditorTest.clips(List.of("RELOAD")));
            e.page = ArmEditor.Page.GRIP;
            e.hand = 1;
            // the grease gun holds its left hand at a point of the model
            assertNotNull(Grips.get(Identifier.parse(GUN)).leftAt());
            float[] at = Grips.get(Identifier.parse(GUN)).leftAt().clone();
            assertEquals("В доли", e.action("grip.mode").label());
            assertTrue(e.press("grip.mode"));
            assertNull(Grips.get(Identifier.parse(GUN)).leftAt());
            float[] f = Grips.get(Identifier.parse(GUN)).left();
            assertArrayEquals(new float[] {(at[0] + 2) / 4, (at[1] + 2) / 4, (at[2] + 10) / 20}, f, 1e-4F);
            assertTrue(e.press("grip.mode"));
            assertArrayEquals(at, Grips.get(Identifier.parse(GUN)).leftAt(), 1e-4F);
            // beyond the muzzle the model has no section to take shares of: the hand would move, so it does not change way
            assertTrue(e.press("grip.mode"));
            assertNull(e.action("grip.mode").why());
            Grips.setAt(GUN, false, 1F, -1F, 12F, false);
            assertNotNull(e.action("grip.mode").why());
            assertFalse(e.press("grip.mode"));
            assertArrayEquals(new float[] {1F, -1F, 12F}, Grips.get(Identifier.parse(GUN)).leftAt(), 1e-4F);
            // without a measured model there is nothing to change by
            var blind = new ArmEditor(GUN, ArmEditorTest.clips(List.of("RELOAD"), "", false));
            blind.page = ArmEditor.Page.GRIP;
            assertNotNull(blind.action("grip.mode").why());
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    // the sawn-off double barrel holds its left hand its own way (the jar says so); the right hand as the whole gun
    @Test
    void aVariantsGripIsWrittenWhereItIsRead() {
        var opened = Grips.snapshot();
        String gun = "hbm:gun_double_barrel";
        try {
            var e = new ArmEditor(gun, ArmEditorTest.clips(List.of("RELOAD"), "-Barrel", true));
            e.page = ArmEditor.Page.GRIP;
            e.hand = 1;
            float[] whole = Grips.get(Identifier.parse(gun)).leftAt();
            assertTrue(e.press("set.grip.2.plus"));
            assertArrayEquals(whole, Grips.get(Identifier.parse(gun)).leftAt(), "the whole gun's hand stays");
            assertNotNull(Grips.own(gun + "-Barrel", "leftAt"));
            assertFalse(e.action("grip.variant.common").active(), "the jar's own grip of the sawn-off cannot be taken away");
            e.hand = 0;
            float[] variantRight = Grips.get(Identifier.parse(gun), "-Barrel").right().clone();
            assertTrue(e.press("set.grip.0.plus"));
            assertArrayEquals(Grips.get(Identifier.parse(gun)).right(), Grips.get(Identifier.parse(gun), "-Barrel").right(),
                    "a variant without a grip of its own moves with the gun");
            assertNotEquals(variantRight[0], Grips.get(Identifier.parse(gun)).right()[0]);
            assertTrue(e.press("grip.variant.own"));
            assertTrue(e.press("set.grip.0.plus"));
            assertNotEquals(Grips.get(Identifier.parse(gun)).right()[0], Grips.get(Identifier.parse(gun), "-Barrel").right()[0]);
            assertTrue(e.press("grip.variant.common"));
            assertArrayEquals(Grips.get(Identifier.parse(gun)).right(), Grips.get(Identifier.parse(gun), "-Barrel").right());
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }
}
