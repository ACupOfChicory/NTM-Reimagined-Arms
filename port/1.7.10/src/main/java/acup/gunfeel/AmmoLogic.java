package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

// port: new for 1.7.10. The searches of 26.2's AmmoLock (0762086): count, pick, the taking in reload and the putting
// back in unload, line for line, written once against two small interfaces instead of Container and ItemStack. AmmoLock
// runs them on IInventory and NTM's ammo bags (ItemAmmoBag.InventoryAmmoBag), the tests on a made-up inventory
// (AmmoLogicTest). No game classes here.
public final class AmmoLogic {
    public static final int INFINITE = -1;

    private AmmoLogic() {
    }

    // one inventory: the player's or an ammo bag's
    public interface Slots<S> {
        int size();

        S get(int i); // null: empty

        void set(int i, S s);

        void remove(int i, int n);

        // the most that slot holds of it
        int max(S s);
    }

    // what the game says about an ammo type and a stack
    public interface Kit<T, S> {
        boolean matches(T type, S s);

        int count(S s);

        // the ammo bag that stack is, as slots; null when it is none
        Slots<S> bag(S s);

        boolean infinite(S s);

        // same item and data: they stack
        boolean same(S a, S b);

        S copy(S s, int n);
    }

    // rounds of that ammo (items) in the inventory and its ammo bags; INFINITE when an infinite bag holds it
    public static <T, S> int count(T type, Slots<S> inv, Kit<T, S> kit) {
        if (type == null || inv == null) return 0;
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            S s = inv.get(i);
            if (s == null) continue;
            if (kit.matches(type, s)) n += kit.count(s);
            Slots<S> bag = kit.bag(s);
            if (bag == null) continue;
            boolean infinite = kit.infinite(s);
            for (int j = 0; j < bag.size(); j++) {
                S in = bag.get(j);
                if (in == null || !kit.matches(type, in)) continue;
                if (infinite) return INFINITE;
                n += kit.count(in);
            }
        }
        return n;
    }

    // the type an empty magazine loads: a locked gun its own (none while there is none: it waits), else the type chosen
    // in the menu while there is any, else the first found as NTM does
    // found: in the inventory, then in ammo bags, then in infinite bags; null = none there
    public static <T, S> T pick(List<T> accepted, T current, boolean locked, T chosen, Slots<S> inv, Kit<T, S> kit) {
        if (locked) return current != null && count(current, inv, kit) != 0 ? current : null;
        if (chosen != null && accepted.contains(chosen) && count(chosen, inv, kit) != 0) return chosen;
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < inv.size(); i++) {
                S s = inv.get(i);
                if (s == null) continue;
                if (pass == 0) {
                    for (T c : accepted) if (kit.matches(c, s)) return c;
                    continue;
                }
                Slots<S> bag = kit.bag(s);
                if (bag == null || kit.infinite(s) != (pass == 2)) continue;
                for (int j = 0; j < bag.size(); j++) {
                    S in = bag.get(j);
                    if (in != null) for (T c : accepted) if (kit.matches(c, in)) return c;
                }
            }
        }
        return null;
    }

    // items a reload wants: enough to fill the magazine, each item `per` rounds, at most `limit` (NTM's loadLimit)
    public static int want(int capacity, int loaded, int per, int limit) {
        per = Math.max(1, per);
        return Math.min((int) Math.ceil((double) (capacity - loaded) / per), limit);
    }

    // reload's taking: up to `want` items of that type from the inventory, then ammo bags, then infinite bags (taken
    // from those without removing); {items taken, 1 when some came out of a bag that is not infinite}
    public static <T, S> int[] take(T type, int want, Slots<S> inv, Kit<T, S> kit) {
        int took = 0;
        boolean fromBag = false;
        for (int pass = 0; pass < 3 && took < want; pass++) {
            for (int i = 0; i < inv.size() && took < want; i++) {
                S s = inv.get(i);
                if (s == null) continue;
                if (pass == 0) {
                    if (!kit.matches(type, s)) continue;
                    int n = Math.min(want - took, kit.count(s));
                    inv.remove(i, n);
                    took += n;
                    continue;
                }
                Slots<S> bag = kit.bag(s);
                boolean infinite = kit.infinite(s);
                if (bag == null || infinite != (pass == 2)) continue;
                for (int j = 0; j < bag.size() && took < want; j++) {
                    S in = bag.get(j);
                    if (in == null || !kit.matches(type, in)) continue;
                    int n = infinite ? want - took : Math.min(want - took, kit.count(in));
                    if (!infinite) {
                        bag.remove(j, n);
                        fromBag = true;
                    }
                    took += n;
                }
            }
        }
        return new int[] {took, fromBag ? 1 : 0};
    }

    // unload's putting back of `left` (rounds as items, taken from a bag): into the bags of the inventory that hold that
    // ammo, onto its stacks there first, then into their empty slots; what does not fit comes back (null: all fitted)
    public static <T, S> S toBags(T type, S left, Slots<S> inv, Kit<T, S> kit) {
        int remaining = left == null ? 0 : kit.count(left);
        if (remaining <= 0) return null;
        List<Slots<S>> bags = new ArrayList<Slots<S>>();
        for (int i = 0; i < inv.size(); i++) {
            S s = inv.get(i);
            Slots<S> bag = s == null || kit.infinite(s) ? null : kit.bag(s);
            if (bag == null) continue;
            for (int j = 0; j < bag.size(); j++) {
                S in = bag.get(j);
                if (in != null && kit.matches(type, in)) {
                    bags.add(bag);
                    break;
                }
            }
        }
        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            for (Slots<S> bag : bags) {
                for (int j = 0; j < bag.size() && remaining > 0; j++) {
                    S in = bag.get(j);
                    if (pass == 0 && in != null && kit.same(in, left)) {
                        int n = Math.min(remaining, bag.max(in) - kit.count(in));
                        if (n <= 0) continue;
                        bag.set(j, kit.copy(in, kit.count(in) + n));
                        remaining -= n;
                    } else if (pass == 1 && in == null) {
                        int n = Math.min(remaining, bag.max(left));
                        bag.set(j, kit.copy(left, n));
                        remaining -= n;
                    }
                }
            }
        }
        return remaining > 0 ? kit.copy(left, remaining) : null;
    }
}
