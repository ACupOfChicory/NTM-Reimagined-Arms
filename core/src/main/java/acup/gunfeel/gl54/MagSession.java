package acup.gunfeel.gl54;

import java.util.List;

// One open magazine window against the player's inventory, on any kind of stack (the game's ItemStack in MagItem, a plain
// record in the tests). The magazine being edited never leaves its inventory slot and the grenades are written to the stack
// as they move, so nothing is held by the window: a crash, a death or another mod costs nothing and dupes nothing.
//  - a stack of one is edited where it lies;
//  - a stack of several gives one magazine to a free slot (0..35) and that one is edited alone; with no free slot the window
//    does not open;
//  - on close the edited magazine goes back into the stack it came from when they are identical again;
//  - if the stack in the edited slot is not what the window last wrote (taken, swapped, replaced by anyone) the window is
//    no longer valid and the game closes it; it writes nothing more.
public final class MagSession<S> {
    public static final int FREE_SLOTS = 36;

    // what the session needs of a stack; stacks are values, never changed in place
    public interface Ops<S> {
        boolean isEmpty(S s);

        int count(S s);

        int max(S s);

        S withCount(S s, int count);

        // the same kind of magazine with the same grenades (count apart)
        boolean same(S a, S b);

        // exactly equal, count included
        boolean equal(S a, S b);

        // the magazine s (one of it) holding these grenades, its other data kept (an empty magazine when none)
        S withRounds(S s, List<String> rounds);

        List<String> rounds(S s);

        S empty();
    }

    public interface Slots<S> {
        S get(int slot);

        void set(int slot, S stack);
    }

    private final Ops<S> ops;
    private final Slots<S> slots;
    private final int edited, source;
    private S expected;

    private MagSession(Ops<S> ops, Slots<S> slots, int edited, int source) {
        this.ops = ops;
        this.slots = slots;
        this.edited = edited;
        this.source = source;
        this.expected = slots.get(edited);
    }

    // opens the window for the magazine in `slot`; null when it cannot (a stack of several and no free slot)
    public static <S> MagSession<S> open(Ops<S> ops, Slots<S> slots, int slot) {
        S s = slots.get(slot);
        if (ops.isEmpty(s)) return null;
        if (ops.count(s) == 1) return new MagSession<>(ops, slots, slot, -1);
        int free = -1;
        for (int i = 0; i < FREE_SLOTS && free < 0; i++) if (i != slot && ops.isEmpty(slots.get(i))) free = i;
        if (free < 0) return null;
        slots.set(free, ops.withCount(s, 1));
        slots.set(slot, ops.withCount(s, ops.count(s) - 1));
        return new MagSession<>(ops, slots, free, slot);
    }

    // the inventory slot of the magazine being edited
    public int edited() {
        return edited;
    }

    // the grenades the window starts with
    public List<String> rounds() {
        return ops.rounds(slots.get(edited));
    }

    // is the stack in the slot still the one the window wrote
    public boolean valid() {
        S now = slots.get(edited);
        return !ops.isEmpty(now) && ops.equal(now, expected);
    }

    // the grenades moved: the stack holds them now; false (and nothing written) when the slot was changed from outside
    public boolean write(List<String> rounds) {
        if (!valid()) return false;
        S next = ops.withRounds(slots.get(edited), rounds);
        slots.set(edited, next);
        expected = next;
        return true;
    }

    // closing: the magazine joins the stack it was taken from when they are identical again (and it fits); a window that is no
    // longer valid does nothing
    public void close() {
        if (!valid() || source < 0) return;
        S src = slots.get(source);
        S mine = slots.get(edited);
        if (ops.isEmpty(src) || !ops.same(src, mine) || ops.count(src) >= ops.max(src)) return;
        slots.set(source, ops.withCount(src, ops.count(src) + 1));
        slots.set(edited, ops.empty());
        expected = ops.empty();
    }
}
