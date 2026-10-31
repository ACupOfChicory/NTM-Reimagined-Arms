package acup.gunfeel;

import net.minecraft.client.Minecraft;

// The client's part of a trace (TraceHooks): its time is the level's game time and the frame's partial tick, and the
// mod's clock (GunClock) runs on that time too, as NTM's animations do (com.hbm.client.ClientGameTime), so a recoil
// or a hole's life is the same in every run that steps its frames the same.
final class TraceClient {
    private static long last = Long.MIN_VALUE;

    private TraceClient() {
    }

    static void register() {
        Trace.client(() -> {
            var level = Minecraft.getInstance().level;
            return level == null ? -1L : level.getGameTime();
        }, () -> Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false));
        GunClock.use(TraceClient::nanos);
        // a scenario writes only what it plays, from the start (TraceScenario)
        if (TraceScenario.enabled()) Trace.scenario = true;
        TestLog.set(true, true);
    }

    // game time in ns, never going back (a partial tick that starts over before its tick is counted)
    private static synchronized long nanos() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return last == Long.MIN_VALUE ? 0L : last;
        double ticks = mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        long now = Math.round(ticks * 50_000_000.0);
        if (now > last) last = now;
        return last;
    }
}
