package acup.gunfeel;

// port/continuum: a stand-in for the 26.2 hole pixel probe (a 26.2 render target): it does not run on this node.
final class HoleVisualProbe {
    static volatile boolean finished;

    private HoleVisualProbe() {
    }

    static void capture(Runnable reference, Runnable cached) {
    }
}
