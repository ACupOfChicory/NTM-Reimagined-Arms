package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

// AmmoLogic, the ammo lock's and menu's searches (26.2's AmmoLock, 0762086), on a made-up inventory: items are names,
// a bag holds its own slots, an infinite bag is never emptied.
class AmmoLogicTest {
    static final class Stack {
        final String item;
        int n;
        Inv bag;
        boolean infinite;

        Stack(String item, int n) {
            this.item = item;
            this.n = n;
        }

        @Override
        public String toString() {
            return item + "x" + n;
        }
    }

    static final class Inv implements AmmoLogic.Slots<Stack> {
        final Stack[] s;
        final int limit;

        Inv(int size, int limit, Stack... in) {
            s = Arrays.copyOf(in, size);
            this.limit = limit;
        }

        @Override
        public int size() {
            return s.length;
        }

        @Override
        public Stack get(int i) {
            return s[i] == null || s[i].n <= 0 ? null : s[i];
        }

        @Override
        public void set(int i, Stack v) {
            s[i] = v;
        }

        @Override
        public void remove(int i, int n) {
            s[i].n -= n;
            if (s[i].n <= 0) s[i] = null;
        }

        @Override
        public int max(Stack v) {
            return limit;
        }

        int total(String item) {
            int t = 0;
            for (Stack x : s) if (x != null && x.item.equals(item)) t += x.n;
            return t;
        }
    }

    static final AmmoLogic.Kit<String, Stack> KIT = new AmmoLogic.Kit<String, Stack>() {
        @Override
        public boolean matches(String type, Stack s) {
            return s.item.equals(type);
        }

        @Override
        public int count(Stack s) {
            return s.n;
        }

        @Override
        public AmmoLogic.Slots<Stack> bag(Stack s) {
            return s.bag;
        }

        @Override
        public boolean infinite(Stack s) {
            return s.infinite;
        }

        @Override
        public boolean same(Stack a, Stack b) {
            return a.item.equals(b.item);
        }

        @Override
        public Stack copy(Stack s, int n) {
            return new Stack(s.item, n);
        }
    };

    static Stack bag(boolean infinite, Stack... in) {
        Stack b = new Stack(infinite ? "bag_inf" : "bag", 1);
        b.bag = new Inv(8, 64, in);
        b.infinite = infinite;
        return b;
    }

    static final List<String> TAKES = Arrays.asList("ball", "buck", "slug");

    @Test
    void countsInventoryAndBags() {
        Inv inv = new Inv(4, 64, new Stack("buck", 5), bag(false, new Stack("buck", 7), new Stack("slug", 2)), new Stack("buck", 1));
        assertEquals(13, AmmoLogic.count("buck", inv, KIT));
        assertEquals(2, AmmoLogic.count("slug", inv, KIT));
        assertEquals(0, AmmoLogic.count("ball", inv, KIT));
        assertEquals(0, AmmoLogic.count(null, inv, KIT));
        inv.set(3, bag(true, new Stack("slug", 1)));
        assertEquals(AmmoLogic.INFINITE, AmmoLogic.count("slug", inv, KIT));
    }

    // NTM's order: the inventory, then bags, then infinite bags; in each the magazine's order of what it takes
    @Test
    void picksAsNtm() {
        Inv inv = new Inv(4, 64, bag(true, new Stack("ball", 1)), bag(false, new Stack("slug", 3)), new Stack("buck", 2));
        assertEquals("buck", AmmoLogic.pick(TAKES, null, false, null, inv, KIT));
        inv.set(2, null);
        assertEquals("slug", AmmoLogic.pick(TAKES, null, false, null, inv, KIT));
        inv.set(1, null);
        assertEquals("ball", AmmoLogic.pick(TAKES, null, false, null, inv, KIT));
        inv.set(0, null);
        assertNull(AmmoLogic.pick(TAKES, null, false, null, inv, KIT));
        // two in one slot run: the magazine's order
        Inv two = new Inv(2, 64, new Stack("slug", 1), new Stack("ball", 1));
        assertEquals("slug", AmmoLogic.pick(TAKES, null, false, null, two, KIT));
    }

