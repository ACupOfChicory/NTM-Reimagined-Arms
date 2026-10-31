package acup.gunfeel;

// port/continuum: a stand-in for the 26.2 self-test (src/main/java ClientSmoke, written for NTM NEXT): it does not run on this node.
public final class ClientSmoke {
    private ClientSmoke() {
    }

    public static void weaponSway(WeaponSway.Turn turn, float aiming) {
    }

    public static void weaponLag(float value) {
    }

    static void armPose(org.joml.Matrix4f pose, boolean right) {
    }

    public static void init() {
    }

    // shot packets Tracers took (PortSmoke)
    static int packets;

    static void packet() {
        packets++;
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
    }

    static void finish() {
    }
}
