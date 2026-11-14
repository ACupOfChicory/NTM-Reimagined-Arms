// SPDX-License-Identifier: GPL-3.0-only
// Copied from TaCZ (Timeless and Classics Zero), MCModderAnchor/TACZ and contributors, GPL-3.0.
// https://github.com/MCModderAnchor/TACZ, revision 19f14c8, src/main/java/com/tacz/guns/util/time/GunTime.java.
// Changes: the package; the clock (TaCZ takes it from SimpleBedrockModel) is the Clock class at the end; epoch(),
// resetEpoch(), isWithin(), hasElapsed() and the epoch counter are left out, nothing here calls them.
package acup.gunfeel.tacz;


/**
 * The time source for gun gameplay and animation. On a physical client this
 * follows the single-player pause; on a dedicated server it keeps running.
 */
public final class GunTime {
    // Keep the legacy -1 "never happened" timestamp well outside ordinary Lua time windows.
    private static final long EPOCH_OFFSET_NANOS = 100_000L * 1_000_000L;
    // TaCZ: SimpleBedrockModel's AnimationClocks.client(), a clock that stops with the single-player pause
    private static final Clock CLOCK = new Clock();
    private static volatile long originNanos = CLOCK.nowNanos();

    private GunTime() {
    }

    /** A nonnegative, monotonic timestamp within the current clock epoch. */
    public static long nowNanos() {
        long raw = CLOCK.nowNanos();
        long origin = originNanos;
        if (raw < origin) {
            synchronized (GunTime.class) {
                if (raw < originNanos) {
                    originNanos = raw;
                }
                origin = originNanos;
            }
        }
        return EPOCH_OFFSET_NANOS + Math.max(0L, raw - origin);
    }

    public static long nowMillis() {
        return nowNanos() / 1_000_000L;
    }

    public static boolean shouldTick() {
        return CLOCK.shouldTick();
    }

    // port: in place of SimpleBedrockModel's client clock: System.nanoTime() that does not run while the game is paused
    private static final class Clock {
        private long paused, pausedAt = -1L;

        synchronized long nowNanos() {
            long now = System.nanoTime();
            boolean pause = net.minecraft.client.Minecraft.getInstance() != null && net.minecraft.client.Minecraft.getInstance().isPaused();
            if (pause && pausedAt < 0L) pausedAt = now;
            if (!pause && pausedAt >= 0L) {
                paused += now - pausedAt;
                pausedAt = -1L;
            }
            return (pausedAt >= 0L ? pausedAt : now) - paused;
        }

        boolean shouldTick() {
            // the filter's thread asks every few milliseconds: it keeps the pause counted while nothing reads the time
            nowNanos();
            var mc = net.minecraft.client.Minecraft.getInstance();
            return mc == null || !mc.isPaused();
        }
    }
}
