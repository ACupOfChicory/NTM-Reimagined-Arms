package acup.gunfeel.gl54;

import java.util.ArrayList;
import java.util.List;

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

    // the first slot holding exactly those grenades in that order; -1 for none
    public static int exact(List<List<String>> mags, List<String> contents) {
        if (contents.isEmpty()) return -1;
        for (int i = 0; i < mags.size(); i++) if (contents.equals(mags.get(i))) return i;
        return -1;
    }

    // the slot R takes: a magazine with exactly the wanted contents (the first by slot), else the fullest one there is (the
    // first among equals); -1 for none
    public static int pick(List<List<String>> mags, List<String> wanted) {
        int e = exact(mags, wanted);
        if (e >= 0) return e;
        int best = -1;
        for (int i = 0; i < mags.size(); i++) {
            List<String> m = mags.get(i);
            if (m == null || m.isEmpty()) continue;
            if (best < 0 || m.size() > mags.get(best).size()) best = i;
        }
        return best;
    }

    // what R loads: the contents the wheel chose if any magazine has them; a locked gun waits for them; otherwise the fullest
    // magazine there is; nothing there: the chosen contents
    public static List<String> wantedContents(List<String> want, boolean locked, List<List<String>> mags) {
        if (!want.isEmpty() && exact(mags, want) >= 0) return want;
        if (locked && !want.isEmpty()) return want;
        // those exact grenades are gone: the fullest magazine of the same kind (the wheel's choice was HE, HE is still about),
        // and only with none of that kind the fullest there is
        String k = kind(want);
        if (!k.equals(GrenadeKind.MIXED) && !k.equals(GrenadeKind.EMPTY)) {
            int same = fullest(mags, k);
            if (same >= 0) return mags.get(same);
        }
        int best = pick(mags, List.of());
        return best >= 0 ? mags.get(best) : want;
    }

    // would taking `cand` change anything: when it is exactly what is wanted, if the gun's magazine is not; otherwise (no
    // magazine of the wanted contents) only if it holds more or is of another kind
    public static boolean improves(List<String> inserted, List<String> wanted, List<String> cand) {
        if (cand.isEmpty()) return false;
        if (cand.equals(wanted)) return !inserted.equals(wanted);
        return cand.size() > inserted.size() || !kind(cand).equals(kind(inserted));
    }

    // the grenades in the magazines that have exactly those contents (stacks counted), max 9999: the HUD's reserve
    public static int reserve(List<List<String>> mags, int[] counts, List<String> wanted) {
        int n = 0;
        for (int i = 0; i < mags.size(); i++) if (!wanted.isEmpty() && wanted.equals(mags.get(i))) n += wanted.size() * counts[i];
        return Math.min(n, 9999);
    }

    // one wheel entry: magazines with exactly these contents and how many there are
    public record Entry(List<String> contents, int magazines) {
    }

    // the wheel's entries: one per distinct contents, the grenades' kinds in their order, a mixed one last, the fuller first
    public static List<Entry> entries(List<List<String>> mags, int[] counts) {
        java.util.LinkedHashMap<List<String>, Integer> by = new java.util.LinkedHashMap<>();
        for (int i = 0; i < mags.size(); i++) {
            List<String> m = mags.get(i);
            if (m != null && !m.isEmpty()) by.merge(m, counts[i], Integer::sum);
        }
        List<Entry> out = new ArrayList<>();
        by.forEach((c, n) -> out.add(new Entry(c, n)));
        out.sort(java.util.Comparator.<Entry>comparingInt(e -> GrenadeKind.order(kind(e.contents())))
                .thenComparing(e -> -e.contents().size()).thenComparing(e -> String.join(",", e.contents())));
        return out;
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
