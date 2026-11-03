package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

// The rest of the HUD part without the game: the corner HUD's strings (HudFormat), the ammo key's tap and hold
// (KeyHold), the wait for the server's draw (SwitchPrediction), the menu's colour sums and shapes (GuiShapes), each
// against 26.2 (0762086).
class HudPartTest {
    @Test
    void hudStrings() {
        assertEquals("006", HudFormat.big(6));
        assertEquals("120", HudFormat.big(120));
        assertEquals("999", HudFormat.big(5000));
        assertEquals("0015", HudFormat.small(15));
        assertEquals("9999", HudFormat.small(123456));
        assertEquals("∞", HudFormat.small(AmmoLogic.INFINITE));
        // dim zeros only beside a reserve, never the last digit
        assertEquals(2, HudFormat.lead("006", true));
        assertEquals(2, HudFormat.lead("000", true));
        assertEquals(0, HudFormat.lead("006", false));
        assertEquals(0, HudFormat.lead("x12", true));
        // red from a quarter down, at least the last round
        assertTrue(HudFormat.low(1, 2));
        assertFalse(HudFormat.low(2, 2));
        assertTrue(HudFormat.low(7, 30));
        assertFalse(HudFormat.low(8, 30));
        assertFalse(HudFormat.low(0, 0));
    }

    @Test
    void durabilityBar() {
        assertEquals(1F, HudFormat.health(0, 100));
        assertEquals(.25F, HudFormat.health(75, 100), 1e-6);
        assertEquals(0F, HudFormat.health(150, 100));
        assertEquals(1F, HudFormat.health(50, 0));
        assertEquals(0xFF00FF40, HudFormat.durabilityColor(1F)); // green
        assertEquals(0xFFFFFF40, HudFormat.durabilityColor(.5F)); // yellow
        assertEquals(0xFFFF0040, HudFormat.durabilityColor(0F)); // red
    }

    @Test
    void tapLocksHoldOpens() {
        KeyHold k = new KeyHold();
        assertEquals(KeyHold.Action.NONE, k.tick(true, true, false, 1000));
        assertEquals(KeyHold.Action.NONE, k.tick(true, true, false, 1200));
        assertEquals(KeyHold.Action.TAP, k.tick(false, true, false, 1240));
        // held: the menu at 250 ms, once
        assertEquals(KeyHold.Action.NONE, k.tick(true, true, false, 2000));
        assertEquals(KeyHold.Action.OPEN, k.tick(true, true, false, 2250));
        assertEquals(KeyHold.Action.NONE, k.tick(true, true, true, 2300));
        assertEquals(KeyHold.Action.CLOSE, k.tick(false, true, true, 2400));
        // held with nothing to choose (the menu did not open): letting go does nothing
        assertEquals(KeyHold.Action.NONE, k.tick(true, true, false, 3000));
        assertEquals(KeyHold.Action.OPEN, k.tick(true, true, false, 3300));
        assertEquals(KeyHold.Action.NONE, k.tick(false, true, false, 3400));
        // no gun: neither
        assertEquals(KeyHold.Action.NONE, k.tick(true, false, false, 4000));
        assertEquals(KeyHold.Action.NONE, k.tick(true, false, false, 4300));
        assertEquals(KeyHold.Action.NONE, k.tick(false, false, false, 4400));
        assertEquals(KeyHold.Action.NONE, k.tick(true, false, false, 5000));
        assertEquals(KeyHold.Action.NONE, k.tick(false, false, false, 5100));
    }

    @Test
    void serverDrawSkippedOncePerConfig() {
        SwitchPrediction p = new SwitchPrediction();
        Object gun = new Object(), other = new Object();
        assertTrue(p.accept(2, gun, true, 0, 0)); // nothing predicted
        p.start(2, gun, 2, 1000);
        assertTrue(p.accept(3, gun, true, 0, 1100)); // another slot
        assertTrue(p.accept(2, other, true, 0, 1100)); // another gun
        assertTrue(p.accept(2, gun, true, 5, 1100)); // no such config
        assertFalse(p.accept(2, gun, true, 0, 1100));
        assertTrue(p.accept(2, gun, true, 0, 1150)); // once each
        assertFalse(p.accept(2, gun, true, 1, 1200));
        p.start(2, gun, 1, 2000);
        assertTrue(p.accept(2, gun, true, 0, 2000 + SwitchPrediction.WAIT_MS + 1)); // too late
        p.start(2, gun, 1, 3000);
        assertTrue(p.accept(2, gun, false, 0, 3100)); // anything else ends the wait
        assertTrue(p.accept(2, gun, true, 0, 3200));
        p.start(2, gun, 1, 4000);
        p.clear();
        assertTrue(p.accept(2, gun, true, 0, 4100));
    }

    // 26.2's PinnedTest.guiColours line for line, against 26.2's pin
    @Test
    void guiColours() {
        List<String> out = new ArrayList<String>();
        int[] colours = {0x000000, 0xFFFFFF, 0xFF8C1A, 0x00FF7F, 0x123456, 0x80FFFFFF, 0xFF102030};
        float[] ts = {-0.5F, 0F, 0.1F, 0.25F, 0.5F, 0.75F, 0.999F, 1F, 1.5F};
        for (int c : colours) {
            for (float t : ts) {
                out.add(String.format("argb %08x %s = %08x", c, PinnedTest.num(t), GuiShapes.argb(c, t)));
                out.add(String.format("alpha %08x %s = %08x", c, PinnedTest.num(t), GuiShapes.alpha(c, t)));
                for (int d : colours) out.add(String.format("mix %08x %08x %s = %08x", c, d, PinnedTest.num(t), GuiShapes.mix(c, d, t)));
            }
        }
        PinnedTest.pin("gui", out);
    }

    @Test
    void shapes() {
        GuiShapes s = new GuiShapes();
        s.quad(0, 0, 0x00FFFFFF, 1, 0, 0, 1, 1, 0, 0, 1, 0); // all clear: nothing
        assertEquals(0, s.vertices());
        s.line(0, 0, 10, 0, 2, 0xFFFFFFFF);
        assertEquals(4, s.vertices());
        assertEquals(1F, Math.abs(s.y(0)), 1e-6);
        // every quad the same way round (26.2's GUI pipeline culls the others)
        s.ring(0, 0, 20, 30, 0xFF000000, 0xFFFFFFFF);
        s.petal(0, 0, 23, 62, 0, 1, 3, 4, 0xFF000000, 0xFF000000);
        s.disc(5, 5, 3, 0xFFFFFFFF, 0xFFFFFFFF);
        s.hazard(0, 0, 56, 62, 0, 1, 0xFF000000, 0xFFFF8C1A, 3);
        assertEquals(0, s.vertices() % 4);
        for (int q = 0; q < s.vertices(); q += 4) {
            float area = 0;
            for (int i = 0; i < 4; i++) {
                int a = q + i, b = q + (i + 1) % 4;
                area += s.x(a) * s.y(b) - s.x(b) * s.y(a);
            }
            assertTrue(area <= 1e-3F, "quad " + q / 4 + " turned the other way: " + area);
        }
        // the ring's outer soft edge fades to clear
        boolean clear = false;
        for (int i = 0; i < s.vertices(); i++) clear |= s.colour(i) == 0x00FFFFFF;
        assertTrue(clear);
    }
}
