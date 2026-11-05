package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

// "Повторили старое": the arms of every gun of the jar are made again on a gun with none, with the editor's controls
// only — pages, buttons, sliders stepped and numbers typed, as a player does with the mouse — and the game then reads
// the same numbers as from the jar. With REPLAY_EXPORTS=<folder> in the environment each gun's export is written there, for the trace.
class ArmReplayTest {
    static final String EMPTY = "gunfeel:replay";

    // ---------------------------------------------------------------- what the game reads, by numbers

    private static String f(float[] a) {
        return a == null ? "null" : Arrays.toString(a);
    }

    private static String pathKeys(String src, String times) {
        var sb = new StringBuilder();
        for (var e : new TreeMap<>(ArmRig.pathKeys(src, times)).entrySet()) {
            sb.append(e.getKey()).append(':');
            for (var k : e.getValue())
                sb.append(' ').append(k.ms()).append('/').append(k.part()).append('#').append(k.nth()).append(f(k.at())).append("s").append(f(k.side()))
                        .append(">").append(f(k.shoulder())).append(k.followShoulder() ? "~" : "").append('!').append(k.contact()).append('^').append(k.roll());
            sb.append(';');
        }
        return sb.toString();
    }

    private static String values(Map<String, ?> m) {
        var sb = new StringBuilder();
        for (var e : new TreeMap<String, Object>(m).entrySet()) {
            sb.append(e.getKey()).append(':');
            Object v = e.getValue();
            if (v instanceof float[] a) sb.append(f(a));
            else if (v instanceof List<?> l) for (Object x : l) sb.append(f((float[]) x));
            else if (v instanceof Map<?, ?> t) for (var x : t.entrySet()) sb.append(x.getKey()).append('=').append(f((float[]) x.getValue()));
            sb.append(';');
        }
        return sb.toString();
    }

    // a carry as the game takes it: a bus without a point of its own takes the last one
    private static String carry(String src) {
        var c = ArmRig.carry(src);
        if (c == null) return src.isBlank() ? "" : "bad";
        var sb = new StringBuilder(c.part()).append(' ').append(c.grab());
        for (int i = 0; i < c.buses().length; i++) sb.append(' ').append(c.buses()[i]).append(f(c.ats()[Math.min(i, c.ats().length - 1)]));
        return sb.append(" dir").append(f(c.dir())).toString();
    }

