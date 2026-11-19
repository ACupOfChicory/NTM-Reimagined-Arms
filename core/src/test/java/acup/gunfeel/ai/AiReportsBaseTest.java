package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import acup.gunfeel.ai.AiReportsBase.Kind;
import acup.gunfeel.ai.AiReportsBase.Report;

// A side's reports off the game: the last of each kind a squad made, fresh FRESH ticks, the stale dropped from PRUNE on
class AiReportsBaseTest {
    @Test
    void theLastOfAKindFreshForItsTicks() {
        Map<String, Map<Long, Report>> sides = new HashMap<>();
        long made = AiReportsBase.made[Kind.PINNED.ordinal()];
        Report a = new Report(7, 100, Kind.PINNED, 1, 2, 3), b = new Report(7, 150, Kind.PINNED, 4, 5, 6);
        AiReportsBase.put(sides, "red", a);
        AiReportsBase.put(sides, "red", b);
        assertEquals(made + 2, AiReportsBase.made[Kind.PINNED.ordinal()]);
        assertSame(b, AiReportsBase.of(sides, "red", 7, Kind.PINNED, 150 + AiReportsBase.FRESH));
        assertNull(AiReportsBase.of(sides, "red", 7, Kind.PINNED, 151 + AiReportsBase.FRESH), "stale");
        assertNull(AiReportsBase.of(sides, "red", 7, Kind.CONTACT, 150), "another kind");
        assertNull(AiReportsBase.of(sides, "blue", 7, Kind.PINNED, 150), "another side");
        assertNull(AiReportsBase.of(null, "red", 7, Kind.PINNED, 150), "no level kept");
        List<Report> out = new ArrayList<>();
        AiReportsBase.fresh(sides, "red", 200, out);
        assertEquals(List.of(b), out);
    }

    @Test
    void theStaleDroppedFromPrune() {
        Map<String, Map<Long, Report>> sides = new HashMap<>();
        for (int i = 0; i < AiReportsBase.PRUNE; i++) AiReportsBase.put(sides, "red", new Report(i, 0, Kind.THERE, 0, 0, 0));
        AiReportsBase.put(sides, "red", new Report(9999, AiReportsBase.FRESH + 1, Kind.THERE, 0, 0, 0));
        assertEquals(1, sides.get("red").size());
    }
}
