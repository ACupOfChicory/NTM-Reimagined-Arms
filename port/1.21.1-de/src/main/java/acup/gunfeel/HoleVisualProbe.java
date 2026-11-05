package acup.gunfeel;

// Stub until the holes bench is ported (notes/ports/de-1.21.1-progress.md): the root's HoleVisualProbe compares a
// frame drawn from the batches with one drawn hole by hole. Tracers asks for it only in that bench.
final class HoleVisualProbe {
    private HoleVisualProbe() {
    }

    static void capture(Runnable reference, Runnable cached) {
    }
}
