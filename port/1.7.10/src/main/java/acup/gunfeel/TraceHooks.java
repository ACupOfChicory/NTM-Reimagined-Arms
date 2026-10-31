package acup.gunfeel;

// -Dntmgunfeel.trace (Trace): where each side's time comes from, both sides of the test log on from the start, and on
// the client the mod's clock on game time. Without the property nothing changes. (26.2: TraceHooks, written anew.)
final class TraceHooks {
    private TraceHooks() {
    }

    static void register(boolean client) {
        if (!Trace.ON) return;
        Trace.server(() -> {
            net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
            return server == null ? -1L : server.getTickCounter();
        });
        if (client) TraceClient.register();
        TestLog.set(false, true);
        GunFeel.LOGGER.info("Trace on: seed {}, clock {}, file {}", Rng.seed(), GunClock.real() ? "real" : "game", TestLog.file());
    }
}
