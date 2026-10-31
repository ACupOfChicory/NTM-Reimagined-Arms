package acup.gunfeel;

import net.neoforged.api.distmarker.Dist;

// -Dntmgunfeel.trace (Trace): where each side's time comes from, both sides of the test log on from the start, and on
// the client the mod's clock on game time. Without the property nothing changes.
final class TraceHooks {
    private TraceHooks() {
    }

    static void register(Dist dist) {
        if (!Trace.ON) return;
        Trace.server(() -> {
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            return server == null ? -1L : server.getTickCount();
        });
        if (dist.isClient()) TraceClient.register();
        TestLog.set(false, true);
        GunFeel.LOGGER.info("Trace on: seed {}, clock {}, file {}", Rng.seed(), GunClock.real() ? "real" : "game", TestLog.file());
    }
}
