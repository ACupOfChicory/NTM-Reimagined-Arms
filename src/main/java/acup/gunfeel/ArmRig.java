package acup.gunfeel;

import java.util.*;
import java.util.function.Function;

/**
 * A gun's arm data as its keys hold it. The game (Arms) and the arm editor both read it here, so the arms in the game
 * are what the editor wrote; writing back keeps every entry nobody edited exactly as it was written (named moments
 * like "release+300", an absent side or wrist turn, "#last", the numbers' own spelling).
 *
 * Tracks: "<ANIMATION>[/<bus>] entry entry; <ANIMATION> entry ..." (armLeftPath, armLeftCarryKeys, armLeftKeys,
 * armLeftHide, armLeftAdjustments). armLeftCarry is one line, armPathTimes names moments ("release=2410").
 */
final class ArmRig {
    private ArmRig() {}

    // the side a hand comes to a part from when the key does not say: from behind the gun
    static final float[] DEFAULT_SIDE = {0F, 0F, -1F};

    // ---------------------------------------------------------------- numbers

    // the shortest spelling that reads back as the same float ("2", "0.625", "-0")
    static String number(float v) {
        if (v == 0F) return Float.floatToRawIntBits(v) < 0 ? "-0" : "0";
        if (v == Math.rint(v) && Math.abs(v) < 1e7F) return Integer.toString((int) v);
        return Float.toString(v);
    }

    static String vector(float[] v) {
        return number(v[0]) + "," + number(v[1]) + "," + number(v[2]);
    }

    // "x,y,z" (anything after the third number is ignored, as the game always did)
    static float[] xyz(String v) {
        String[] t = v.split(",");
        return new float[] {Float.parseFloat(t[0]), Float.parseFloat(t[1]), Float.parseFloat(t[2])};
    }

    // "RELOAD/PumpGrip" -> "RELOAD/PumpGrip", "reload" -> "RELOAD": the animation upper case, the bus as written
    static String animation(String head) {
        int slash = head.indexOf('/');
        return slash < 0 ? head.toUpperCase(Locale.ROOT) : head.substring(0, slash).toUpperCase(Locale.ROOT) + head.substring(slash);
    }

    // ---------------------------------------------------------------- tracks, kept as written

    interface Entry {
        String text();
    }

    static final class Segment<E extends Entry> {
        final String head;
        final List<E> entries;
        private final String raw;
        private final List<String> rawTexts;

        private Segment(String raw, String head, List<E> entries) {
            this.raw = raw;
            this.head = head;
            this.entries = entries;
            this.rawTexts = raw == null ? null : texts();
        }

        private List<String> texts() {
            var out = new ArrayList<String>(entries.size());
            for (E e : entries) out.add(e.text());
            return out;
        }

        boolean edited() {
            return raw == null || !texts().equals(rawTexts);
        }

        private String text() {
            if (!edited()) return raw;
            String lead = raw == null ? " " : raw.substring(0, raw.length() - raw.stripLeading().length());
            String trail = raw == null ? "" : raw.substring(raw.stripTrailing().length());
            var s = new StringBuilder(lead).append(head);
            for (String t : texts()) s.append(' ').append(t);
            return s.append(trail).toString();
        }
    }

    static final class Tracks<E extends Entry> {
        final List<Segment<E>> segments = new ArrayList<>();
        private final String source;

        private Tracks(String source, Function<String, E> entry) {
            this.source = source;
            for (String piece : source.split(";", -1)) {
                String[] w = piece.trim().split("\\s+");
                var list = new ArrayList<E>();
                for (int j = 1; j < w.length; j++) list.add(entry.apply(w[j]));
                segments.add(new Segment<>(piece, w[0], list));
            }
        }

        // the last segment written for exactly this head (the one the game uses), or null
        Segment<E> find(String head) {
            Segment<E> found = null;
            for (var s : segments) if (s.head.equals(head)) found = s;
            return found;
        }

        // the last segment the game plays for this animation ("reload" counts as RELOAD), or null
        Segment<E> forAnimation(String name) {
            Segment<E> found = null;
            for (var s : segments) if (animation(s.head).equals(name)) found = s;
            return found;
        }

        Segment<E> add(String head) {
            var s = new Segment<E>(null, head, new ArrayList<>());
            segments.add(s);
            return s;
        }

        Segment<E> findOrAdd(String head) {
            var s = find(head);
            if (s == null) segments.add(s = new Segment<>(null, head, new ArrayList<>()));
            return s;
        }

