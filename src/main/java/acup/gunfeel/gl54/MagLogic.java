package acup.gunfeel.gl54;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

// The rules of the 20 mm magazines in plain lists of keys, with no game classes (tested without the game). A magazine is a
// list of grenade keys (GrenadeKind.key), the first one fires first. The rules are the owner's (design.md, mag-system.md
// section 9): R takes the fullest magazine of the wanted kind (the first by slot among equals); the magazine taken out
// pours into the fullest unfinished one of its kind and what does not fit stays in it; mixed magazines are not poured.
public final class MagLogic {
    public static final int CAPACITY = MagContents.CAPACITY;

    private MagLogic() {
    }

    // "empty", the grenade's key if all are the same, else "mixed"
    public static String kind(List<String> rounds) {
        if (rounds.isEmpty()) return GrenadeKind.EMPTY;
        String first = rounds.get(0);
        for (String r : rounds) if (!r.equals(first)) return GrenadeKind.MIXED;
        return first;
    }

    // the index of the fullest magazine of that kind with at least one grenade, the first among equals; -1 for none
    public static int fullest(List<List<String>> mags, String kind) {
        int best = -1;
        for (int i = 0; i < mags.size(); i++) {
            List<String> m = mags.get(i);
            if (m == null || m.isEmpty() || !kind(m).equals(kind)) continue;
            if (best < 0 || m.size() > mags.get(best).size()) best = i;
        }
        return best;
    }

    // the magazine a leftover pours into: the fullest one of that single kind that is not full; none for a mixed or empty kind
    public static int pourTarget(List<List<String>> mags, String kind) {
        if (kind.equals(GrenadeKind.MIXED) || kind.equals(GrenadeKind.EMPTY)) return -1;
        int best = -1;
        for (int i = 0; i < mags.size(); i++) {
            List<String> m = mags.get(i);
            if (m == null || m.isEmpty() || m.size() >= CAPACITY || !kind(m).equals(kind)) continue;
            if (best < 0 || m.size() > mags.get(best).size()) best = i;
        }
        return best;
    }

    // old poured into target: what the target holds then and what is left of old
    public record Poured(List<String> target, List<String> left) {
    }

    public static Poured pour(List<String> old, List<String> target) {
        int n = Math.min(old.size(), CAPACITY - target.size());
        List<String> t = new ArrayList<>(target);
        t.addAll(old.subList(0, Math.max(n, 0)));
        return new Poured(t, new ArrayList<>(old.subList(Math.max(n, 0), old.size())));
    }

    // what R loads: the selected kind if any magazine of it has a grenade; a locked gun waits for it; otherwise the first
    // kind that is there in the wheel's order; nothing there: the selected one
    public static String wanted(String sel, boolean locked, Predicate<String> available) {
        if (!sel.isEmpty() && available.test(sel)) return sel;
        if (locked && !sel.isEmpty()) return sel;
        for (GrenadeKind k : GrenadeKind.values()) if (available.test(k.key)) return k.key;
        if (available.test(GrenadeKind.MIXED)) return GrenadeKind.MIXED;
        return sel;
    }

    // would a reload change anything: there is a magazine of the wanted kind that holds more than the one in the gun, or
    // the kind in the gun is another
    public static boolean improves(String inGunKind, int inGun, String wanted, int bestOfWanted) {
        if (bestOfWanted <= 0) return false;
        return !inGunKind.equals(wanted) || bestOfWanted > inGun;
    }

    // "2x8 + 5": full magazines and the grenades of the unfinished ones; compact has no spaces round the plus
    public static String label(int full, int rest, boolean compact) {
        String sep = compact ? "+" : " + ";
        String fulls = full + "×" + CAPACITY;
        return full > 0 ? (rest > 0 ? fulls + sep + rest : fulls) : Integer.toString(rest);
    }

    // the grenades in all magazines of that kind and how many of them are full
    public record Stock(int rounds, int full, int rest) {
        public static final Stock NONE = new Stock(0, 0, 0);
    }

    public static Stock stock(List<List<String>> mags, String kind) {
        int rounds = 0, full = 0, rest = 0;
        for (List<String> m : mags) {
            if (m == null || m.isEmpty() || !kind(m).equals(kind)) continue;
            rounds += m.size();
            if (m.size() >= CAPACITY) full++;
            else rest += m.size();
        }
        return new Stock(rounds, full, rest);
    }

    // the same, for the HUD's "reserve": the grenades of that kind in the magazines, max 9999
    public static int reserve(List<List<String>> mags, String kind) {
        return Math.min(stock(mags, kind).rounds(), 9999);
    }

    // the colour (argb) of the HUD's number: the tip of the grenade that fires next, white with none
    public static int numberColor(GunMag gun) {
        String next = gun.next();
        return next.isEmpty() ? 0xFFFFFFFF : GrenadeKind.argbOf(next);
    }

    // LOW AMMO shows with this many left or fewer (a quarter of the magazine, as the other guns' red number), not while reloading
    public static final int LOW = Math.max(1, CAPACITY / 4);

    public static boolean low(int count, boolean reloading) {
        return !reloading && count <= LOW;
    }
}
