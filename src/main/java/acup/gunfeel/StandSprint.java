package acup.gunfeel;

import net.minecraft.server.MinecraftServer;

/**
 * The server stands that play a step a tick (BalanceCheck, DamageCapCheck, SpreadCheck) run their ticks as fast as the
 * server can (/tick sprint): the same ticks, the clock not waited for. -PstandSprint=false (ntmgunfeel.standSprint):
 * twenty a second, as before. What a stand waited for in real time it now waits for by itself: the server's file
 * (ReaimTimes.reloadNow after each write) and a /reload (BalanceCheck.planRecipes).
 */
final class StandSprint {
    private StandSprint() {
    }

    static boolean on() {
        return !"false".equals(System.getProperty("ntmgunfeel.standSprint", "true"));
    }

    static void start(MinecraftServer server) {
        if (on()) server.tickRateManager().requestGameToSprint(1_000_000_000);
    }

    static void stop(MinecraftServer server) {
        if (on() && server.tickRateManager().isSprinting()) server.tickRateManager().stopSprinting();
    }
}
