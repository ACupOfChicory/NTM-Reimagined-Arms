package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.*;
import org.junit.jupiter.api.Test;

class ArmRigTest {
    private static final String[] TRACK_KEYS = {"armLeftPath", "armRightPath", "armLeftCarryKeys", "armRightCarryKeys",
            "armLeftKeys", "armRightKeys", "armLeftHide", "armRightHide", "armLeftAdjustments", "armRightAdjustments",
            "armLeftCarry", "armRightCarry", "armLeftCarryReload", "armRightCarryReload", "armPathTimes"};

    // every arm key the jar ships: "<gun>.<key>" -> value
    private static Map<String, String> jar() throws Exception {
        var p = new Properties();
        try (InputStream in = ArmRigTest.class.getClassLoader().getResourceAsStream("ntmgunfeel-guns.properties")) {
            p.load(in);
        }
        var out = new TreeMap<String, String>();
        for (String k : p.stringPropertyNames()) {
            String key = k.substring(k.lastIndexOf('.') + 1);
            if (Arrays.asList(TRACK_KEYS).contains(key)) out.put(k, p.getProperty(k));
        }
        return out;
    }

    // the jar's values and broken variants of them: case, spacing, stray separators, unreadable entries,
    // unknown moments, equal and backward times, buses
    private static List<String> samples(Collection<String> jar) {
        var out = new ArrayList<String>(jar);
        for (String v : jar) {
            out.add(v.toLowerCase(Locale.ROOT));
            out.add("  " + v.replace(" ", "   ") + " ; ");
            out.add(v + ";;");
            out.add(v.replace(":", "::"));
            out.add(v.replace(",", ",x"));
            out.add(v.replace("@", ""));
            out.add(v.replace("/", "/NaN,"));
            out.add(v.replace("0", ""));
            out.add(v.replace("RELOAD", "RELOAD/PumpGrip"));
        }
        out.addAll(List.of("", ";", "RELOAD", "RELOAD 0:grip", "RELOAD 0:grip 0:GRIP 100:Lever@1,2,3",
                "RELOAD 300:Lever@1,2,3 100:grip 100:Gun@0,0,0", "RELOAD 0:grip later:Gun@1,2,3 later+50:grip",
                "RELOAD 0:Belt#0@1,2,3 5:Belt#-1@1,2,3 9:Belt#x@1,2,3 12:Belt!@1,2,3 14:Belt!14@1,2,3 15:Belt!3#last@1,2,3",
                "RELOAD 0:A@1,2,3^NaN 1:A@1,2,3,4/5,6,7,8>~1,1,1^-0 2:A@1,2>3,4,5 3:A@1,2,3>~ 4:A@-0,-0.0,0.0",
                "reload 0:0 1:1/5 2:0.5@1,2,3 3:1/x 2:1 4:1@1,2", "RELOAD 0:-90 0:90 10:5 x:3 5:7:9",
                "RELOAD 0-650 x-5 -5-6 3; RELOAD_END", "RELOAD 0:1,2,3,4,5,6 5:1,2,3,4,5,NaN",
                "Gun BOTTLE 0 -0.6,2,0 1.5,0,0", "Gun A|B 10 1,2,3|4,5,6", " Gun A 1 1,2,3", "Gun A x 1,2,3",
                "Gun A 1 1,2 1,2,3", "Gun A 1 1,2,3 1,2,3 9"));
        return out;
    }

    private static String bits(float v) {
        return Float.toString(v) + "#" + Integer.toHexString(Float.floatToRawIntBits(v));
    }

    private static String bits(float[] v) {
        if (v == null) return "null";
        var s = new StringBuilder("[");
        for (float f : v) s.append(bits(f)).append(' ');
        return s.append(']').toString();
    }

    private static String describe(Object k) {
        if (k instanceof ArmRig.PathKey p) return bits(p.ms()) + p.part() + p.nth() + bits(p.at()) + bits(p.side()) + bits(p.shoulder())
                + p.contact() + p.followShoulder() + bits(p.roll());
        if (k instanceof ArmRigOld.PathKey p) return bits(p.ms()) + p.part() + p.nth() + bits(p.at()) + bits(p.side()) + bits(p.shoulder())
                + p.contact() + p.followShoulder() + bits(p.roll());
        if (k instanceof float[] f) return bits(f);
        if (k instanceof List<?> l) {
            var s = new StringBuilder();
            for (Object o : l) s.append(describe(o)).append('|');
            return s.toString();
        }
        if (k instanceof Map<?, ?> m) {
            var s = new StringBuilder();
            for (var e : m.entrySet()) s.append(e.getKey()).append('=').append(describe(e.getValue())).append(';');
            return s.toString();
        }
        return String.valueOf(k);
    }