        // the text back: as read when nothing changed, else each untouched segment and entry as it was written
        String write() {
            boolean edited = false;
            for (var s : segments) edited |= s.edited();
            if (!edited) return source;
            var out = new ArrayList<String>();
            for (var s : segments) {
                if (s.raw != null && s.raw.isBlank()) continue;
                if (s.edited() && s.entries.isEmpty()) continue;
                out.add(s.text());
            }
            if (!out.isEmpty()) out.set(0, out.get(0).stripLeading());
            return String.join(";", out);
        }
    }

    // ---------------------------------------------------------------- the path (armLeftPath, armRightPath)

    // one key: ms -> grip (part null) or a point of a part as drawn
    record PathKey(float ms, String part, int nth, float[] at, float[] side, float[] shoulder, int contact, boolean followShoulder, float roll) {
    }

    // "ms:grip" or "ms:Part[#n|#last][!contact]@x,y,z[/dx,dy,dz][>[~]sx,sy,sz][^roll]"; ms may be a named moment
    static final class PathEntry implements Entry {
        private final String raw;
        private final boolean bad;
        private String parsed;
        String time = "0";              // as written: ms, or a moment of armPathTimes with an optional +ms / -ms
        String part = "grip";           // "grip" while point is null
        int nth;                        // which drawn copy of the part, -1 = the last
        int contact;                    // 0 = where the animation puts the hand, 1..13 = ArmContact
        float[] point;                  // null = the grip
        float[] side;                   // null = DEFAULT_SIDE
        float[] shoulder;               // null = no shift
        boolean follow;                 // the shoulder follows the point (">~")
        float roll = Float.NaN;         // wrist turn, NaN = armLoadRoll
        float ms = Float.NaN;           // the time in ms, filled by sorted() for the editor

        private PathEntry(String raw) {
            this.raw = raw;
            boolean ok = true;
            try {
                int colon = raw.indexOf(':');
                time = raw.substring(0, colon);
                String t = raw.substring(colon + 1);
                if (!t.equalsIgnoreCase("grip")) {
                    int at = t.indexOf('@');
                    String p = t.substring(0, at), pt = t.substring(at + 1);
                    int marker = p.indexOf('!');
                    contact = marker < 0 ? 0 : ArmContact.parse(p.substring(marker + 1));
                    if (marker >= 0) p = p.substring(0, marker);
                    int hash = p.indexOf('#');
                    if (hash >= 0) {
                        String n = p.substring(hash + 1);
                        nth = n.equalsIgnoreCase("last") ? -1 : Integer.parseInt(n);
                        p = p.substring(0, hash);
                    }
                    int wrist = pt.indexOf('^');
                    roll = wrist < 0 ? Float.NaN : Float.parseFloat(pt.substring(wrist + 1));
                    if (wrist >= 0) pt = pt.substring(0, wrist);
                    int gt = pt.indexOf('>');
                    follow = gt >= 0 && pt.substring(gt + 1).startsWith("~");
                    shoulder = gt < 0 ? null : xyz(pt.substring(gt + (follow ? 2 : 1)));
                    if (gt >= 0) pt = pt.substring(0, gt);
                    int sl = pt.indexOf('/');
                    side = sl < 0 ? null : xyz(pt.substring(sl + 1));
                    point = xyz(sl < 0 ? pt : pt.substring(0, sl));
                    part = p;
                }
            } catch (RuntimeException e) {
                ok = false;
            }
            bad = !ok;
            parsed = canonical();
        }

        private PathEntry() {
            raw = null;
            bad = false;
        }

        static PathEntry read(String text) {
            return new PathEntry(text);
        }

        static PathEntry grip(float ms) {
            var e = new PathEntry();
            e.time = number(ms);
            e.ms = ms;
            return e;
        }

        // false = the text could not be read; it is kept as written and the game skips it
        boolean valid() {
            return !bad;
        }

        boolean grip() {
            return point == null;
        }

        // hold this part at this point, or the grip ("grip")
        void attach(String part, float[] point) {
            if (part.equalsIgnoreCase("grip") || point == null) {
                this.part = "grip";
                this.point = null;
                nth = 0;
            } else {
                this.part = part;
                this.point = point.clone();
            }
        }

