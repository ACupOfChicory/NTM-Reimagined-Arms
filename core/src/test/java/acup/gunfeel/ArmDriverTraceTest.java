package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

// The editor's "what moves the hand" against what the game drew: every arm frame of an `arms` trace
// (ARM_TRACE=<file.tsv>). Without a trace the test is skipped.
class ArmDriverTraceTest {
    // every bus there: the "<TYPE>/<bus>" variant wins (release turns are not in the trace, see below)
    static final ArmRig.Buses ALL = new ArmRig.Buses() {
        public boolean has(String bus) {
            return true;
        }

        public double[] at(String bus, float ms) {
            return null;
        }
    };

    @Test
    void driverMatchesTheGameFrameByFrame() throws Exception {
        String file = System.getenv("ARM_TRACE");
        Assumptions.assumeTrue(file != null && !file.isEmpty(), "ARM_TRACE not set");
        String gun = null;
        Map<String, ArmRig.Drive[]> drives = new HashMap<>();
        Map<String, Integer> bad = new TreeMap<>();
        int frames = 0, variants = 0;
        try {
            for (String line : Files.readAllLines(Path.of(file))) {
                if (line.startsWith("#")) continue;
                String[] c = line.split("\t");
                if (c.length < 7) continue;
                Map<String, String> v = new HashMap<>();
                for (int i = 6; i < c.length; i++) {
                    int eq = c[i].indexOf('=');
                    if (eq > 0) v.put(c[i].substring(0, eq), c[i].substring(eq + 1));
                }
                if (c[5].equals("segment")) gun = v.get("gun");
                boolean hidden = c[5].equals("arm_hidden");
                if (!hidden && !c[5].equals("arm")) continue;
                String anim = v.get("anim");
                if (anim == null || anim.equals("none") || gun == null) continue;
                String expected = hidden ? "hidden" : v.get("branch");
                if (expected.equals("rest")) continue;
                String g = gun;
                var d = drives.computeIfAbsent(g, k -> {
                    Config.select(k);
                    return new ArmRig.Drive[] {new ArmRig.Drive(true), new ArmRig.Drive(false)};
                });
                boolean right = v.get("right").equals("true");
                float t = Float.parseFloat(v.get("t"));
                var got = d[right ? 0 : 1].at(anim, t, ArmRig.Buses.NONE);
                frames++;
                // the trace keeps no buses: a "RELOAD/PumpGrip" variant is played when the animation has the bus
                // (SPAS-12's reload from empty), so with variants either answer may be the game's
                if (!got.name().toLowerCase(Locale.ROOT).equals(expected)) {
                    var withBuses = d[right ? 0 : 1].at(anim, t, ALL);
                    if (withBuses.name().toLowerCase(Locale.ROOT).equals(expected)) {
                        variants++;
                        continue;
                    }
                }
                String name = got.name().toLowerCase(Locale.ROOT);
                // the trace keeps no bus turns: a release frame is right when the hand has a release bus and
                // nothing before it in the order took the hand
                if (expected.equals("release") && got == ArmRig.Driver.HOLD && !d[right ? 0 : 1].release.isEmpty()) continue;
                if (!name.equals(expected)) bad.merge(g + " " + anim + (right ? " R " : " L ") + expected + "->" + name, 1, Integer::sum);
            }
        } finally {
            Config.select(null);
        }
        bad.forEach((k, n) -> System.out.println("DRIVER " + k + " x" + n));
        System.out.println("DRIVER frames " + frames + " by a bus variant " + variants + " mismatched kinds " + bad.size());
        assertTrue(frames > 0);
        assertEquals(Map.of(), bad);
    }
}