    private static String describe(ArmRig.CarrySpec c) {
        if (c == null) return describe(ArmRigOld.NO_CARRY);
        var s = new StringBuilder(c.part()).append(Arrays.toString(c.buses())).append(c.grab());
        for (float[] a : c.ats()) s.append(bits(a));
        return s.append(bits(c.dir() != null ? c.dir() : ArmRig.DEFAULT_SIDE)).toString();
    }

    private static String describe(ArmRigOld.Carry c) {
        var s = new StringBuilder(c.part()).append(Arrays.toString(c.buses())).append(c.grab());
        for (float[] a : c.ats()) s.append(bits(a));
        return s.append(bits(c.dir())).toString();
    }

    private static String adjustments(java.util.function.Supplier<Map<String, TreeMap<Float, float[]>>> read) {
        try {
            return describe(read.get());
        } catch (RuntimeException e) {
            return "unreadable";
        }
    }

    // the game reads every gun's arm data exactly as before ArmRig (the old parsers, copied from 524f0ea)
    @Test
    void gameReadsEveryGunAsBefore() throws Exception {
        var jar = jar();
        assertTrue(jar.size() >= 26, "jar arm keys: " + jar.size());
        var times = new ArrayList<String>(List.of("", "release=2410", "later=200, release = 100,bad=x,=5"));
        for (var e : jar.entrySet()) if (e.getKey().endsWith(".armPathTimes")) times.add(e.getValue());
        int compared = 0;
        for (String v : samples(jar.values())) {
            for (String t : times) {
                assertEquals(describe(ArmRigOld.parsePath(v, t)), describe(ArmRig.pathKeys(v, t)), "path " + v + " / " + t);
                compared++;
            }
            assertEquals(describe(ArmRigOld.parseCarryKeys(v)), describe(ArmRig.carryKeys(v)), "carry keys " + v);
            assertEquals(describe(ArmRigOld.parseKeys(v)), describe(ArmRig.angleKeys(v)), "angle keys " + v);
            // one change since: a hidden range for a bus ("RELOAD/PumpGrip") keeps the bus as NTM names it, as paths and
            // carry keys do (the old reader made it "RELOAD/PUMPGRIP", which no bus is, so it was never played)
            Map<String, float[]> hidden = new HashMap<>();
            ArmRig.ranges(v).forEach((k, x) -> {
                assertTrue(k.equals(k.toUpperCase(Locale.ROOT)) || k.contains("/"), "hidden " + v);
                hidden.put(k.toUpperCase(Locale.ROOT), x);
            });
            assertEquals(describe(new TreeMap<>(ArmRigOld.hide(v))), describe(new TreeMap<>(hidden)), "hidden " + v);
            assertEquals(describe(ArmRigOld.carry(v)), describe(ArmRig.carry(v)), "carry " + v);
            assertEquals(adjustments(() -> ArmRigOld.read(v)), adjustments(() -> ArmRig.adjustments(v)), "adjustments " + v);
            compared += 5;
        }
        assertTrue(compared > 1000, "compared " + compared);
    }

    // read -> write with nothing edited gives the text back, for every kind of track and every broken variant
    @Test
    void untouchedDataIsWrittenBackAsItWas() throws Exception {
        for (String v : samples(jar().values())) {
            assertEquals(v, ArmRig.path(v).write());
            assertEquals(v, ArmRig.carryKeyTracks(v).write());
            assertEquals(v, ArmRig.angleKeyTracks(v).write());
            assertEquals(v, ArmRig.rangeTracks(v).write());
            assertEquals(v, ArmRig.adjustmentTracks(v).write());
        }
    }

    // a path key written anew (as the editor writes an edited one) is read by the game exactly as the original
    @Test
    void rewrittenPathKeysMeanTheSame() throws Exception {
        var jar = jar();
        int keys = 0;
        for (var e : jar.entrySet()) {
            if (!e.getKey().endsWith("Path")) continue;
            String gun = e.getKey().substring(0, e.getKey().lastIndexOf('.'));
            String times = jar.getOrDefault(gun + ".armPathTimes", "");
            var doc = ArmRig.path(e.getValue());
            var fresh = new StringBuilder();
            for (var s : doc.segments) {
                fresh.append(fresh.isEmpty() ? "" : "; ").append(s.head);
                for (var k : s.entries) {
                    var copy = ArmRig.PathEntry.grip(0);
                    copy.time = k.time;
                    copy.attach(k.part, k.point);
                    copy.nth = k.nth;
                    copy.contact = k.contact;
                    copy.side = k.side;
                    copy.shoulder = k.shoulder;
                    copy.follow = k.follow;
                    copy.roll = k.roll;
                    fresh.append(' ').append(copy.text());
                    keys++;
                }
            }
            assertEquals(describe(ArmRig.pathKeys(e.getValue(), times)), describe(ArmRig.pathKeys(fresh.toString(), times)), e.getKey());
            assertFalse(fresh.toString().contains(".0,") || fresh.toString().contains(".0/"), "short numbers: " + fresh);
        }
        assertTrue(keys > 30, "keys " + keys);
    }