        // move the key to ms; a key written from a named moment stays written from it ("release+340")
        void retime(float ms, Map<String, Float> times) {
            this.ms = ms;
            String base = momentOf(time, times);
            if (base == null) {
                time = number(ms);
                return;
            }
            float off = ms - times.get(base.trim().toLowerCase(Locale.ROOT));
            time = off == 0F ? base : base + (off > 0F ? "+" : "") + number(off);
        }

        private String canonical() {
            if (bad) return raw;
            if (point == null) return time + ":grip";
            var s = new StringBuilder(time).append(':').append(part);
            if (nth != 0) s.append('#').append(nth < 0 ? "last" : Integer.toString(nth));
            if (contact > 0) s.append('!').append(contact);
            s.append('@').append(vector(point));
            if (side != null) s.append('/').append(vector(side));
            if (shoulder != null) s.append('>').append(follow ? "~" : "").append(vector(shoulder));
            if (!Float.isNaN(roll)) s.append('^').append(number(roll));
            return s.toString();
        }

        @Override
        public String text() {
            String now = canonical();
            return raw != null && now.equals(parsed) ? raw : now;
        }

        // the key as the game uses it, or null when it cannot be read (the game skips it)
        PathKey key(Map<String, Float> times) {
            if (bad) return null;
            float t;
            try {
                t = pathMs(time, times);
            } catch (RuntimeException e) {
                return null;
            }
            if (point == null) return new PathKey(t, null, 0, null, null, null, 0, false, 0F);
            return new PathKey(t, part, nth, point.clone(), side != null ? side.clone() : DEFAULT_SIDE,
                    shoulder != null ? shoulder.clone() : new float[3], contact, follow, roll);
        }
    }

    static Tracks<PathEntry> path(String src) {
        return new Tracks<>(src, PathEntry::read);
    }

    // armPathTimes "release=2410, drop=300": names (lower case) -> ms; bad entries are skipped
    static Map<String, Float> pathTimes(String src) {
        Map<String, Float> times = new HashMap<>();
        for (String t : src.split(",")) {
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            try {
                times.put(t.substring(0, eq).trim().toLowerCase(Locale.ROOT), Float.parseFloat(t.substring(eq + 1).trim()));
            } catch (NumberFormatException e) {
                // skip
            }
        }
        return times;
    }

    // a key's time: milliseconds, or a name from armPathTimes with an optional +ms / -ms ("release+300")
    static float pathMs(String e, Map<String, Float> times) {
        Float whole = times.get(e.toLowerCase(Locale.ROOT));
        if (whole != null) return whole;
        int op = Math.max(e.lastIndexOf('+'), e.lastIndexOf('-'));
        if (op <= 0 || Character.isDigit(e.charAt(0))) return Float.parseFloat(e);
        Float base = times.get(e.substring(0, op).trim().toLowerCase(Locale.ROOT));
        if (base == null) throw new IllegalArgumentException(e);
        return base + Float.parseFloat(e.substring(op));
    }

    // the moment a time is written from, as written ("release" of "release+300"), or null for plain ms
    static String momentOf(String e, Map<String, Float> times) {
        if (times.containsKey(e.toLowerCase(Locale.ROOT))) return e;
        int op = Math.max(e.lastIndexOf('+'), e.lastIndexOf('-'));
        if (op <= 0 || Character.isDigit(e.charAt(0))) return null;
        String base = e.substring(0, op);
        return times.containsKey(base.trim().toLowerCase(Locale.ROOT)) ? base : null;
    }

    // the keys of one animation the game uses, in time order, each with its ms (for the editor)
    static List<PathEntry> sorted(Segment<PathEntry> s, Map<String, Float> times) {
        var out = new ArrayList<PathEntry>();
        if (s == null) return out;
        for (var e : s.entries) {
            var k = e.key(times);
            if (k == null) continue;
            e.ms = k.ms();
            out.add(e);
        }
        out.sort(Comparator.comparingDouble(e -> e.ms));
        return out;
    }

    // armLeftPath as the game plays it: per animation ("RELOAD", "RELOAD/PumpGrip") its keys in time order;
    // of two keys at one time the first stays; entries that cannot be read are skipped
    static Map<String, List<PathKey>> pathKeys(String src, String timeSrc) {
        var times = pathTimes(timeSrc);
        Map<String, List<PathKey>> out = new HashMap<>();
        for (var s : path(src).segments) {
            if (s.entries.isEmpty()) continue;
            List<PathKey> list = new ArrayList<>();
            for (var e : s.entries) {
                var k = e.key(times);
                if (k != null) list.add(k);
            }
            if (list.isEmpty()) continue;
            list.sort(Comparator.comparingDouble(PathKey::ms));
            for (int j = list.size() - 1; j > 0; j--) if (list.get(j).ms() <= list.get(j - 1).ms()) list.remove(j);
            out.put(animation(s.head), list);
        }
        return out;
    }

