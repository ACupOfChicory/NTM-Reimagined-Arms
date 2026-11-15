package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

// The staff's picture (stage 7, C0): groups, the circle's radius, the truth always within it, the drift.
class IntelBookTest {
    static final int ROLES = 5;

    static IntelBook.Foe foe(int id, double x, double z, long seen) {
        return new IntelBook.Foe(id, x, 64, z, seen, 0, 1F, true);
    }

    @Test
    void groupsThoseNearOneAnother() {
        IntelBook b = new IntelBook(ROLES);
        List<IntelBook.Group> g = b.report(List.of(foe(5, 0, 0, Long.MIN_VALUE), foe(3, 20, 0, Long.MIN_VALUE), foe(9, 200, 0, Long.MIN_VALUE),
                new IntelBook.Foe(7, 40, 64, 0, Long.MIN_VALUE, IntelBook.PLAYER, 0.5F, true)), 1000);
        assertEquals(2, g.size());
        IntelBook.Group a = g.get(0).has(3) ? g.get(0) : g.get(1);
        // chained: 5 to 3 to 7; the least id leads
        assertEquals(3, a.lead);
        assertEquals(3, a.ids.length);
        assertEquals(1, a.players());
        assertEquals(2, a.kinds[0]);
        assertEquals(2.5F, a.health, 1e-6);
    }

    @Test
    void theCircleWidensWhileUnseen() {
        assertEquals(IntelBook.R_MAX, IntelBook.radius(Long.MIN_VALUE, 5000), 1e-9);
        assertEquals(IntelBook.R_MIN, IntelBook.radius(1000, 1000 + IntelBook.REPORT), 1e-9);
        assertEquals(IntelBook.R_MIN + 10, IntelBook.radius(1000, 1000 + IntelBook.REPORT + 10 * IntelBook.WIDEN), 1e-9);
        assertEquals(IntelBook.R_MAX, IntelBook.radius(1000, 100_000), 1e-9);
    }

    @Test
    void theTruthIsAlwaysWithinTheCircleAndTheFreshOneIsNear() {
        IntelBook b = new IntelBook(ROLES);
        double worstFresh = 0;
        // one walking 0.1 blocks a tick across, seen till tick 3000, then not
        for (long t = 0; t < 8000; t += IntelBook.REPORT) {
            double x = 0.1 * t;
            IntelBook.Group g = b.report(List.of(foe(1, x, 0, t < 3000 ? t : 2999)), t).get(0);
            double err = Math.hypot(g.x - x, g.z);
            assertTrue(err <= g.r + 1e-9, "within the circle at " + t + ": " + err + " > " + g.r);
            if (t > 200 && t < 3000) {
                assertEquals(IntelBook.R_MIN, g.r, 1e-9);
                worstFresh = Math.max(worstFresh, err);
            }
        }
        // off by half the radius at most, and lagging behind a walk of 10 blocks a report by as much again at most
        assertTrue(worstFresh <= IntelBook.R_MIN * 0.5 + 10 + 1e-9, "fresh error " + worstFresh);
    }

    @Test
    void itDriftsTowardsTheTruthNotJumps() {
        IntelBook b = new IntelBook(ROLES);
        IntelBook.Group first = b.report(List.of(foe(1, 0, 0, 0)), 0).get(0);
        double x0 = first.x;
        // a step of 12 blocks between two reports: the middle goes half the way (the offset kept: the same SHIFT)
        IntelBook.Group next = b.report(List.of(foe(1, 12, 0, IntelBook.REPORT)), IntelBook.REPORT).get(0);
        assertEquals(x0 + 6, next.x, 1e-6);
        // the same group known by any of its members, a new one joined
        IntelBook.Group joined = b.report(List.of(foe(1, 12, 0, 200), foe(2, 22, 0, Long.MIN_VALUE)), 200).get(0);
        assertEquals(1, joined.lead);
        assertTrue(Math.abs(joined.x - next.x) < 6, "drifted, not jumped");
    }

    @Test
    void lossesAndSightingsAreKeptAWhile() {
        IntelBook b = new IntelBook(ROLES);
        b.report(List.of(foe(1, 0, 0, 100)), 100);
        b.lost(1, 150);
        IntelBook.Group g = b.report(List.of(foe(1, 0, 0, Long.MIN_VALUE)), 200).get(0);
        // the side saw it at 100: kept, though this report has none
        assertEquals(100, g.seen);
        assertEquals(150, g.lost);
        g = b.report(List.of(foe(1, 0, 0, Long.MIN_VALUE)), 100 + IntelBook.KEEP + 200).get(0);
        assertEquals(Long.MIN_VALUE, g.seen);
        assertEquals(Long.MIN_VALUE, g.lost);
    }

    @Test
    void noEnemiesNoGroups() {
        assertTrue(new IntelBook(ROLES).report(new ArrayList<>(), 0).isEmpty());
    }

    /**
     * the soldiers know only what their senses tell them and their orders: of the AI's code, only the staff (and the
     * check's stands) reads the picture
     */
    @Test
    void theSoldiersNeverReadThePicture() throws IOException {
        Path dir = Path.of("src/main/java/acup/gunfeel/ai");
        if (!Files.isDirectory(dir)) dir = Path.of("../../src/main/java/acup/gunfeel/ai");
        assertTrue(Files.isDirectory(dir), "sources at " + dir.toAbsolutePath());
        List<String> may = List.of("AiCommander.java", "AiIntel.java", "IntelBook.java", "AiHqMap.java", "HqGrid.java", "StaffPlan.java");
        int read = 0;
        try (var files = Files.list(dir)) {
            for (Path p : files.toList()) {
                String f = p.getFileName().toString();
                if (!f.endsWith(".java") || may.contains(f) || f.startsWith("AiCheck")) continue;
                String s = Files.readString(p);
                assertFalse(s.contains("AiIntel") || s.contains("IntelBook"), f + " reads the staff's picture");
                read++;
            }
        }
        // the soldier's own among them
        assertTrue(Files.exists(dir.resolve("AiSoldier.java")) && Files.exists(dir.resolve("Senses.java")) && read > 50, read + " files read");
    }
}
