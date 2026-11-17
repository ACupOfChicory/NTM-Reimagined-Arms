package acup.gunfeel.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

// A pair made again (C4): its other gone before it was ready, the new pair keeps the time it had rather than start the
// way round's time afresh; its other dead and the new one not at its place yet, a little more.
class PairTimeTest {
    static final long NOW = 10_000;

    static AiCommander.Job job(StaffPlan.Kind kind) {
        return new AiCommander.Job(kind, NOW - 500, List.of(), new int[] {1});
    }

    @Test
    void aFirstPairHasItsOwnTime() {
        assertEquals(NOW + 900, AiCommander.time(job(StaffPlan.Kind.FLANK), job(StaffPlan.Kind.PIN), NOW + 900, NOW));
    }

    @Test
    void aPairMadeAgainKeepsTheTimeItHad() {
        AiCommander.Job round = job(StaffPlan.Kind.FLANK);
        round.dueWas = NOW + 300;
        assertEquals(NOW + 300, AiCommander.time(round, job(StaffPlan.Kind.PIN), NOW + 900, NOW));
        // sooner than that, its own
        round.dueWas = NOW + 2000;
        assertEquals(NOW + 900, AiCommander.time(round, job(StaffPlan.Kind.PIN), NOW + 900, NOW));
        // its time up already (its other gone with its task, alive): up now, both advance
        round.dueWas = NOW - 50;
        assertEquals(NOW - 50, AiCommander.time(round, job(StaffPlan.Kind.PIN), NOW + 900, NOW));
        // taken up by that pair: a later pair of its has its own time
        assertEquals(Long.MAX_VALUE, round.dueWas);
    }

    @Test
    void itsOtherDeadTheNewOneNotThereGetsAMoment() {
        AiCommander.Job round = job(StaffPlan.Kind.FLANK), pin = job(StaffPlan.Kind.PIN);
        round.dueWas = NOW - 50;
        round.otherDead = true;
        assertEquals(NOW + AiCommander.PAIR_GRACE, AiCommander.time(round, pin, NOW + 900, NOW));
        assertEquals(false, round.otherDead, "once");
        // the new one at its place already: the time it had
        round.dueWas = NOW - 50;
        round.otherDead = true;
        pin.there = true;
        assertEquals(NOW - 50, AiCommander.time(round, pin, NOW + 900, NOW));
    }
}
