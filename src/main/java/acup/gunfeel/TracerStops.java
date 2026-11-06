package acup.gunfeel;

// Where the server's stop of a bullet falls on the client's tracer. Water and ricochets cut one bullet's tracer into
// pieces, each begun some ticks after the first; the stop comes with the ticks of flight it fell at (`age`).
final class TracerStops {
    private TracerStops() {
    }

    // the piece the stop at `age` falls on: the last one begun by then, of the pieces begun `starts` ticks after the
    // first (-1: none). Water begins a piece every tick it slows the bullet, and the client flies ahead of the server's
    // stop, so the newest piece may already be past it
    static int piece(double[] starts, double age) {
        int found = -1;
        for (int i = 0; i < starts.length; i++)
            if (starts[i] <= age + 1e-9 && (found < 0 || starts[i] > starts[found])) found = i;
        return found;
    }

    // a piece begun `later` ticks after the one the stop fell on, past that one's stop: where it ends in turn, in its
    // own ticks (0: at once)
    static double left(double stop, double later) {
        return Math.max(0, stop - later);
    }
}
