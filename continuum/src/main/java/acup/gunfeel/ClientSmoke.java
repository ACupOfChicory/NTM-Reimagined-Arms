package acup.gunfeel;

// port/continuum: a stand-in for the 26.2 self-test (src/main/java ClientSmoke, written for NTM NEXT): it does not run on
// this node. It only drives a trace scenario (TraceScenario, -Pscenario with -Ptrace and -Pcontinuum.world), the shapes
// stand (ShapesStand, -Pntmgunfeel.shapesStand=<file>) or the check of a machine left out of the shapes table
// (ShapesFallbackCheck, -Pntmgunfeel.shapesFallback=true -Pntmgunfeel.shapesSkip=hbm:machine_chemical_factory): started
// once the world is in, one step per frame, the game quits when it is over.
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
    private static long startedAt;

    public static void init() {
        if (!TraceScenario.enabled() && !ShapesStand.ENABLED && !ShapesFallbackCheck.ENABLED) return;
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
            startedAt = System.nanoTime();
            GunFeel.LOGGER.info("[port-scenario] start");
            if (ShapesStand.ENABLED) ShapesStand.start();
            else if (ShapesFallbackCheck.ENABLED) ShapesFallbackCheck.start();
            else TraceScenario.start();
            return;
        }
        if (ShapesStand.ENABLED || ShapesFallbackCheck.ENABLED) {
            if (System.nanoTime() - startedAt > (ShapesStand.ENABLED ? ShapesStand.timeout() : 180_000_000_000L)) {
                check(false, "таймаут клиента");
                finish();
                return;
            }
            if (ShapesStand.ENABLED) ShapesStand.frame();
            else ShapesFallbackCheck.frame();
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
        if (!quitting) ExitWatch.start();
        quitting = true;
    }
}
