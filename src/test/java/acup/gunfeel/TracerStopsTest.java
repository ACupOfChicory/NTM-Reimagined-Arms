package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// The server's stop on a tracer that water cut into a piece a tick (Tracers.stop, Shot.stopFrom).
class TracerStopsTest {
    // a bullet slowed by water each tick: pieces begun at 0, 1, 2, 3 ticks; the stop at 1.4 came late, when the
    // client already flew the piece begun at 3. It falls on the piece begun at 1, 0.4 into it. Taking the newest
    // piece (as before) ended that one at once, at 3: the tracer went 1.6 ticks past where the bullet stopped
    @Test
    void stopFallsOnThePieceBegunByThen() {
        double[] starts = {0, 1, 2, 3};
        int i = TracerStops.piece(starts, 1.4);
        assertEquals(1, i);
        assertEquals(0.4, 1.4 - starts[i], 1e-12);
    }

    // the pieces come in any order (a ricochet's piece and water's); a stop right at a piece's start falls on it
    @Test
    void unorderedAndAtAStart() {
        assertEquals(0, TracerStops.piece(new double[] {2, 0.5, 1}, 2), "begun at 2: the stop at 2 is on it");
        assertEquals(2, TracerStops.piece(new double[] {2, 0.5, 1}, 1.99));
        assertEquals(-1, TracerStops.piece(new double[] {0.5, 1}, 0.2), "none begun by then");
    }

    // the stop came before the client began the next piece: it fell on the one before, past that one's end (where
    // water or a ricochet cut it); the next ends where the stop is in its own ticks, or at once
    @Test
    void aLaterPieceEndsWhereTheStopIs() {
        assertEquals(0.4, TracerStops.left(1.4, 1.0), 1e-12);
        assertEquals(0, TracerStops.left(1.4, 1.4));
        assertEquals(0, TracerStops.left(1.4, 2.0));
    }
}
