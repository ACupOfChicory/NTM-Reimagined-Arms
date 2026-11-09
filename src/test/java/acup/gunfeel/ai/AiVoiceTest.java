package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.List;

import org.junit.jupiter.api.Test;

import acup.gunfeel.ai.AiVoice.Heard;
import acup.gunfeel.ai.AiVoice.Line;
import acup.gunfeel.ai.AiVoice.Said;

// AI mobs' call-outs: not too often, not over one another, an urgent one over that; a voice of its own each.
class AiVoiceTest {
    static final long NEVER = Long.MIN_VALUE / 2;
    /** a mob's squad, and another's */
    static final Object ME = new Object(), OTHER = new Object();

    @Test
    void oneMobNotTooOften() {
        ArrayDeque<Said> r = new ArrayDeque<>();
        assertTrue(AiVoice.allowed(r, NEVER, 0, 0, Line.RELOADING, 1000, ME));
        assertFalse(AiVoice.allowed(r, 1000, 0, 0, Line.CONTACT, 1000 + AiVoice.BOT_GAP - 1, ME));
        assertTrue(AiVoice.allowed(r, 1000, 0, 0, Line.CONTACT, 1000 + AiVoice.BOT_GAP, ME));
        // a grenade, seen or its own, it says however lately it spoke
        assertTrue(AiVoice.allowed(r, 1000, 0, 0, Line.GRENADE, 1001, ME));
        assertTrue(AiVoice.allowed(r, 1000, 0, 0, Line.FRAG_OUT, 1001, ME));
    }

    @Test
    void noTwoNearAtOnce() {
        ArrayDeque<Said> r = new ArrayDeque<>();
        r.add(new Said(0, 0, Line.RELOADING, 1000, OTHER));
        assertFalse(AiVoice.allowed(r, NEVER, 3, 0, Line.CONTACT, 1010, ME));
        // farther off, or later, it may
        assertTrue(AiVoice.allowed(r, NEVER, AiVoice.CHATTER_NEAR + 1, 0, Line.CONTACT, 1010, ME));
        assertTrue(AiVoice.allowed(r, NEVER, 3, 0, Line.CONTACT, 1000 + AiVoice.CHATTER, ME));
        assertTrue(AiVoice.allowed(r, NEVER, 3, 0, Line.MAN_DOWN, 1010, ME));
    }

    @Test
    void theSameLineNotAgainNear() {
        ArrayDeque<Said> r = new ArrayDeque<>();
        r.add(new Said(0, 0, Line.RELOADING, 1000, OTHER));
        assertFalse(AiVoice.allowed(r, NEVER, 12, 0, Line.RELOADING, 1100, ME));
        assertTrue(AiVoice.allowed(r, NEVER, AiVoice.LINE_NEAR + 1, 0, Line.RELOADING, 1100, ME));
        assertTrue(AiVoice.allowed(r, NEVER, 12, 0, Line.RELOADING, 1000 + AiVoice.LINE_GAP, ME));
        r.clear();
        r.add(new Said(0, 0, Line.GRENADE, 1000, OTHER));
        assertFalse(AiVoice.allowed(r, NEVER, 12, 0, Line.GRENADE, 1000 + AiVoice.URGENT_GAP - 1, ME));
        assertTrue(AiVoice.allowed(r, NEVER, 12, 0, Line.GRENADE, 1000 + AiVoice.URGENT_GAP, ME));
    }

    @Test
    void oldLinesAreLetGo() {
        ArrayDeque<Said> r = new ArrayDeque<>();
        r.add(new Said(0, 0, Line.CONTACT, 1000, OTHER));
        AiVoice.allowed(r, NEVER, 0, 0, Line.CONTACT, 1000 + AiVoice.LINE_GAP + 1, ME);
        assertTrue(r.isEmpty());
    }

    @Test
    void everyVoiceIsUsedAndAMobKeepsItsOwn() {
        int[] n = new int[AiVoice.VOICES];
        for (int id = 0; id < 300; id++) {
            int v = AiVoice.voice(id);
            assertEquals(v, AiVoice.voice(id));
            n[v]++;
        }
        for (int v = 0; v < AiVoice.VOICES; v++) assertTrue(n[v] > 60, "voice " + v + ": " + n[v]);
        assertEquals("ntmgunfeel:ai.voice.v1.frag_out", AiVoice.sound(0, Line.FRAG_OUT).toString());
    }

    @Test
    void theMostASecondIsCountedBySquad() {
        Object a = new Object(), b = new Object();
        // a: three within a second; b: two, a second apart
        List<Heard> h = List.of(new Heard(0, a), new Heard(5, b), new Heard(10, a), new Heard(19, a), new Heard(25, b), new Heard(40, a));
        assertEquals(3, AiVoice.mostASecond(h));
        assertEquals(1, AiVoice.mostASecond(List.of(new Heard(0, a), new Heard(AiVoice.SECOND, a), new Heard(0, b))));
        assertEquals(0, AiVoice.mostASecond(List.of()));
    }

    @Test
    void aSquadSaysTwoLinesASecondAtMostUrgentOrNot() {
        ArrayDeque<Said> r = new ArrayDeque<>();
        r.add(new Said(0, 0, Line.MAN_DOWN, 1000, ME));
        r.add(new Said(20, 0, Line.FRAG_OUT, 1005, ME));
        assertFalse(AiVoice.allowed(r, NEVER, 40, 0, Line.GRENADE, 1010, ME));
        assertTrue(AiVoice.allowed(r, NEVER, 40, 0, Line.GRENADE, 1010, OTHER));
        assertTrue(AiVoice.allowed(r, NEVER, 40, 0, Line.GRENADE, 1000 + AiVoice.SECOND, ME));
    }
}