    // The old editor wrote "release+300" as 2710.0, gave a key without a side the side 0,-1,0 (the game's is
    // 0,0,-1) and baked the current wrist turn into every key. Editing one key now touches that key alone.
    @Test
    void editingOneKeyKeepsTheRestOfSexysReload() throws Exception {
        String source = jar().get("hbm:gun_autoshotgun_sexy.armLeftPath");
        String times = "release=2410";
        var doc = ArmRig.path(source);
        var keys = ArmRig.sorted(doc.forAnimation("RELOAD"), ArmRig.pathTimes(times));
        var lever = keys.get(1);
        assertEquals("Lever", lever.part);
        lever.contact = 5;
        String written = doc.write();
        assertTrue(written.contains(" 70:Lever!5@0.625,1.12,-7.25/0.7,0.7,0 "), written);
        assertEquals(source.replaceFirst(" 70:Lever@", " 70:Lever!5@"), written);
        assertTrue(written.contains(" release:Magazine@") && written.contains(" release+300:Gun@0,-4.06,-4/0,-1,0>0.65,0,0 "), written);
        assertFalse(written.contains("2710") || written.contains("^"), written);
        // the game reads the rest as before
        var before = ArmRig.pathKeys(source, times).get("RELOAD");
        var after = ArmRig.pathKeys(written, times).get("RELOAD");
        assertEquals(before.size(), after.size());
        for (int i = 0; i < before.size(); i++)
            if (i != 1) assertEquals(describe(before.get(i)), describe(after.get(i)), "key " + i);
        assertEquals(5, after.get(1).contact());
    }

    @Test
    void aKeyWithoutSideOrTurnStaysWithoutThem() {
        var doc = ArmRig.path("INSPECT 0:grip 200:Magazine@0,1,2; RELOAD 0:grip 50:what 100:Lever@1,2,3 200:grip");
        var keys = ArmRig.sorted(doc.forAnimation("RELOAD"), Map.of());
        assertEquals(3, keys.size(), "the unreadable entry is not shown");
        keys.get(1).attach("Hood", new float[] {0.5F, -2F, 3F});
        assertEquals("INSPECT 0:grip 200:Magazine@0,1,2; RELOAD 0:grip 50:what 100:Hood@0.5,-2,3 200:grip", doc.write());
        keys.get(1).attach("grip", null);
        assertEquals("INSPECT 0:grip 200:Magazine@0,1,2; RELOAD 0:grip 50:what 100:grip 200:grip", doc.write());
        var side = ArmRig.sorted(ArmRig.path("RELOAD 100:Lever@1,2,3").forAnimation("RELOAD"), Map.of()).get(0);
        assertNull(side.side);
        assertTrue(Float.isNaN(side.roll));
        assertArrayEquals(new float[] {0F, 0F, -1F}, side.key(Map.of()).side(), "the game's side for a key without one");
    }

    @Test
    void movingAKeyKeepsItsNamedMoment() {
        var times = ArmRig.pathTimes("Release=2410");
        var doc = ArmRig.path("RELOAD 0:grip release:Gun@0,0,0 release+300:grip 2800:grip");
        var keys = ArmRig.sorted(doc.forAnimation("RELOAD"), times);
        assertEquals(2710F, keys.get(2).ms);
        keys.get(2).retime(2750F, times);
        keys.get(1).retime(2400F, times);
        keys.get(3).retime(2900F, times);
        keys.get(0).retime(12.5F, times);
        assertEquals("RELOAD 12.5:grip release-10:Gun@0,0,0 release+340:grip 2900:grip", doc.write());
        keys.get(1).retime(2410F, times);
        assertEquals("RELOAD 12.5:grip release:Gun@0,0,0 release+340:grip 2900:grip", doc.write());
    }

