package acup.gunfeel;

import net.minecraft.client.Minecraft;

// The client's part of a trace (TraceHooks): its time is the world's game time and the frame's partial tick, and the
// mod's clock (GunClock) runs on that time too, so a recoil is the same in every run that steps its frames the same.
// (26.2: TraceClient, written anew: the partial tick comes from RenderTickEvent, GunFeelClient.partial.)
final class TraceClient {
    private static long last = Long.MIN_VALUE;

    private TraceClient() {
    }

    static void register() {
        Trace.client(() -> {
            net.minecraft.client.multiplayer.WorldClient world = Minecraft.getMinecraft().theWorld;
            return world == null ? -1L : world.getTotalWorldTime();
        }, () -> GunFeelClient.partial);
        GunClock.use(TraceClient::nanos);
        TestLog.set(true, true);
    }

    // game time in ns, never going back (a partial tick that starts over before its tick is counted)
    private static synchronized long nanos() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return last == Long.MIN_VALUE ? 0L : last;
        double ticks = mc.theWorld.getTotalWorldTime() + (double) GunFeelClient.partial;
        long now = Math.round(ticks * 50_000_000.0);
        if (now > last) last = now;
        return last;
    }
}
