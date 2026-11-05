package acup.gunfeel;

import java.util.*;

/**
 * One track of one hand in one animation, as the arm editor edits it: the keys the game plays there, and edits that
 * keep every other entry as it was written. Made anew from the gun's text for each look or edit; {@link #write()}
 * gives the text back.
 */
final class ArmKeys {
    // a key the game plays: its time, the end of a hidden range (else the same time), and its entry in the segment
    record Key(float ms, float to, int entry) {
    }

    final ArmEditor.Track track;
    private final String clip;
    private final Map<String, Float> times;
    private final ArmRig.Tracks<ArmRig.PathEntry> path;
    private final ArmRig.Tracks<ArmRig.Value> values;
    private ArmRig.Segment<?> seg;
    private final ArmRig.Buses buses;
    private final List<Key> keys = new ArrayList<>();
    // adjustments: one entry the game cannot read and it reads none of them
    final boolean broken;

    ArmKeys(ArmEditor.Track track, String src, String clip, ArmRig.Buses buses, Map<String, Float> times) {
        this.track = track;
        this.clip = clip;
        this.times = times;
        this.buses = buses;
        path = track == ArmEditor.Track.PATH ? ArmRig.path(src) : null;
        values = switch (track) {
            case CARRY -> ArmRig.carryKeyTracks(src);
            case AROUND -> ArmRig.angleKeyTracks(src);
            case HIDE -> ArmRig.rangeTracks(src);
            case ADJUST -> ArmRig.adjustmentTracks(src);
            default -> null;
        };
        boolean bad = false;
        if (values != null && track == ArmEditor.Track.ADJUST)
            for (var s : values.segments) for (var e : s.entries) bad |= e.v() == null;
        broken = bad;
        if (track == ArmEditor.Track.RELEASE || bad) return;
        seg = played(buses);
        if (seg != null) readKeys();
    }

    // ---------------------------------------------------------------- which segment the game plays

    private List<? extends ArmRig.Segment<?>> segments() {
        return path != null ? path.segments : values.segments;
    }

    // the name the game files a segment under, or null when it skips the segment (as each reader in ArmRig)
    private String nameOf(ArmRig.Segment<?> s) {
        if (s.entries.isEmpty()) return null;
        boolean readable = false;
        for (Object e : s.entries)
            readable |= e instanceof ArmRig.PathEntry p ? p.key(times) != null : ((ArmRig.Value) e).v() != null;
        return switch (track) {
            case PATH, CARRY -> readable ? ArmRig.animation(s.head) : null;
            case HIDE -> ArmRig.animation(s.head);
            case AROUND -> readable ? s.head.toUpperCase(Locale.ROOT) : null;
            default -> s.head;
        };
    }

    private ArmRig.Segment<?> played(ArmRig.Buses buses) {
        Map<String, ArmRig.Segment<?>> byName = new LinkedHashMap<>();
        for (var s : segments()) {
            String n = nameOf(s);
            if (n != null) byName.put(n, s);
        }
        boolean byBus = track == ArmEditor.Track.PATH || track == ArmEditor.Track.CARRY || track == ArmEditor.Track.HIDE;
        return byBus ? ArmRig.forAnimation(byName, clip, buses) : byName.get(clip);
    }

