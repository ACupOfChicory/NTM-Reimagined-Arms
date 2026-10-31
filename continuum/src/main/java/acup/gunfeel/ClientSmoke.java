package acup.gunfeel;

// port/continuum: a stand-in for the 26.2 self-test (src/main/java ClientSmoke, written for NTM NEXT): it does not run on
// this node. It only drives a trace scenario (TraceScenario, -Pscenario with -Ptrace and -Pcontinuum.world): started once
// the world is in, one step per frame, the game quits when the scenario is over.
public final class ClientSmoke {
    private ClientSmoke() {
    }

    public static void weaponSway(WeaponSway.Turn turn, float aiming) {
    }

    public static void weaponLag(float value) {
    }

    static void armPose(org.joml.Matrix4f pose, boolean right) {
    }

    private static boolean started, failed, quitting;

    public static void init() {
        if (!TraceScenario.enabled()) return;
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre.class, e -> frame());
    }

    private static void frame() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (quitting) {
            mc.stop();
            return;
        }
        mc.options.pauseOnLostFocus = false;
        if (!started) {
            if (mc.player == null || mc.getSingleplayerServer() == null || mc.getOverlay() != null) return;
            started = true;
            mc.setScreen(null);
            GunFeel.LOGGER.info("[port-scenario] start");
            TraceScenario.start();
            return;
        }
        TraceScenario.frame();
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
        if (success) GunFeel.LOGGER.info("[port-scenario] OK {}", text);
        else {
            failed = true;
            GunFeel.LOGGER.error("[port-scenario] FAIL {}", text);
        }
    }

    static void finish() {
        GunFeel.LOGGER.info("[port-scenario] done{}", failed ? " with failures" : "");
        quitting = true;
    }
}