    // ---------------------------------------------------------------- simple entries: numbers after a time

    // an entry of carry keys, angle keys, hidden ranges or adjustments: the text and its numbers (null = unreadable)
    record Value(String text, float[] v) implements Entry {
    }

    // carry keys "ms:weight[/busMs|@x,y,z]" -> {t, w, kind (0 live, 1 bus time, 2 fixed point), b, x, y, z}
    static Value carryKey(String e) {
        try {
            float[] k = new float[7];
            int colon = e.indexOf(':');
            k[0] = Float.parseFloat(e.substring(0, colon));
            String rest = e.substring(colon + 1);
            int at = rest.indexOf('@'), sl = rest.indexOf('/');
            if (at >= 0) {
                float[] pt = xyz(rest.substring(at + 1));
                k[2] = 2F;
                k[4] = pt[0];
                k[5] = pt[1];
                k[6] = pt[2];
                rest = rest.substring(0, at);
            } else if (sl >= 0) {
                k[2] = 1F;
                k[3] = Float.parseFloat(rest.substring(sl + 1));
                rest = rest.substring(0, sl);
            }
            k[1] = Float.parseFloat(rest);
            return new Value(e, k);
        } catch (RuntimeException ex) {
            return new Value(e, null);
        }
    }

    static Value carryKey(float t, float w, int kind, float b, float[] at) {
        String s = number(t) + ":" + number(w) + (kind == 1 ? "/" + number(b) : kind == 2 ? "@" + vector(at) : "");
        return carryKey(s);
    }

    static Tracks<Value> carryKeyTracks(String src) {
        return new Tracks<>(src, ArmRig::carryKey);
    }

    // armLeftCarryKeys as the game plays it: per animation its keys; a key not after the one before is skipped
    static Map<String, List<float[]>> carryKeys(String src) {
        Map<String, List<float[]>> out = new HashMap<>();
        for (var s : carryKeyTracks(src).segments) {
            if (s.entries.isEmpty()) continue;
            List<float[]> list = new ArrayList<>();
            for (var e : s.entries) {
                if (e.v() == null) continue;
                if (!list.isEmpty() && e.v()[0] <= list.get(list.size() - 1)[0]) continue;
                list.add(e.v().clone());
            }
            if (list.isEmpty()) continue;
            out.put(animation(s.head), list);
        }
        return out;
    }

    // angle keys (armLeftKeys) "ms:angle" -> {t, angle}
    static Value angleKey(String e) {
        String[] tv = e.split(":");
        if (tv.length != 2) return new Value(e, null);
        try {
            return new Value(e, new float[] {Float.parseFloat(tv[0]), Float.parseFloat(tv[1])});
        } catch (NumberFormatException ex) {
            return new Value(e, null);
        }
    }

    static Tracks<Value> angleKeyTracks(String src) {
        return new Tracks<>(src, ArmRig::angleKey);
    }

    // "RELOAD 0:-90 240:90; INSPECT 0:-90" -> {RELOAD: [0,-90, 240,90], ...}; a key not after the one before and
    // bad entries are skipped; the name is matched upper case and whole
    static Map<String, float[]> angleKeys(String src) {
        Map<String, float[]> out = new HashMap<>();
        for (var s : angleKeyTracks(src).segments) {
            if (s.entries.isEmpty()) continue;
            List<Float> v = new ArrayList<>();
            for (var e : s.entries) {
                if (e.v() == null) continue;
                if (!v.isEmpty() && e.v()[0] <= v.get(v.size() - 2)) continue;
                v.add(e.v()[0]);
                v.add(e.v()[1]);
            }
            if (v.isEmpty()) continue;
            float[] a = new float[v.size()];
            for (int j = 0; j < a.length; j++) a[j] = v.get(j);
            out.put(s.head.toUpperCase(Locale.ROOT), a);
        }
        return out;
    }

    // hidden ranges (armLeftHide) "from-to" ms
    static Value range(String e) {
        String[] ab = e.split("-");
        if (ab.length != 2) return new Value(e, null);
        try {
            return new Value(e, new float[] {Float.parseFloat(ab[0]), Float.parseFloat(ab[1])});
        } catch (NumberFormatException ex) {
            return new Value(e, null);
        }
    }