    @Test
    void newAnimationsAndAdjustmentsAreWrittenShort() {
        var doc = ArmRig.path("RELOAD 0:grip");
        var s = doc.add("INSPECT");
        s.entries.add(ArmRig.PathEntry.grip(0));
        s.entries.add(ArmRig.PathEntry.grip(250.5F));
        assertEquals("RELOAD 0:grip; INSPECT 0:grip 250.5:grip", doc.write());
        assertEquals("INSPECT 0:grip", writeOnly("", "INSPECT"));

        String g3 = "RELOAD 0.0:0.0,0.0,0.0,0.0,0.0,0.0 180.0:0.0,0.0,0.0,0.0,0.0,0.0; INSPECT 0:1,0,0,0,0,0";
        var adj = ArmRig.adjustmentTracks(g3);
        var reload = ArmRig.adjustments(g3).get("RELOAD");
        ArmRig.putAdjustments(adj, "RELOAD", reload);
        assertEquals(g3, adj.write(), "unchanged values keep their spelling");
        reload.put(180F, new float[] {0, 0, 0, 0, 0, 20});
        reload.put(585F, new float[] {0, 0, 0, 0, 0, 50.5F});
        ArmRig.putAdjustments(adj, "RELOAD", reload);
        assertEquals("RELOAD 0.0:0.0,0.0,0.0,0.0,0.0,0.0 180:0,0,0,0,0,20 585:0,0,0,0,0,50.5; INSPECT 0:1,0,0,0,0,0", adj.write());
        ArmRig.putAdjustments(adj, "RELOAD", new TreeMap<>());
        assertEquals("INSPECT 0:1,0,0,0,0,0", adj.write(), "an animation left without adjustments goes");
    }

    private static String writeOnly(String source, String animation) {
        var doc = ArmRig.path(source);
        doc.add(animation).entries.add(ArmRig.PathEntry.grip(0));
        return doc.write();
    }

    // findings of the Sol review (2026-11-04)
    @Test
    void editorOpensTheSegmentTheGamePlays() {
        var doc = ArmRig.path("RELOAD 0:Lever@1,2,3 100:grip; RELOAD; reload x:bad");
        var track = ArmRig.pathTrack(doc, "RELOAD", Map.of());
        assertEquals(2, ArmRig.sorted(track, Map.of()).size(), "the first segment, the one the game reads");
        assertEquals("Lever", ArmRig.sorted(track, Map.of()).get(0).part);
        assertEquals(describe(ArmRig.pathKeys("RELOAD 0:Lever@1,2,3 100:grip", "").get("RELOAD")),
                describe(ArmRig.pathKeys("RELOAD 0:Lever@1,2,3 100:grip; RELOAD; reload x:bad", "").get("RELOAD")));
        assertNotNull(ArmRig.pathTrack(ArmRig.path("RELOAD x:bad"), "RELOAD", Map.of()), "nothing readable: the one written");
        assertNull(ArmRig.pathTrack(ArmRig.path("INSPECT 0:grip"), "RELOAD", Map.of()));
    }

    @Test
    void adjustmentsKeepEveryEntryOfAnUnchangedTime() {
        String src = "RELOAD 0:1,0,0,0,0,0 0.0:2,0,0,0,0,0 100:0,0,0,0,0,0";
        var doc = ArmRig.adjustmentTracks(src);
        var track = ArmRig.adjustments(src).get("RELOAD");
        track.put(100F, new float[] {0, 0, 0, 0, 0, 5});
        track.put(50F, new float[] {1, 0, 0, 0, 0, 0});
        ArmRig.putAdjustments(doc, "RELOAD", track);
        assertEquals("RELOAD 0:1,0,0,0,0,0 0.0:2,0,0,0,0,0 50:1,0,0,0,0,0 100:0,0,0,0,0,5", doc.write());
        assertEquals(describe(track), describe(ArmRig.adjustments(doc.write()).get("RELOAD")));
    }

    @Test
    void tinyAndHugeTimesAreWrittenWithoutExponent() {
        var doc = ArmRig.path("RELOAD 0:Lever@1,2,3");
        var key = doc.forAnimation("RELOAD").entries.get(0);
        key.retime(-0.00001F, Map.of());
        assertEquals("RELOAD -0.00001:Lever@1,2,3", doc.write());
        assertEquals(-0.00001F, ArmRig.pathKeys(doc.write(), "").get("RELOAD").get(0).ms());
        assertEquals("3000000000", ArmRig.number(3e9F));
        assertEquals("0.1", ArmRig.number(0.1F));
    }

    @Test
    void numbersAreShortAndReadBackTheSame() {
        for (float v : new float[] {0F, -0F, 1F, -2F, 0.625F, 1.12F, -4.06F, 1e-5F, 123456.5F, 3e9F, 0.1F, 2410F})
            assertEquals(Float.floatToRawIntBits(v), Float.floatToRawIntBits(Float.parseFloat(ArmRig.number(v))), ArmRig.number(v));
        assertEquals("2", ArmRig.number(2F));
        assertEquals("-0", ArmRig.number(-0F));
        assertEquals("0.625", ArmRig.number(0.625F));
    }
}