    // every arm setting of Config, the texts parsed as the game parses them, and the grips of the variants
    static String game(String gun, Collection<String> variants) throws Exception {
        Config.select(gun);
        var sb = new StringBuilder();
        for (var fl : Config.class.getFields()) {
            String n = fl.getName();
            if (!n.startsWith("arm") || !java.lang.reflect.Modifier.isStatic(fl.getModifiers()) || n.equals("armPathTimes")) continue;
            Object v = fl.get(null);
            String shown;
            if (v instanceof String s) {
                if (n.endsWith("Path")) shown = pathKeys(s, Config.armPathTimes);
                else if (n.endsWith("CarryKeys")) shown = values(ArmRig.carryKeys(s));
                else if (n.endsWith("Hide")) shown = values(ArmRig.ranges(s));
                else if (n.endsWith("Adjustments")) shown = values(ArmRig.adjustments(s));
                else if (n.equals("armLeftKeys")) shown = values(ArmRig.angleKeys(s));
                else if (n.contains("Carry")) shown = carry(s);
                else shown = s;
            } else if (v instanceof float[] a) shown = f(a);
            else shown = String.valueOf(v);
            sb.append(n).append('=').append(shown).append('\n');
        }
        var id = Identifier.parse(gun);
        for (String m : variants) {
            var g = Grips.get(id, m);
            sb.append("grip").append(m).append('=').append(f(g.right())).append(f(g.left())).append(f(g.rightAt())).append(f(g.leftAt())).append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- the guns and the animations the game gives

    static Properties jar() throws Exception {
        var p = new Properties();
        try (var in = ArmReplayTest.class.getResourceAsStream("/ntmgunfeel-guns.properties")) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    // the variants of a gun with grips of their own in the jar ("-Barrel")
    static List<String> variants(Properties p, String gun) {
        var out = new TreeSet<String>();
        for (String k : p.stringPropertyNames()) if (k.startsWith(gun + "-")) out.add(k.substring(gun.length(), k.lastIndexOf('.')));
        var list = new ArrayList<String>();
        list.add("");
        list.addAll(out);
        return list;
    }

    // the game's animations of the gun, as long as its keys need, with the parts of the model and the buses its
    // settings name moving one after another (as NTM's would): a bus of a carry or a release, and of a "/bus" head
    static ArmEditor.Clips clips(Map<String, String> arms, String gun, String variant) {
        Set<String> names = new TreeSet<>(List.of("RELOAD", "INSPECT")), heads = new TreeSet<>(), buses = new LinkedHashSet<>(), parts = new LinkedHashSet<>();
        float longest = 0F;
        for (var e : arms.entrySet()) {
            String k = e.getKey().substring(e.getKey().lastIndexOf('.') + 1), v = e.getValue();
            if (k.endsWith("Path") || k.endsWith("CarryKeys") || k.endsWith("Hide") || k.endsWith("Adjustments") || k.equals("armLeftKeys"))
                for (String s : v.split(";")) {
                    String[] w = s.trim().split("\\s+");
                    if (w[0].isEmpty()) continue;
                    String head = ArmRig.animation(w[0]);
                    int slash = head.indexOf('/');
                    names.add(slash < 0 ? head : head.substring(0, slash));
                    if (slash >= 0) {
                        heads.add(head.substring(0, slash));
                        buses.add(head.substring(slash + 1));
                    }
                    for (int i = 1; i < w.length; i++) {
                        String t = w[i].substring(0, Math.max(0, w[i].indexOf(':')));
                        if (k.endsWith("Hide")) t = w[i].split("-")[w[i].split("-").length - 1];
                        try {
                            longest = Math.max(longest, Float.parseFloat(t.replaceAll("[^0-9.]", "")));
                        } catch (NumberFormatException x) {
                            // a moment: counted below
                        }
                        if (k.endsWith("Path")) {
                            String rest = w[i].substring(w[i].indexOf(':') + 1);
                            int at = rest.indexOf('@');
                            if (at > 0) parts.add(rest.substring(0, at).replaceAll("[#!].*", ""));
                        }
                    }
                }
            if (k.contains("Carry") && !k.endsWith("Keys") && !k.startsWith("armCarry") && ArmRig.carry(v) != null) {
                var c = ArmRig.carry(v);
                parts.add(c.part());
                buses.addAll(List.of(c.buses()));
            }
            if (k.startsWith("armRelease") && !k.startsWith("armReleaseMove") && !v.isBlank()) buses.add(v.trim());
            if (k.startsWith("armPart") && !v.isBlank()) parts.add(v.trim());
            if (k.equals("armPathTimes"))
                for (String t : v.split(",")) if (t.contains("=")) longest = Math.max(longest, Float.parseFloat(t.split("=")[1].trim()) + 1000F);
        }
        parts.add("Gun");
        int ms = Math.max(4000, Math.round(longest) + 500);
        var list = new ArrayList<ArmEditor.Clip>();
        for (String n : names) list.add(new ArmEditor.Clip(n, ms));
        for (String h : heads) list.add(new ArmEditor.Clip(h, ms, h + ArmEditor.Clip.EMPTY));
        Map<String, int[]> moves = new LinkedHashMap<>();
        int i = 0;
        for (String b : buses) {
            if (heads.isEmpty() || !headBus(arms, b)) moves.put(b, new int[] {100 + i * 300, 300 + i * 300});
            i++;
        }
        Map<String, int[]> emptyMoves = new LinkedHashMap<>(moves);
        i = 0;
        for (String b : buses) {
            if (headBus(arms, b)) emptyMoves.put(b, new int[] {100 + i * 300, 300 + i * 300});
            i++;
        }
        var partList = List.copyOf(parts);
        return new ArmEditor.Clips() {
            public List<ArmEditor.Clip> list() {
                return list;
            }

            public Map<String, int[]> moves(String clip) {
                return clip.endsWith(ArmEditor.Clip.EMPTY) ? emptyMoves : moves;
            }

            public ArmRig.Buses buses(String clip) {
                var m = moves(clip);
                return new ArmRig.Buses() {
                    public boolean has(String bus) {
                        return m.containsKey(bus);
                    }

                    public double[] at(String bus, float ms) {
                        return null;
                    }
                };
            }

            public String variant() {
                return variant;
            }

            // a measured model, as big as any gun's: a grip is typed in, so its size does not matter
            public ArmEditor.Shape shape() {
                return BOX;
            }

            public List<String> parts() {
                return partList;
            }

            public String gunName(String g) {
                return g.equals(EMPTY) ? gun : g;
            }
        };
    }

    static final ArmEditor.Shape BOX = new ArmEditor.Shape() {
        public float[] min() {
            return new float[] {-20, -20, -20};
        }

        public float[] max() {
            return new float[] {20, 20, 20};
        }

        public float[] fractions(float[] p) {
            return new float[] {(p[0] + 20) / 40, (p[1] + 20) / 40, (p[2] + 20) / 40};
        }

        public float[] point(float[] f) {
            return new float[] {f[0] * 40 - 20, f[1] * 40 - 20, f[2] * 40 - 20};
        }
    };

    // a bus some "/bus" head names: it moves only in the animation from an empty magazine
    private static boolean headBus(Map<String, String> arms, String bus) {
        for (var e : arms.entrySet()) for (String s : e.getValue().split(";")) if (s.trim().split("\\s+")[0].endsWith("/" + bus)) return true;
        return false;
    }

    // ---------------------------------------------------------------- the player at the controls

    static final class Player {
        final ArmEditor e;
        int presses, typed, steps;

        Player(ArmEditor e) {
            this.e = e;
        }

        void tap(String id) {
            var a = e.action(id);
            if (a == null) fail("no control " + id + " on " + e.page + "/" + e.track + "/" + e.shownView());
            if (!a.active()) fail("«" + a.label() + "» (" + id + ") is grey: " + a.why());
            assertTrue(e.press(id));
            presses++;
        }

        boolean can(String id) {
            var a = e.action(id);
            return a != null && a.active();
        }

        void page(ArmEditor.Page p) {
            if (e.page != p) tap("page." + p.name().toLowerCase(Locale.ROOT));
        }

        // the hand buttons are on every page but "Обе руки" and "Файл"
        void hand(int h) {
            if (e.hand == h) return;
            var was = e.page;
            if (was == ArmEditor.Page.BOTH || was == ArmEditor.Page.FILE) page(ArmEditor.Page.ARM);
            tap(h == 0 ? "hand.right" : "hand.left");
            page(was);
        }

        void clip(String id) {
            for (int n = 0; n < e.clipList().size() && !e.clip().id().equals(id); n++) tap("clip.next");
            assertEquals(id, e.clip().id(), "no animation " + id);
        }

        void track(ArmEditor.Track t) {
            if (e.track == t) return;
            var was = e.page;
            page(ArmEditor.Page.TIME);
            tap("track." + t.name().toLowerCase(Locale.ROOT));
            page(was);
        }

        void view(ArmEditor.KeyView v) {
            if (e.shownView() != v) tap("key.view." + v.name().toLowerCase(Locale.ROOT));
        }

        // a click on the lane: the time of the nearest pixel of a lane 240 wide
        void click(float t) {
            float px = Math.round(t / e.duration() * 239F);
            e.seek(px / 239F * e.duration());
        }

        // to time t: a click on the lane, then the 10 ms arrows to within 5 ms of it
        void to(float t) {
            click(t);
            while (e.time < t - ArmEditor.STEP / 2F && e.time < e.duration()) arrow("time.forward");
            while (e.time > t + ArmEditor.STEP / 2F && e.time > 0) arrow("time.back");
        }

        void arrow(String id) {
            var was = e.page;
            page(ArmEditor.Page.TIME);
            tap(id);
            page(was);
        }

        ArmEditor.Setting slider(String id) {
            for (var r : e.rows()) if (r.slider() != null && r.slider().id().equals(id)) return r.slider();
            return null;
        }

        // a slider set to v: stepped with − / + when v is on its grid and near, else the number typed
        void set(String id, float v) {
            var s = slider(id);
            if (s == null) fail("no slider " + id + " on " + e.page + "/" + e.track + "/" + e.shownView() + " at " + e.time);
            if (s.value() == v) return;
            step(id, v);
        }

        // a setting the jar gives the gun as its own, even where it is what the game takes anyway: the slider set to v
        // and, while its ↺ is grey (no value of the gun's own), moved off and back
        void own(String id, float v) {
            set(id, v);
            String reset = "set." + id.replaceAll("\\.\\d$", "") + ".reset";
            if (e.action(reset) != null && !can(reset)) force(id, v);
        }

        private void step(String id, float v) {
            var s = slider(id);
            if (s.why() != null) fail("slider " + id + " is grey: " + s.why());
            float grid = ArmEditor.snap(v, s.step());
            if (grid == v && Math.abs(v - s.value()) <= 3 * s.step() + 1e-6F) {
                String btn = "set." + id + (v > s.value() ? ".plus" : ".minus");
                for (int n = 0; n < 4 && slider(id).value() != v; n++) {
                    tap(btn);
                    steps++;
                }
                if (slider(id).value() == v) return;
            }
            String why = e.type(id, ArmRig.number(v).replace('.', ','));
            if (why != null) fail("typing " + v + " into " + id + ": " + why);
            typed++;
            assertEquals(v, slider(id) != null ? slider(id).value() : v, id);
        }

        // a value the slider already shows because it follows another setting (or the hand's usual one), made the key's
        // own: moved off it and typed back, as a player would drag it away and back
        void force(String id, float v) {
            var s = slider(id);
            if (s == null) fail("no slider " + id);
            float other = v + s.step() <= s.max() ? v + s.step() : v - s.step();
            for (float x : new float[] {other, v}) {
                String why = e.type(id, ArmRig.number(x).replace('.', ','));
                if (why != null) fail("typing " + x + " into " + id + ": " + why);
                typed++;
            }
        }

        // presses id until done() or the round is over
        void cycle(String id, java.util.function.BooleanSupplier done, String what) {
            for (int n = 0; n < 40 && !done.getAsBoolean(); n++) tap(id);
            if (!done.getAsBoolean()) fail("could not get " + what + " with " + id);
        }
    }

    // ---------------------------------------------------------------- one gun made again

    private static String head(String full) {
        int slash = full.indexOf('/');
        return slash < 0 ? full : full.substring(0, slash);
    }

    // the animation of a head: "RELOAD/PumpGrip" is the reload from an empty magazine
    private static String clipOf(String head) {
        return head.contains("/") ? head(head) + ArmEditor.Clip.EMPTY : head;
    }

    // the keys of a track at a head as they are now in the editor
    private static void onlyWith(Player p, String full) {
        if (!full.contains("/")) return;
        String bus = full.substring(full.indexOf('/') + 1);
        var k = p.e.keysOf(p.e.track);
        if (bus.equals(k.onlyWith())) return;
        p.page(ArmEditor.Page.KEY);
        p.view(ArmEditor.KeyView.TRACK);
        p.cycle("key.only.next", () -> bus.equals(p.e.keysOf(p.e.track).onlyWith()), "keys only with " + bus);
        p.view(ArmEditor.KeyView.WHERE);
    }

    // a head for one bus ("RELOAD/PumpGrip"): keys of its own, made from the common ones as the editor does it, all but
    // the first deleted (with none left the variant would play the common keys again). True when the hand had no common
    // keys: the one made for the copy goes after
    private static boolean ownSegment(Player p, String full, float first) {
        if (!full.contains("/")) return false;
        boolean common = !p.e.keys(p.e.track).isEmpty();
        if (!common) addKey(p, first);
        onlyWith(p, full);
        p.page(ArmEditor.Page.KEY);
        p.view(ArmEditor.KeyView.WHERE);
        for (int n = 0; n < 200 && p.e.keys(p.e.track).size() > 1; n++) {
            var ks = p.e.keys(p.e.track);
            p.e.seek(ks.get(ks.size() - 1));
            p.tap("key.delete");
        }
        return !common;
    }

    // the keys at none of these times deleted; then, if the hand had no common keys, the one made for the copy
    private static void finish(Player p, String full, Collection<Float> want, boolean dropCommon) {
        p.page(ArmEditor.Page.KEY);
        p.view(ArmEditor.KeyView.WHERE);
        for (int n = 0; n < 200; n++) {
            Float stray = null;
            // a hidden range is one key from its start (the lane shows both ends)
            for (var key : p.e.keysOf(p.e.track).keys()) {
                float k = key.ms();
                boolean wanted = false;
                for (float w : want) wanted |= Math.abs(k - w) < .5F;
                if (!wanted && stray == null) stray = k;
            }
            if (stray == null) break;
            p.e.seek(stray);
            p.tap("key.delete");
        }
        if (!dropCommon) return;
        p.clip(head(full));
        clear(p);
    }

    // the keys of the track now (a fresh variant segment: the common keys it was copied from) deleted
    private static void clear(Player p) {
        p.page(ArmEditor.Page.KEY);
        p.view(ArmEditor.KeyView.WHERE);
        for (int n = 0; n < 200 && !p.e.keys(p.e.track).isEmpty(); n++) {
            p.e.seek(p.e.keys(p.e.track).get(0));
            p.tap("key.delete");
        }
    }

    // a new key of the selected track at time t: to a free time near it on the 10 ms grid, "+ Ключ", the exact time typed
    private static void addKey(Player p, float t) {
        p.page(ArmEditor.Page.KEY);
        p.view(ArmEditor.KeyView.WHERE);
        var ks = p.e.keys(p.e.track);
        for (float k : ks)
            if (Math.abs(k - t) < .5F) {
                p.e.seek(k);
                if (p.e.track != ArmEditor.Track.HIDE) p.set("key.time", t);
                return;
            }
        float before = -1F;
        for (float k : ks) if (k < t) before = Math.max(before, k);
        p.to(t);
        // a key goes in after the ones before it: their order is kept as it is in the jar
        while (p.e.time <= before) p.arrow("time.forward");
        p.tap("key.add");
        if (p.e.track == ArmEditor.Track.HIDE) return;
        p.set("key.time", t);
    }

    private static void path(Player p, int hand, String src, String times) {
        for (var e : new TreeMap<>(ArmRig.pathKeys(src, times)).entrySet()) {
            String full = e.getKey();
            p.clip(clipOf(full));
            p.hand(hand);
            p.track(ArmEditor.Track.PATH);
            boolean drop = ownSegment(p, full, e.getValue().get(0).ms());
            var want = new ArrayList<Float>();
            for (var k : e.getValue()) want.add(k.ms());
            for (var k : e.getValue()) {
                addKey(p, k.ms());
                String part = k.part() == null ? "grip" : k.part();
                p.cycle("key.part.next", () -> {
                    var x = p.e.keysOf(ArmEditor.Track.PATH);
                    var y = x.pathEntry(x.at(p.e.time));
                    return (y.grip() ? "grip" : y.part).equals(part);
                }, "part " + part);
                if (k.part() == null) continue;
                var pe = entry(p);
                if ((pe.nth == -1) != (k.nth() == -1)) p.tap("key.nth");
                if (k.nth() > 0) fail("a copy №" + (k.nth() + 1) + " of " + part);
                p.cycle("key.contact.next", () -> {
                    var x = p.e.keysOf(ArmEditor.Track.PATH);
                    return x.pathEntry(x.at(p.e.time)).contact == k.contact();
                }, "contact " + k.contact());
                for (int c = 0; c < 3; c++) p.set("key.point." + c, k.at()[c]);
                if (Float.isNaN(k.roll())) {
                    if (p.can("key.roll.reset")) p.tap("key.roll.reset");
                } else {
                    p.set("key.roll", k.roll());
                    if (Float.isNaN(entry(p).roll)) p.force("key.roll", k.roll());
                }
                p.view(ArmEditor.KeyView.APPROACH);
                approach(p, k);
                p.view(ArmEditor.KeyView.WHERE);
            }
            finish(p, full, want, drop);
        }
    }

    private static final float[][] SIDE = {{0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}, {0, -1, 0}, {0, 1, 0}};

    private static ArmRig.PathEntry entry(Player p) {
        var x = p.e.keysOf(ArmEditor.Track.PATH);
        return x.pathEntry(x.at(p.e.time));
    }

    private static void approach(Player p, ArmRig.PathKey k) {
        float[] side = k.side();
        // the game's own side when none is written
        if (side == null || Arrays.equals(side, ArmRig.DEFAULT_SIDE) && entry(p).side == null) {
            if (side == null && p.can("key.side.reset")) p.tap("key.side.reset");
        } else {
            boolean axis = false;
            for (var s : SIDE) axis |= Arrays.equals(s, side);
            if (axis) p.cycle("key.side.next", () -> Arrays.equals(entry(p).side, side), "side " + f(side));
            else {
                if (entry(p).side == null) p.tap("key.side.next");
                for (int c = 0; c < 3; c++) p.set("key.side." + c, side[c]);
            }
        }
        if (k.shoulder() != null) {
            if (entry(p).follow != k.followShoulder()) p.tap("key.follow");
            for (int c = 0; c < 3; c++) p.set("key.shoulder." + c, k.shoulder()[c]);
        }
    }

    private static void carryKeys(Player p, int hand, String src) {
        for (var e : new TreeMap<>(ArmRig.carryKeys(src)).entrySet()) {
            String full = e.getKey();
            p.clip(clipOf(full));
            p.hand(hand);
            p.track(ArmEditor.Track.CARRY);
            boolean drop = ownSegment(p, full, e.getValue().get(0)[0]);
            var want = new ArrayList<Float>();
            for (float[] k : e.getValue()) want.add(k[0]);
            for (float[] k : e.getValue()) {
                addKey(p, k[0]);
                p.set("key.weight", k[1]);
                p.cycle("key.kind.next", () -> {
                    var x = p.e.keysOf(ArmEditor.Track.CARRY);
                    return (int) x.value(x.at(p.e.time))[2] == (int) k[2];
                }, "carry kind " + (int) k[2]);
                if ((int) k[2] == 1) p.set("key.bus", k[3]);
                if ((int) k[2] == 2) for (int c = 0; c < 3; c++) p.set("key.carry." + c, k[4 + c]);
            }
            finish(p, full, want, drop);
        }
    }

    private static void ranges(Player p, int hand, String src) {
        for (var e : new TreeMap<>(ArmRig.ranges(src)).entrySet()) {
            String full = e.getKey();
            p.clip(clipOf(full));
            p.hand(hand);
            p.track(ArmEditor.Track.HIDE);
            float[] r = e.getValue();
            boolean drop = ownSegment(p, full, r.length > 0 ? r[0] : 0F);
            var want = new ArrayList<Float>();
            for (int i = 0; i < r.length; i += 2) want.add(r[i]);
            for (int i = 0; i < r.length; i += 2) {
                addKey(p, r[i]);
                // the range was added from a time near its start: its ends typed, the far one first
                p.set("key.to", r[i + 1]);
                p.set("key.from", r[i]);
                p.e.seek(r[i]);
            }
            finish(p, full, want, drop);
        }
    }

    private static void angles(Player p, String src) {
        for (var e : new TreeMap<>(ArmRig.angleKeys(src)).entrySet()) {
            p.clip(e.getKey());
            p.hand(1);
            p.track(ArmEditor.Track.AROUND);
            float[] a = e.getValue();
            for (int i = 0; i < a.length; i += 2) {
                addKey(p, a[i]);
                p.set("key.angle", a[i + 1]);
            }
        }
    }

    private static void adjustments(Player p, int hand, String src) {
        for (var e : new TreeMap<>(ArmRig.adjustments(src)).entrySet()) {
            p.clip(ArmRig.animation(e.getKey()));
            p.hand(hand);
            p.track(ArmEditor.Track.ADJUST);
            for (var k : e.getValue().entrySet()) {
                addKey(p, k.getKey());
                for (int c = 0; c < 6; c++) p.set("key.adjust." + c, k.getValue()[c]);
            }
        }
    }

    // what a hand carries (reload: on a reload, "Своё" first)
    private static void carried(Player p, int hand, String src, boolean reload) {
        var c = ArmRig.carry(src);
        if (c == null) return;
        String clip = null;
        for (var x : p.e.clipList()) {
            boolean r = x.name().equals("RELOAD") && !x.empty();
            if (reload ? r : !r && !x.empty() && clip == null) clip = x.id();
        }
        if (clip == null) clip = "RELOAD";
        p.clip(clip);
        p.hand(hand);
        p.track(ArmEditor.Track.CARRY);
        p.page(ArmEditor.Page.KEY);
        p.view(ArmEditor.KeyView.TRACK);
        if (reload) p.tap("carry.reload.own");
        else if (p.can("carry.add")) p.tap("carry.add");
        p.cycle("carry.part.next", () -> c.part().equals(part(p)), "carry part " + c.part());
        var moves = p.e.clips.moves(p.e.clip().id());
        for (int b = 0; b < c.buses().length; b++) {
            String bus = c.buses()[b];
            int bi = b;
            if (b > 0 && buses(p).size() <= b) p.tap("carry.bus.add");
            // the bus moving at this time is the one the rows show
            int[] when = moves.get(bus);
            if (when != null) p.e.seek((when[0] + when[1]) / 2F);
            p.cycle("carry.bus.next", () -> buses(p).size() > bi && buses(p).get(bi).equals(bus) && shownBus(p).equals(bus), "carry bus " + bus);
            float[] at = c.ats()[Math.min(b, c.ats().length - 1)];
            for (int i = 0; i < 3; i++) p.set("carry.at." + i, at[i]);
        }
        p.set("carry.grab", c.grab());
        if (c.dir() != null) {
            p.view(ArmEditor.KeyView.HOLD);
            for (int i = 0; i < 3; i++) p.set("carry.dir." + i, c.dir()[i]);
            p.view(ArmEditor.KeyView.TRACK);
        }
    }

    private static String part(Player p) {
        String key = carryKeyNow(p);
        var c = ArmRig.carry(key);
        return c == null ? "" : c.part();
    }

    private static List<String> buses(Player p) {
        var c = ArmRig.carry(carryKeyNow(p));
        return c == null ? List.of() : List.of(c.buses());
    }

    // the bus the rows show now ("Деталь: SHELL2, 2 из 4")
    private static String shownBus(Player p) {
        for (var r : p.e.rows())
            if (r.text() != null && r.text().startsWith("Деталь: ")) {
                String t = r.text().substring(8);
                int comma = t.indexOf(',');
                return comma < 0 ? t : t.substring(0, comma);
            }
        return "";
    }

    private static String carryKeyNow(Player p) {
        Config.select(p.e.gun);
        String s = p.e.hand == 0 ? "Right" : "Left";
        boolean reload = p.e.clip().name().equals("RELOAD");
        try {
            String r = (String) Config.class.getField("arm" + s + "CarryReload").get(null);
            return reload && !r.isEmpty() ? r : (String) Config.class.getField("arm" + s + "Carry").get(null);
        } catch (ReflectiveOperationException x) {
            throw new IllegalStateException(x);
        }
    }

    // every page, hand and view of the editor where a slider or a switch of this setting is shown
    private static boolean setting(Player p, String name, String value) throws Exception {
        var type = Config.class.getField(name).getType();
        for (var page : List.of(ArmEditor.Page.ARM, ArmEditor.Page.BOTH, ArmEditor.Page.GRIP, ArmEditor.Page.KEY))
            for (int h : new int[] {1, 0}) {
                for (var t : page == ArmEditor.Page.KEY ? ArmEditor.Track.values() : new ArmEditor.Track[] {p.e.track})
                    for (var v : page == ArmEditor.Page.KEY ? List.of(ArmEditor.KeyView.TRACK, ArmEditor.KeyView.HOLD) : List.of(ArmEditor.KeyView.WHERE)) {
                        if (page != ArmEditor.Page.BOTH) p.hand(h);
                        p.page(page);
                        if (page == ArmEditor.Page.KEY) {
                            if (p.e.trackWhy(t) != null) continue;
                            p.track(t);
                            if (p.e.viewWhy(v) != null && p.e.shownView() != v) continue;
                            p.view(v);
                        }
                        if (type == boolean.class) {
                            var a = p.e.action("set." + name + ".toggle");
                            if (a == null) continue;
                            Config.select(p.e.gun);
                            if (!String.valueOf(Config.class.getField(name).get(null)).equals(value)) p.tap("set." + name + ".toggle");
                            // the gun's own value, the same as the game's: switched over and back
                            if (Grips.own(p.e.gun, name) == null) {
                                p.tap("set." + name + ".toggle");
                                p.tap("set." + name + ".toggle");
                            }
                            return true;
                        }
                        if (type == float[].class) {
                            if (p.slider(name + ".0") == null) continue;
                            float[] want = ArmRig.xyz(value);
                            for (int c = 0; c < 3; c++) p.set(name + "." + c, want[c]);
                            if (Grips.own(p.e.gun, name) == null) p.force(name + ".0", want[0]);
                            Config.select(p.e.gun);
                            float[] got = (float[]) Config.class.getField(name).get(null);
                            for (int c = 0; c < 3; c++) if (Float.compare(got[c], want[c]) != 0) p.force(name + "." + c, want[c]);
                            return true;
                        }
                        if (p.slider(name) == null) continue;
                        if (p.slider(name).why() != null && p.can("set." + name + ".toggle")) p.tap("set." + name + ".toggle");
                        float want = Float.parseFloat(value);
                        p.own(name, want);
                        Config.select(p.e.gun);
                        if (Float.compare(Config.class.getField(name).getFloat(null), want) != 0) p.force(name, want);
                        return true;
                    }
            }
        return false;
    }

    // the grip of each hand, of the gun and of each variant with its own: point or fractions, as the jar has it
    private static void grips(Player p, Map<String, String> arms, String gun, String variant) {
        String g = gun + variant;
        p.page(ArmEditor.Page.GRIP);
        for (int h : new int[] {0, 1}) {
            String side = h == 0 ? "right" : "left";
            String at = arms.get(g + "." + side + "At"), frac = arms.get(g + "." + side);
            if (at == null && frac == null) continue;
            p.hand(h);
            if (!variant.isEmpty()) p.tap("grip.variant.own");
            boolean point = at != null;
            if (isPoint(p) != point) p.tap("grip.mode");
            float[] want = ArmRig.xyz(point ? at : frac);
            for (int c = 0; c < 3; c++) p.set("grip." + c, want[c]);
        }
    }

    private static boolean isPoint(Player p) {
        var a = p.e.action("grip.mode");
        return a != null && a.label().equals("В доли");
    }

    static Map<String, String> replay(Player p, Map<String, String> arms, String gun, List<String> variants) throws Exception {
        Map<String, String> problems = new TreeMap<>();
        // the settings a slider or a switch shows; texts below; release and carry first, as other settings act only then
        Map<String, String> own = new TreeMap<>();
        for (var e : arms.entrySet()) if (e.getKey().startsWith(gun + ".")) own.put(e.getKey().substring(gun.length() + 1), e.getValue());
        for (String side : new String[] {"Right", "Left"}) {
            int hand = side.equals("Right") ? 0 : 1;
            String rel = own.get("armRelease" + side);
            try {
                if (rel != null && !rel.isBlank()) {
                    p.clip(p.e.clipList().get(0).id());
                    for (var c : p.e.clipList()) if (p.e.clips.moves(c.id()).containsKey(rel)) {
                        p.clip(c.id());
                        break;
                    }
                    p.hand(hand);
                    p.track(ArmEditor.Track.RELEASE);
                    p.page(ArmEditor.Page.KEY);
                    p.view(ArmEditor.KeyView.TRACK);
                    p.cycle("release.next", () -> {
                        Config.select(p.e.gun);
                        try {
                            return rel.equals(Config.class.getField("armRelease" + side).get(null));
                        } catch (ReflectiveOperationException x) {
                            throw new IllegalStateException(x);
                        }
                    }, "release " + rel);
                }
            } catch (AssertionError x) {
                problems.put("armRelease" + side, x.getMessage());
            }
            for (String k : new String[] {"arm" + side + "Carry", "arm" + side + "CarryReload"}) {
                String v = own.get(k);
                if (v == null || v.isBlank()) continue;
                try {
                    carried(p, hand, v, k.endsWith("Reload"));
                } catch (AssertionError x) {
                    problems.put(k, x.getMessage());
                }
            }
        }
        String times = own.getOrDefault("armPathTimes", "");
        if (!times.isBlank()) {
            try {
                p.clip(p.e.clipList().get(0).id());
                p.track(ArmEditor.Track.PATH);
                p.page(ArmEditor.Page.KEY);
                for (var m : ArmRig.pathTimes(times).entrySet()) {
                    p.view(ArmEditor.KeyView.TRACK);
                    p.to(Math.min(m.getValue(), p.e.duration()));
                    p.tap("moment.add");
                    int i = 0;
                    for (var r : p.e.rows()) if (r.slider() != null && r.slider().id().startsWith("moment.")) i++;
                    p.set("moment." + (i - 1), m.getValue());
                }
            } catch (AssertionError x) {
                problems.put("armPathTimes", x.getMessage());
            }
        }
        for (var e : own.entrySet()) {
            String k = e.getKey(), v = e.getValue();
            if (v.isBlank()) continue;
            try {
                if (k.startsWith("armRelease") && !k.startsWith("armReleaseMove") || k.contains("Carry") && !k.endsWith("Keys") && !k.startsWith("armCarry")
                        || k.equals("armPathTimes") || k.equals("right") || k.equals("left") || k.equals("rightAt") || k.equals("leftAt")) continue;
                if (k.endsWith("Path")) path(p, k.equals("armRightPath") ? 0 : 1, v, times);
                else if (k.endsWith("CarryKeys")) carryKeys(p, k.startsWith("armRight") ? 0 : 1, v);
                else if (k.endsWith("Hide")) ranges(p, k.startsWith("armRight") ? 0 : 1, v);
                else if (k.endsWith("Adjustments")) adjustments(p, k.startsWith("armRight") ? 0 : 1, v);
                else if (k.equals("armLeftKeys")) angles(p, v);
                else if (k.startsWith("armPart")) {
                    p.hand(k.endsWith("Right") ? 0 : 1);
                    p.page(ArmEditor.Page.GRIP);
                    p.cycle("grip.part.next", () -> {
                        Config.select(p.e.gun);
                        try {
                            return v.equals(Config.class.getField(k).get(null));
                        } catch (ReflectiveOperationException x) {
                            throw new IllegalStateException(x);
                        }
                    }, "part " + v);
                } else if (!setting(p, k, v)) problems.put(k, "no slider or switch on any page");
            } catch (AssertionError x) {
                problems.put(k, x.getMessage());
            }
        }
        // a slider grey until another setting is made (the reach, after the base of the thickness): once more
        for (var k : new ArrayList<>(problems.keySet()))
            if (problems.get(k).contains("is grey") && own.containsKey(k))
                try {
                    // the reach acts only with the base of the thickness 0: as its hint says, the base made 0 for it and back
                    String base = k.startsWith("armReach") ? "armBase" + k.substring("armReach".length()) : null;
                    if (base != null && problems.get(k).contains("«Основа толщины»")) {
                        p.hand(k.endsWith("Right") ? 0 : 1);
                        p.page(ArmEditor.Page.ARM);
                        float was = p.slider(base).value();
                        p.set(base, 0F);
                        p.set(k, Float.parseFloat(own.get(k)));
                        if (own.containsKey(base) || !p.can("set." + base + ".reset")) p.set(base, was);
                        else p.tap("set." + base + ".reset");
                        problems.remove(k);
                    } else if (setting(p, k, own.get(k))) problems.remove(k);
                } catch (AssertionError x) {
                    problems.put(k, x.getMessage());
                }
        try {
            grips(p, arms, gun, "");
        } catch (AssertionError x) {
            problems.put("grip", x.getMessage());
        }
        return problems;
    }

    // ---------------------------------------------------------------- the check

    @Test
    void everyJarGunIsMadeAgainWithTheEditorsControls() throws Exception {
        var opened = Grips.snapshot();
        var p = jar();
        Map<String, String> wrong = new TreeMap<>();
        int guns = 0, presses = 0, typed = 0, steps = 0;
        String folder = System.getenv("REPLAY_EXPORTS");
        ArmFiles.dir = java.nio.file.Files.createTempDirectory("arm-replay");
        try {
            for (String gun : ArmEditorTest.jarGuns().keySet()) {
                // a gun sharing another's settings with no arms of its own plays that gun's: made by «Общие руки» alone
                if (Grips.sharedWith(gun) != null) {
                    assertFalse(Grips.ownArmsShipped(gun), gun + " shares another gun's settings but has arms of its own: not made again here");
                    continue;
                }
                Grips.restoreUnsaved(opened);
                var arms = Grips.armsOf(gun);
                var variants = variants(p, gun);
                String want = game(gun, variants);
                // the replay gun is a gun with no arms: its variants are named after it
                Map<String, String> mine = new TreeMap<>();
                arms.forEach((k, v) -> mine.put(EMPTY + k.substring(gun.length()), v));
                var problems = new TreeMap<String, String>();
                for (String m : variants) {
                    var e = new ArmEditor(m.isEmpty() ? EMPTY : EMPTY + m, clips(mine, gun, m));
                    var pl = new Player(e);
                    try {
                        if (m.isEmpty()) problems.putAll(replay(pl, mine, EMPTY, variants));
                        else grips(pl, mine, EMPTY, m);
                    } catch (AssertionError | RuntimeException x) {
                        problems.put("variant " + m, String.valueOf(x.getMessage()));
                    }
                    presses += pl.presses;
                    typed += pl.typed;
                    steps += pl.steps;
                }
                String got = game(EMPTY, variants);
                // the keys the export has are the jar's (empty values hide nothing on a gun that shares no settings)
                var jarKeys = new TreeSet<String>();
                arms.forEach((k, v) -> {
                    if (!v.isBlank()) jarKeys.add(k.substring(gun.length()));
                });
                var madeKeys = new TreeSet<String>();
                Grips.armsOf(EMPTY).forEach((k, v) -> {
                    if (!v.isBlank()) madeKeys.add(k.substring(EMPTY.length()));
                });
                if (!jarKeys.equals(madeKeys)) {
                    var extra = new TreeSet<>(madeKeys);
                    extra.removeAll(jarKeys);
                    var missing = new TreeSet<>(jarKeys);
                    missing.removeAll(madeKeys);
                    problems.put("keys", "not made " + missing + ", made but not in the jar " + extra);
                }
                if (!want.equals(got)) {
                    var a = want.split("\n");
                    var b = got.split("\n");
                    for (int i = 0; i < Math.min(a.length, b.length); i++)
                        if (!a[i].equals(b[i])) problems.put("differs " + a[i].substring(0, a[i].indexOf('=')), "jar " + a[i] + "\n      made " + b[i]);
                }
                if (!problems.isEmpty()) {
                    var sb = new StringBuilder();
                    problems.forEach((k, v) -> sb.append("\n   ").append(k).append(": ").append(v));
                    wrong.put(gun, sb.toString());
                }
                if (folder != null && problems.isEmpty()) {
                    // the export of the gun made again, under the gun's own id: what a player sends
                    Map<String, String> made = new TreeMap<>();
                    Grips.armsOf(EMPTY).forEach((k, v) -> made.put(gun + k.substring(EMPTY.length()), v));
                    var dir = java.nio.file.Path.of(folder);
                    java.nio.file.Files.createDirectories(dir);
                    java.nio.file.Files.writeString(dir.resolve(ArmFiles.stem(gun) + ".properties"),
                            ArmFiles.text(gun, gun, gun, "X0-dev", java.time.LocalDateTime.of(2026, 11, 5, 0, 0), made));
                }
                guns++;
            }
        } finally {
            Grips.restoreUnsaved(opened);
            Config.select(null);
        }
        System.out.println("REPLAY guns " + guns + " made again " + (guns - wrong.size()) + " presses " + presses + " typed " + typed + " steps " + steps);
        var sb = new StringBuilder();
        wrong.forEach((g, v) -> sb.append('\n').append(g).append(v));
        assertTrue(wrong.isEmpty(), wrong.size() + " guns not made again:" + sb);
    }
}