    static Tracks<Value> rangeTracks(String src) {
        return new Tracks<>(src, ArmRig::range);
    }

    // "RELOAD 0-650; RELOAD_END 0-700" -> {RELOAD: [0,650], ...}; an animation with no readable range is kept empty
    static Map<String, float[]> ranges(String src) {
        Map<String, float[]> out = new HashMap<>();
        for (var s : rangeTracks(src).segments) {
            if (s.entries.isEmpty()) continue;
            List<Float> v = new ArrayList<>();
            for (var e : s.entries) {
                if (e.v() == null) continue;
                v.add(e.v()[0]);
                v.add(e.v()[1]);
            }
            float[] a = new float[v.size()];
            for (int j = 0; j < a.length; j++) a[j] = v.get(j);
            out.put(s.head.toUpperCase(Locale.ROOT), a);
        }
        return out;
    }

    // adjustments (armLeftAdjustments) "ms:right,up,back,tilt,turn,out" -> {t, 6 values}
    static Value adjustment(String e) {
        try {
            int colon = e.indexOf(':');
            float time = Float.parseFloat(e.substring(0, colon));
            var numbers = e.substring(colon + 1).split(",");
            if (numbers.length != 6 || !Float.isFinite(time)) return new Value(e, null);
            var v = new float[7];
            v[0] = time;
            for (int j = 0; j < 6; j++) {
                v[j + 1] = Float.parseFloat(numbers[j]);
                if (!Float.isFinite(v[j + 1])) return new Value(e, null);
            }
            return new Value(e, v);
        } catch (RuntimeException ex) {
            return new Value(e, null);
        }
    }

    static Value adjustment(float time, float[] pose) {
        var s = new StringBuilder(number(time)).append(':');
        for (int j = 0; j < 6; j++) s.append(j > 0 ? "," : "").append(number(pose[j]));
        return adjustment(s.toString());
    }

    static Tracks<Value> adjustmentTracks(String src) {
        return new Tracks<>(src, ArmRig::adjustment);
    }

    // adjustments as the game plays them: per animation (as written) ms -> 6 values; one bad entry anywhere and
    // there are none (IllegalArgumentException)
    static Map<String, TreeMap<Float, float[]>> adjustments(String src) {
        var result = new LinkedHashMap<String, TreeMap<Float, float[]>>();
        for (var s : adjustmentTracks(src).segments) {
            if (s.entries.isEmpty()) continue;
            var values = new TreeMap<Float, float[]>();
            for (var e : s.entries) {
                if (e.v() == null) throw new IllegalArgumentException(e.text());
                values.put(e.v()[0], Arrays.copyOfRange(e.v(), 1, 7));
            }
            result.put(s.head, values);
        }
        return result;
    }

    // one animation's adjustments set to these; entries equal to ones already written stay as written
    static void putAdjustments(Tracks<Value> doc, String animation, SortedMap<Float, float[]> track) {
        var s = doc.findOrAdd(animation);
        var old = new ArrayList<>(s.entries);
        s.entries.clear();
        for (var e : track.entrySet()) {
            Value same = null;
            for (var o : old) {
                if (o.v() != null && o.v()[0] == e.getKey() && Arrays.equals(Arrays.copyOfRange(o.v(), 1, 7), e.getValue())) {
                    same = o;
                    break;
                }
            }
            s.entries.add(same != null ? same : adjustment(e.getKey(), e.getValue()));
        }
    }

    // ---------------------------------------------------------------- what a hand carries (armLeftCarry)

    // "<part> <bus[|bus]> <grab ms> x,y,z[|x,y,z] [dx,dy,dz]"; dir null = DEFAULT_SIDE
    record CarrySpec(String part, String[] buses, int grab, float[][] ats, float[] dir) {
    }

    // null = no carrying (empty or malformed)
    static CarrySpec carry(String src) {
        String[] w = src.split("\\s+");
        if (w.length != 4 && w.length != 5) return null;
        try {
            String[] pts = w[3].split("\\|");
            float[][] at = new float[pts.length][];
            for (int j = 0; j < pts.length; j++) at[j] = xyz(pts[j]);
            float[] dir = w.length == 5 ? xyz(w[4]) : null;
            return new CarrySpec(w[0], w[1].split("\\|"), Integer.parseInt(w[2]), at, dir);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
