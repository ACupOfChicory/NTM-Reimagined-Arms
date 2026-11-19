package acup.gunfeel.gl54;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

// The magazine window against an inventory (MagSession): the magazine never leaves its slot, nothing is held by the window,
// so no sequence of open, move, close and outside changes loses a magazine or a grenade, or makes one.
class MagSessionTest {
    record Stack(boolean mag, List<String> rounds, int count) {
        static final Stack NONE = new Stack(false, List.of(), 0);

        static Stack mag(int count, String... rounds) {
            return new Stack(true, List.of(rounds), count);
        }
    }

    static final MagSession.Ops<Stack> OPS = new MagSession.Ops<>() {
        public boolean isEmpty(Stack s) {
            return s.count == 0;
        }

        public int count(Stack s) {
            return s.count;
        }

        public int max(Stack s) {
            return 4;
        }

        public Stack withCount(Stack s, int c) {
            return c == 0 ? Stack.NONE : new Stack(s.mag, s.rounds, c);
        }

        public boolean same(Stack a, Stack b) {
            return a.mag == b.mag && a.rounds.equals(b.rounds);
        }

        public boolean equal(Stack a, Stack b) {
            return a.equals(b);
        }

        public Stack withRounds(Stack s, List<String> r) {
            return new Stack(true, r, 1);
        }

        public List<String> rounds(Stack s) {
            return s.rounds;
        }

        public Stack empty() {
            return Stack.NONE;
        }
    };

    static final class Inv implements MagSession.Slots<Stack> {
        final Stack[] a = new Stack[41];

        Inv() {
            Arrays.fill(a, Stack.NONE);
        }

        public Stack get(int i) {
            return a[i];
        }

        public void set(int i, Stack s) {
            a[i] = s;
        }

        int magazines() {
            return Arrays.stream(a).mapToInt(s -> s.mag ? s.count : 0).sum();
        }

        // every grenade in the inventory's magazines, each counted per magazine
        int grenades() {
            return Arrays.stream(a).mapToInt(s -> s.mag ? s.rounds.size() * s.count : 0).sum();
        }

        void fillOthers(int except) {
            for (int i = 0; i < 36; i++) if (i != except) a[i] = new Stack(false, List.of("dirt"), 1);
        }
    }

    @Test
    void openMoveClose() {
        Inv inv = new Inv();
        inv.a[3] = Stack.mag(1, "he", "ap");
        var s = MagSession.open(OPS, inv, 3);
        assertNotNull(s);
        assertEquals(3, s.edited());
        assertEquals(List.of("he", "ap"), s.rounds());
        // a grenade put in: the stack has it at once, before any close
        assertTrue(s.write(List.of("smoke", "he", "ap")));
        assertEquals(List.of("smoke", "he", "ap"), inv.a[3].rounds);
        assertTrue(s.write(List.of("ap")));
        assertEquals(List.of("ap"), inv.a[3].rounds);
        s.close();
        assertEquals(1, inv.magazines());
        assertEquals(Stack.mag(1, "ap"), inv.a[3]);
    }

    @Test
    void aCrashAnywhereLosesNothing() {
        // the window is never closed (a server crash): the stack already holds what was moved
        Inv inv = new Inv();
        inv.a[0] = Stack.mag(1, "he", "he");
        var s = MagSession.open(OPS, inv, 0);
        s.write(List.of("he"));
        assertEquals(1, inv.magazines());
        assertEquals(1, inv.grenades());
    }

    @Test
    void theItemRemovedFromOutsideEndsTheWindowWithNoDupe() {
        Inv inv = new Inv();
        inv.a[5] = Stack.mag(1, "he", "ap");
        var s = MagSession.open(OPS, inv, 5);
        assertTrue(s.valid());
        inv.a[5] = Stack.NONE; // /clear, death, another mod
        assertFalse(s.valid());
        assertFalse(s.write(List.of("he")));
        s.close();
        assertEquals(0, inv.magazines());
        assertEquals(Stack.NONE, inv.a[5]);
    }

    @Test
    void anotherStackPutInTheSlotIsNotTouched() {
        Inv inv = new Inv();
        inv.a[1] = Stack.mag(1, "he");
        var s = MagSession.open(OPS, inv, 1);
        inv.a[1] = Stack.mag(1, "acid", "acid"); // swapped for another magazine
        assertFalse(s.valid());
        assertFalse(s.write(List.of("he", "he")));
        s.close();
        assertEquals(Stack.mag(1, "acid", "acid"), inv.a[1]);
    }

    @Test
    void aCountChangedFromOutsideEndsTheWindow() {
        Inv inv = new Inv();
        inv.a[1] = Stack.mag(1, "he");
        var s = MagSession.open(OPS, inv, 1);
        inv.a[1] = Stack.mag(2, "he");
        assertFalse(s.valid());
    }