    private void readKeys() {
        keys.clear();
        var entries = seg.entries;
        switch (track) {
            case PATH -> {
                var list = new ArrayList<Key>();
                for (int i = 0; i < entries.size(); i++) {
                    var k = ((ArmRig.PathEntry) entries.get(i)).key(times);
                    if (k != null) list.add(new Key(k.ms(), k.ms(), i));
                }
                list.sort(Comparator.comparingDouble(Key::ms));
                for (var k : list) if (keys.isEmpty() || k.ms() > keys.get(keys.size() - 1).ms()) keys.add(k);
            }
            case CARRY, AROUND -> {
                for (int i = 0; i < entries.size(); i++) {
                    float[] v = ((ArmRig.Value) entries.get(i)).v();
                    if (v == null || !keys.isEmpty() && v[0] <= keys.get(keys.size() - 1).ms()) continue;
                    keys.add(new Key(v[0], v[0], i));
                }
            }
            case ADJUST -> {
                var last = new TreeMap<Float, Integer>();
                for (int i = 0; i < entries.size(); i++) last.put(((ArmRig.Value) entries.get(i)).v()[0], i);
                for (var e : last.entrySet()) keys.add(new Key(e.getKey(), e.getKey(), e.getValue()));
            }
            case HIDE -> {
                for (int i = 0; i < entries.size(); i++) {
                    float[] v = ((ArmRig.Value) entries.get(i)).v();
                    if (v != null) keys.add(new Key(v[0], v[1], i));
                }
                keys.sort(Comparator.comparingDouble(Key::ms));
            }
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- looking

    List<Key> keys() {
        return keys;
    }

    // the key at this time: a key written for it, or a hidden range that holds it; null = none
    Key at(float t) {
        for (var k : keys) {
            if (track == ArmEditor.Track.HIDE ? t >= k.ms() - .5F && t <= k.to() + .5F : Math.abs(k.ms() - t) < .5F) return k;
        }
        return null;
    }

    int indexOf(Key k) {
        return keys.indexOf(k);
    }

    Key before(Key k) {
        int i = keys.indexOf(k);
        return i > 0 ? keys.get(i - 1) : null;
    }

    Key after(Key k) {
        int i = keys.indexOf(k);
        return i >= 0 && i + 1 < keys.size() ? keys.get(i + 1) : null;
    }

    ArmRig.PathEntry pathEntry(Key k) {
        return (ArmRig.PathEntry) seg.entries.get(k.entry());
    }

    float[] value(Key k) {
        return ((ArmRig.Value) seg.entries.get(k.entry())).v().clone();
    }

    // the moment a path key is written from ("release" of "release+300"), or null
    String moment(Key k) {
        return track == ArmEditor.Track.PATH ? ArmRig.momentOf(pathEntry(k).time, times) : null;
    }

    String write() {
        return path != null ? path.write() : values.write();
    }

    // the bus the played keys are written for ("PumpGrip" of "RELOAD/PumpGrip"), or null when for every animation of
    // this name
    String onlyWith() {
        String n = seg == null ? null : nameOf(seg);
        int slash = n == null ? -1 : n.indexOf('/');
        return slash < 0 ? null : n.substring(slash + 1);
    }

    // the played keys written for the animations of this name that have this bus (null = for all of them): copied to
    // "<clip>/<bus>"; the segment written for the bus before is dropped
    @SuppressWarnings({"unchecked", "rawtypes"})
    void onlyWith(String bus) {
        var copy = new ArrayList<ArmRig.Entry>();
        if (seg != null)
            for (Object e : seg.entries) copy.add(e instanceof ArmRig.PathEntry p ? ArmRig.PathEntry.read(p.text()) : (ArmRig.Entry) e);
        String old = seg == null ? null : nameOf(seg);
        if (onlyWith() != null)
            for (var s : segments()) if (old.equals(nameOf(s))) s.entries.clear();
        if (bus != null) {
            var s = path != null ? path.add(clip + "/" + bus) : values.add(clip + "/" + bus);
            ((List) s.entries).addAll(copy);
        }
        seg = played(buses);
        if (seg != null) readKeys();
        else keys.clear();
    }

    // ---------------------------------------------------------------- editing

    @SuppressWarnings("unchecked")
    private <E extends ArmRig.Entry> List<E> entries() {
        return (List<E>) seg.entries;
    }

    private void ensureSegment() {
        if (seg == null) seg = path != null ? path.add(clip) : values.add(clip);
    }

    // where a new entry at t goes in the text: before the first key that comes later (the game reads carry and
    // angle keys in the order written)
    private int slot(float t) {
        for (var k : keys) if (k.ms() > t) return k.entry();
        int last = -1;
        for (var k : keys) last = Math.max(last, k.entry());
        return last + 1 > 0 ? last + 1 : seg.entries.size();
    }

    // a new key at t, as the hand is then (the key before it, or the value between its neighbours)
    void add(float t, float duration) {
        ensureSegment();
        switch (track) {
            case PATH -> {
                Key from = null;
                for (var k : keys) if (k.ms() <= t) from = k;
                if (from == null && !keys.isEmpty()) from = keys.get(0);
                ArmRig.PathEntry e;
                if (from == null) e = ArmRig.PathEntry.grip(t);
                else {
                    String text = pathEntry(from).text();
                    e = ArmRig.PathEntry.read(ArmRig.number(t) + text.substring(text.indexOf(':')));
                }
                int at = from == null ? seg.entries.size() : from.entry() + 1;
                this.<ArmRig.PathEntry>entries().add(at, e);
            }
            case CARRY -> {
                var list = new ArrayList<float[]>();
                for (var k : keys) list.add(value(k));
                float w = list.isEmpty() ? 1F : ArmRig.carryWeight(list, t);
                this.<ArmRig.Value>entries().add(slot(t), ArmRig.carryKey(t, w, 0, 0F, null));
            }
            case AROUND -> {
                var flat = new float[keys.size() * 2];
                for (int i = 0; i < keys.size(); i++) {
                    flat[2 * i] = keys.get(i).ms();
                    flat[2 * i + 1] = value(keys.get(i))[1];
                }
                float a = keys.isEmpty() ? 0F : ArmRig.angle(flat, t);
                this.<ArmRig.Value>entries().add(slot(t), ArmRig.angleKey(ArmRig.number(t) + ":" + ArmRig.number(Math.round(a))));
            }
            case ADJUST -> {
                var track = new TreeMap<Float, float[]>();
                for (var k : keys) track.put(k.ms(), Arrays.copyOfRange(value(k), 1, 7));
                float[] pose = ArmPoseEdits.sample(track, t);
                this.<ArmRig.Value>entries().add(slot(t), ArmRig.adjustment(t, pose));
            }
            case HIDE -> {
                float to = Math.max(t + ArmEditor.STEP, Math.min(t + 500F, duration));
                for (var k : keys) if (k.ms() > t) to = Math.min(to, k.ms());
                this.<ArmRig.Value>entries().add(slot(t), ArmRig.range(ArmRig.number(t) + "-" + ArmRig.number(to)));
            }
            default -> throw new IllegalStateException(track.name());
        }
        readKeys();
    }

    void delete(Key k) {
        seg.entries.remove(k.entry());
        readKeys();
    }

    // a value entry with its time changed and the rest as written
    private static ArmRig.Value retimed(ArmRig.Value v, float ms, ArmEditor.Track t) {
        String text = v.text();
        String rest = text.substring(text.indexOf(':'));
        String now = ArmRig.number(ms) + rest;
        return switch (t) {
            case CARRY -> ArmRig.carryKey(now);
            case AROUND -> ArmRig.angleKey(now);
            default -> ArmRig.adjustment(now);
        };
    }

    // the key moved to ms (a path key written from a named moment stays written from it)
    void retime(Key k, float ms) {
        if (track == ArmEditor.Track.PATH) pathEntry(k).retime(ms, times);
        else this.<ArmRig.Value>entries().set(k.entry(), retimed((ArmRig.Value) seg.entries.get(k.entry()), ms, track));
        readKeys();
    }

    // a hidden range's ends
    void range(Key k, float from, float to) {
        this.<ArmRig.Value>entries().set(k.entry(), ArmRig.range(ArmRig.number(from) + "-" + ArmRig.number(to)));
        readKeys();
    }

    // a value entry's numbers after the time (carry: {t, w, kind, b, x, y, z}; angle: {t, a}; adjustment: {t, 6})
    void values(Key k, float[] v) {
        ArmRig.Value now = switch (track) {
            case CARRY -> ArmRig.carryKey(v[0], v[1], (int) v[2], v[3], new float[] {v[4], v[5], v[6]});
            case AROUND -> ArmRig.angleKey(ArmRig.number(v[0]) + ":" + ArmRig.number(v[1]));
            case ADJUST -> ArmRig.adjustment(v[0], Arrays.copyOfRange(v, 1, 7));
            default -> throw new IllegalStateException(track.name());
        };
        this.<ArmRig.Value>entries().set(k.entry(), now);
        readKeys();
    }

    // a path key changed in place (its fields are set by the caller)
    void touched() {
        readKeys();
    }

    // ---------------------------------------------------------------- copy and paste

    // a key as copied: its text after the time (":Pump@0,1,0", ":0.5", ":10") and, for a hidden range, how long it is
    record Copy(ArmEditor.Track track, String rest, float length) {
    }

    Copy copy(Key k) {
        String text = track == ArmEditor.Track.PATH ? pathEntry(k).text() : ((ArmRig.Value) seg.entries.get(k.entry())).text();
        int colon = text.indexOf(':');
        return new Copy(track, track == ArmEditor.Track.HIDE ? "" : text.substring(colon), k.to() - k.ms());
    }

    // the key at time t is this copy (one there keeps its time, as written); none there: a new key at t
    void paste(Copy c, float t, float duration) {
        ensureSegment();
        Key at = at(t);
        if (track == ArmEditor.Track.HIDE) {
            float from = at != null ? at.ms() : t, to = Math.min(from + (c.length() > 0 ? c.length() : ArmEditor.STEP), duration);
            for (var k : keys) if (k != at && k.ms() > from) to = Math.min(to, k.ms());
            var r = ArmRig.range(ArmRig.number(from) + "-" + ArmRig.number(Math.max(to, from + 1)));
            if (at != null) this.<ArmRig.Value>entries().set(at.entry(), r);
            else this.<ArmRig.Value>entries().add(slot(from), r);
        } else if (track == ArmEditor.Track.PATH) {
            var e = ArmRig.PathEntry.read((at != null ? pathEntry(at).time : ArmRig.number(t)) + c.rest());
            if (at != null) this.<ArmRig.PathEntry>entries().set(at.entry(), e);
            else {
                Key from = null;
                for (var k : keys) if (k.ms() <= t) from = k;
                int i = from != null ? from.entry() + 1 : keys.isEmpty() ? seg.entries.size() : keys.get(0).entry();
                this.<ArmRig.PathEntry>entries().add(i, e);
            }
        } else {
            String now = ArmRig.number(at != null ? at.ms() : t) + c.rest();
            ArmRig.Value v = switch (track) {
                case CARRY -> ArmRig.carryKey(now);
                case AROUND -> ArmRig.angleKey(now);
                default -> ArmRig.adjustment(now);
            };
            if (at != null) this.<ArmRig.Value>entries().set(at.entry(), v);
            else this.<ArmRig.Value>entries().add(slot(t), v);
        }
        readKeys();
    }
}