    @Test
    void choiceWhileThereIsAny() {
        Inv inv = new Inv(3, 64, new Stack("buck", 2), new Stack("slug", 2));
        assertEquals("slug", AmmoLogic.pick(TAKES, null, false, "slug", inv, KIT));
        // run out, or one the magazine does not take: NTM's order
        assertEquals("buck", AmmoLogic.pick(TAKES, null, false, "ball", inv, KIT));
        assertEquals("buck", AmmoLogic.pick(TAKES, null, false, "dart", new Inv(1, 64, new Stack("buck", 2)), KIT));
    }

    @Test
    void lockedWaitsForItsOwn() {
        Inv inv = new Inv(2, 64, new Stack("buck", 2));
        assertEquals("buck", AmmoLogic.pick(TAKES, "buck", true, "slug", inv, KIT));
        assertNull(AmmoLogic.pick(TAKES, "slug", true, null, inv, KIT));
        assertNull(AmmoLogic.pick(TAKES, null, true, null, inv, KIT));
    }

    @Test
    void wantsWholeItems() {
        assertEquals(6, AmmoLogic.want(6, 0, 1, 99));
        assertEquals(2, AmmoLogic.want(6, 0, 4, 99)); // 6 rounds at 4 a box: 2 boxes
        assertEquals(1, AmmoLogic.want(6, 4, 4, 99));
        assertEquals(1, AmmoLogic.want(6, 0, 1, 1)); // a shell at a time
        assertEquals(6, AmmoLogic.want(6, 0, 0, 99)); // 0 per item counts as 1
    }

    @Test
    void takesInventoryThenBagsThenInfinite() {
        Stack b = bag(false, new Stack("buck", 3));
        Stack inf = bag(true, new Stack("buck", 1));
        Inv inv = new Inv(4, 64, inf, b, new Stack("buck", 2));
        assertArrayEquals(new int[] {2, 0}, AmmoLogic.take("buck", 2, inv, KIT));
        assertEquals(0, inv.total("buck"));
        assertEquals(3, b.bag.total("buck"));
        assertArrayEquals(new int[] {3, 1}, AmmoLogic.take("buck", 3, inv, KIT));
        assertEquals(0, b.bag.total("buck"));
        // the infinite bag gives all that is wanted and keeps it
        assertArrayEquals(new int[] {40, 0}, AmmoLogic.take("buck", 40, inv, KIT));
        assertEquals(1, inf.bag.total("buck"));
        assertArrayEquals(new int[] {0, 0}, AmmoLogic.take("slug", 5, inv, KIT));
    }

    @Test
    void putsBackIntoBagsThenReturnsTheRest() {
        Stack b = bag(false, new Stack("buck", 60), new Stack("slug", 1));
        Stack other = bag(false, new Stack("slug", 2)); // holds no buck: not a place for it
        Stack inf = bag(true, new Stack("buck", 1)); // never
        Inv inv = new Inv(4, 64, other, inf, b);
        Stack left = AmmoLogic.toBags("buck", new Stack("buck", 10), inv, KIT);
        // onto its stack up to 64, then the bag's empty slots (8 slots, 2 used, 64 each)
        assertNull(left);
        assertEquals(70, b.bag.total("buck"));
        assertEquals(64, b.bag.s[0].n);
        assertEquals(6, b.bag.s[2].n);
        assertEquals(0, other.bag.total("buck"));
        assertEquals(1, inf.bag.total("buck"));
        // a full bag: all of it comes back
        Stack full = bag(false);
        for (int j = 0; j < 8; j++) full.bag.s[j] = new Stack("buck", 64);
        Stack back = AmmoLogic.toBags("buck", new Stack("buck", 5), new Inv(1, 64, full), KIT);
        assertEquals(5, back.n);
        assertNull(AmmoLogic.toBags("buck", null, inv, KIT));
    }
}
