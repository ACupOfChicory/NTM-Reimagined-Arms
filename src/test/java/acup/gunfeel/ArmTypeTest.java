package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

// a number typed for a slider: taken exactly, off the step's grid too, in one step of undo; a text that is not a
// number or a number past the slider's ends is not taken and says why
class ArmTypeTest {
    @Test
    void aTypedNumberIsTakenAsItIs() {
        var opened = Grips.snapshot();
        String gun = "gunfeel:key_probe";
        try {
            var e = new ArmEditor(gun, ArmEditorTest.clips(List.of("RELOAD")));
            e.page = ArmEditor.Page.ARM;
            e.hand = 1;
            assertNull(e.type("armRollLeft", "-66,25"));
            Config.select(gun);
            assertEquals(-66.25F, Config.armRollLeft);
            // more digits than four: the number itself, as the jar has such numbers
            assertNull(e.type("armDirLeft.1", "0.42999998"));
            Config.select(gun);
            assertEquals(0.42999998F, Config.armDirLeft[1]);
            assertEquals(Float.parseFloat("0.42999998"), Float.parseFloat(Grips.armsOf(gun).get(gun + ".armDirLeft").split(",")[1]));
            var before = e.state();
            assertNotNull(e.type("armRollLeft", "пять"));
            assertNotNull(e.type("armRollLeft", "1e3"));
            assertTrue(e.type("armRollLeft", "500").contains("до 180"), e.type("armRollLeft", "500"));
            assertNotNull(e.type("no.such", "1"));
            assertEquals(before, e.state(), "nothing taken");
            assertTrue(e.press("undo"));
            Config.select(gun);
            assertEquals(-66.25F, Config.armRollLeft);
            assertTrue(e.press("undo"));
            assertEquals(opened, Grips.snapshot());
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    // the grip, a key's time and a key's point, off their grids (as G3, the shotgun and Sexy have them)
    @Test
    void numbersOffTheGridAreReached() {
        var opened = Grips.snapshot();
        String gun = "gunfeel:key_probe";
        try {
            var e = new ArmEditor(gun, ArmEditorTest.clips(List.of("RELOAD")));
            e.page = ArmEditor.Page.GRIP;
            e.hand = 1;
            assertTrue(e.press("grip.mode"), "to a point in model units");
            assertNull(e.type("grip.2", "-0,44"));
            Config.select(gun);
            assertEquals(-0.44F, Grips.get(Identifier.parse(gun)).leftAt()[2]);
            assertEquals(-0.44F, e.rows().stream().filter(r -> r.slider() != null && r.slider().id().equals("grip.2")).findFirst().orElseThrow().slider().value(), 1e-6F);
            e.page = ArmEditor.Page.KEY;
            e.track = ArmEditor.Track.PATH;
            e.time = 0F;
            assertTrue(e.press("key.add"));
            e.time = 860F;
            assertTrue(e.press("key.add"));
            assertNull(e.type("key.time", "865"));
            Config.select(gun);
            var k = ArmRig.pathKeys(Config.armLeftPath, Config.armPathTimes).get("RELOAD");
            assertEquals(865F, k.get(1).ms(), Config.armLeftPath);
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    // a step lands on the decimal itself: 7 steps of 0.05 is written 0.35; a number off the grid is shown as it is
    @Test
    void aStepIsTheDecimal() {
        assertEquals(0.35F, ArmEditor.snap(0.349F, 0.05F));
        assertEquals("0.35", ArmRig.number(ArmEditor.snap(0.351F, 0.05F)));
        assertEquals(-0.44F, ArmEditor.snap(-0.44F, 0.01F));
        assertEquals("0,35", ArmEditor.number(0.35F, 0.05F));
        assertEquals("-66", ArmEditor.number(-66F, 1F));
        assertEquals("-66,25", ArmEditor.number(-66.25F, 1F));
        assertEquals("3,935", ArmEditor.number(3.935F, ArmEditor.STEP));
    }

    // the game takes a whole number of milliseconds for the moment a carry is taken: a fraction is refused, saying so
    @Test
    void aWholeNumberSliderRefusesAFraction() {
        var opened = Grips.snapshot();
        String gun = "gunfeel:key_probe";
        try {
            var e = new ArmEditor(gun, ArmEditorTest.clips(List.of("RELOAD")));
            e.page = ArmEditor.Page.KEY;
            e.track = ArmEditor.Track.CARRY;
            e.hand = 1;
            assertTrue(e.press("key.view.track"));
            assertTrue(e.press("carry.add"), String.valueOf(e.action("carry.add")));
            var before = e.state();
            String why = e.type("carry.grab", "865,5");
            assertNotNull(why);
            assertTrue(why.contains("целое"), why);
            assertEquals(before, e.state());
            assertNull(e.type("carry.grab", "865"));
            Config.select(gun);
            assertEquals(865, ArmRig.carry(Config.armLeftCarry).grab(), Config.armLeftCarry);
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
    }

    // a key written from a named moment reads back as the time typed: the game adds the offset as a float
    @Test
    void aKeyFromAMomentReadsAsTyped() {
        var times = java.util.Map.of("release", 2410F);
        for (float ms : new float[] {0.1F, 3.935F, 2410F, 2750F, 0.3F, 2409.9F}) {
            String t = ArmRig.fromMoment("release", 2410F, ms);
            if (t == null) continue;
            assertEquals(ms, ArmRig.pathMs(t, times), t);
        }
        assertEquals("release+340", ArmRig.fromMoment("release", 2410F, 2750F));
        assertEquals("release", ArmRig.fromMoment("release", 2410F, 2410F));
        // retimed: from the moment where an offset adds up, else in milliseconds
        var e = ArmRig.PathEntry.read("release+300:Pump@0,0,0");
        e.retime(0.1F, times);
        assertEquals(0.1F, ArmRig.pathMs(e.time, times), e.time);
    }
}