    @Test
    void aStackOfThreeGivesOneToAFreeSlot() {
        Inv inv = new Inv();
        inv.a[2] = Stack.mag(3, "he", "he");
        var s = MagSession.open(OPS, inv, 2);
        assertNotNull(s);
        assertEquals(0, s.edited());
        assertEquals(Stack.mag(2, "he", "he"), inv.a[2]);
        assertEquals(Stack.mag(1, "he", "he"), inv.a[0]);
        assertEquals(3, inv.magazines());
        // edited: it stays apart, nothing is merged into the others
        s.write(List.of("he"));
        s.close();
        assertEquals(3, inv.magazines());
        assertEquals(Stack.mag(2, "he", "he"), inv.a[2]);
        assertEquals(Stack.mag(1, "he"), inv.a[0]);
        assertEquals(5, inv.grenades());
    }

    @Test
    void aStackOfThreeComesBackTogetherWhenIdenticalAgain() {
        Inv inv = new Inv();
        inv.a[2] = Stack.mag(3, "he", "he");
        var s = MagSession.open(OPS, inv, 2);
        s.close();
        assertEquals(Stack.mag(3, "he", "he"), inv.a[2]);
        assertEquals(Stack.NONE, inv.a[0]);
        // moved out and back in: identical again, so merged
        s = MagSession.open(OPS, inv, 2);
        s.write(List.of("he"));
        s.write(List.of("he", "he"));
        s.close();
        assertEquals(Stack.mag(3, "he", "he"), inv.a[2]);
        assertEquals(3, inv.magazines());
        assertEquals(6, inv.grenades());
    }

    @Test
    void aFullStackIsNotOverfilledOnTheWayBack() {
        Inv inv = new Inv();
        inv.a[2] = Stack.mag(4, "he");
        var s = MagSession.open(OPS, inv, 2);
        assertEquals(Stack.mag(3, "he"), inv.a[2]);
        inv.a[2] = Stack.mag(4, "he"); // someone topped the stack up meanwhile
        s.close();
        assertEquals(Stack.mag(4, "he"), inv.a[2]);
        assertEquals(Stack.mag(1, "he"), inv.a[s.edited()]);
        assertEquals(5, inv.magazines());
    }

    @Test
    void theSourceGoneTheMagazineStaysWhereItIs() {
        Inv inv = new Inv();
        inv.a[2] = Stack.mag(3, "he");
        var s = MagSession.open(OPS, inv, 2);
        inv.a[2] = Stack.NONE;
        s.close();
        assertEquals(Stack.mag(1, "he"), inv.a[0]);
    }

    @Test
    void noFreeSlotAStackOfSeveralDoesNotOpen() {
        Inv inv = new Inv();
        inv.fillOthers(7);
        inv.a[7] = Stack.mag(3, "he");
        assertNull(MagSession.open(OPS, inv, 7));
        // and nothing was moved
        assertEquals(Stack.mag(3, "he"), inv.a[7]);
        assertEquals(3, inv.magazines());
    }

    @Test
    void noFreeSlotAStackOfOneIsEditedInPlace() {
        Inv inv = new Inv();
        inv.fillOthers(7);
        inv.a[7] = Stack.mag(1, "he");
        var s = MagSession.open(OPS, inv, 7);
        assertNotNull(s);
        assertEquals(7, s.edited());
        s.write(List.of("he", "he"));
        s.close();
        assertEquals(Stack.mag(1, "he", "he"), inv.a[7]);
    }

    @Test
    void theOffhandStackSplitsToo() {
        Inv inv = new Inv();
        inv.a[40] = Stack.mag(2, "ap");
        var s = MagSession.open(OPS, inv, 40);
        assertEquals(0, s.edited());
        assertEquals(Stack.mag(1, "ap"), inv.a[40]);
        s.close();
        assertEquals(Stack.mag(2, "ap"), inv.a[40]);
    }

    @Test
    void nothingToOpenInAnEmptySlot() {
        assertNull(MagSession.open(OPS, new Inv(), 4));
    }

    @Test
    void randomWalksNeverMakeOrLoseAnything() {
        var rnd = new java.util.Random(7);
        String[] keys = {"he", "ap", "smoke"};
        for (int round = 0; round < 500; round++) {
            Inv inv = new Inv();
            int n = 1 + rnd.nextInt(4);
            List<String> start = new ArrayList<>();
            for (int i = rnd.nextInt(8); i > 0; i--) start.add(keys[rnd.nextInt(3)]);
            inv.a[rnd.nextInt(36)] = new Stack(true, start, n);
            int slot = 0;
            while (!inv.a[slot].mag) slot++;
            var s = MagSession.open(OPS, inv, slot);
            if (s == null) continue;
            int grenades = inv.grenades();
            List<String> now = new ArrayList<>(s.rounds());
            for (int step = 0; step < 6; step++) {
                // grenades only move between the window and the stack here: the count of both is kept
                if (!now.isEmpty() && rnd.nextBoolean()) {
                    now.remove(0);
                    s.write(now);
                    grenades--;
                } else if (now.size() < 8) {
                    now.add(0, keys[rnd.nextInt(3)]);
                    s.write(now);
                    grenades++;
                }
                assertEquals(n, inv.magazines());
            }
            s.close();
            assertEquals(n, inv.magazines());
            // the grenades of the others are untouched, the edited one holds `now`
            assertEquals(grenades, inv.grenades());
        }
    }
}
