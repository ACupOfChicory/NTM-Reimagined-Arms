package acup.gunfeel;

// Stub until the stands are ported (notes/ports/de-1.21.1-progress.md): the root's ClientSmoke drives the client
// stands, and the shared files tell it what they drew. Here no stand runs, so it hears nothing.
public final class ClientSmoke {
    private ClientSmoke() {
    }

    public static void init() {
    }

    public static void weaponSway(WeaponSway.Turn turn, float aiming) {
    }

    public static void weaponLag(float value) {
    }

    static void armBranch(boolean right, String branch) {
    }

    static void armPose(org.joml.Matrix4f pose, boolean right) {
    }

    static void packet() {
    }

    static double flightTime(double real) {
        return real;
    }

    static void segment(double age, boolean expired) {
    }

    static void tracerDrawn(int count) {
    }

    static void particleAdded() {
    }

    static void holeDrawn() {
    }

    static void check(boolean success, String text) {
        GunFeel.LOGGER.info("[clientcheck] {} {}", success ? "PASS" : "FAIL", text);
    }

    static void finish() {
    }
}
