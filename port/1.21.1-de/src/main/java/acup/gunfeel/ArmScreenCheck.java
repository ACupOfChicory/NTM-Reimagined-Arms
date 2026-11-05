package acup.gunfeel;

// The arm editor's stand: the editor is not ported (skip.txt), so neither is its check.
final class ArmScreenCheck {
    private ArmScreenCheck() {
    }

    static void frame() {
        GunFeel.LOGGER.warn("[armscreen] the arm editor is not in the 1.21.1 port");
    }
}
