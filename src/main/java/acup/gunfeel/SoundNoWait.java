package acup.gunfeel;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;

import acup.gunfeel.mixin.mc.ChannelAccessAccess;
import acup.gunfeel.mixin.mc.ChannelHandleAccess;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.util.thread.BlockableEventLoop;

/**
 * A new sound no longer holds the frame while the sound thread is busy. The game asks the sound thread for a channel
 * and waits for the answer; with many shots (and Sound Physics working in that thread) the frame waits behind the
 * sounds before it. Here the handle is made at once and the channel taken in the sound thread's turn: the queue keeps
 * its order, so everything the game then does with the sound (play it, move it, stop it) comes after the channel.
 * Only while the sound thread has work queued; with Config.soundNoWait off, or the mixin not applied, the game's way.
 * Unlike the game's way, a sound that finds no free channel still counts as started and ends at the next tick.
 */
public final class SoundNoWait {
    /** the mixin is in the game (it runs on the first sound) */
    public static volatile boolean applied;
    /** stopAll's count: a channel asked for before it is not taken after it */
    private static final AtomicInteger GENERATION = new AtomicInteger();
    /** sounds started without the wait; their channels taken; found none; dropped by stopAll */
    public static final AtomicLong EARLY = new AtomicLong(), TAKEN = new AtomicLong(), NONE = new AtomicLong(), DROPPED = new AtomicLong();
    private static volatile boolean failed;

    private SoundNoWait() {
    }

    /** the handle at once, or null for the game's way */
    public static CompletableFuture<ChannelAccess.ChannelHandle> handle(ChannelAccess access, Library.Pool pool) {
        if (!Config.soundNoWait || failed) return null;
        if (!(access instanceof ChannelAccessAccess a)) {
            off("ChannelAccess has no accessor here");
            return null;
        }
        Executor executor = a.gunfeel$executor();
        // the game's way when nothing is queued: the answer comes at once
        if (!(executor instanceof BlockableEventLoop<?> loop) || loop.getPendingTasksCount() == 0) return null;
        ChannelAccess.ChannelHandle handle;
        try {
            handle = access.new ChannelHandle(null);
            if (!(handle instanceof ChannelHandleAccess)) throw new IllegalStateException("ChannelHandle has no accessor here");
        } catch (RuntimeException | LinkageError e) {
            off(e.toString());
            return null;
        }
        int generation = GENERATION.get();
        EARLY.incrementAndGet();
        executor.execute(() -> {
            try {
                attach(a, handle, pool, generation);
            } catch (RuntimeException | LinkageError e) {
                // the sound is not heard, as when no channel is free; the rest start the game's way
                ((ChannelHandleAccess) handle).gunfeel$stopped(true);
                off(e.toString());
            }
        });
        return CompletableFuture.completedFuture(handle);
    }

    /** the sound thread's turn: the channel taken and given to the handle, as the game's createHandle does */
    private static void attach(ChannelAccessAccess a, ChannelAccess.ChannelHandle handle, Library.Pool pool, int generation) {
        if (generation != GENERATION.get()) {
            ((ChannelHandleAccess) handle).gunfeel$stopped(true);
            DROPPED.incrementAndGet();
            return;
        }
        Channel channel = a.gunfeel$library().acquireChannel(pool);
        if (channel == null) {
            ((ChannelHandleAccess) handle).gunfeel$stopped(true);
            NONE.incrementAndGet();
            return;
        }
        ((ChannelHandleAccess) handle).gunfeel$channel(channel);
        a.gunfeel$channels().add(handle);
        TAKEN.incrementAndGet();
    }

    /** the stand's measure: from the game's play to the channel's play, by the stand's way at the start (0, 1) */
    public static volatile boolean timing;
    /** when the game asked for the channel of the sound being started (render thread) */
    public static long playAt;
    /** sounds the game started (NeoForge's PlaySoundEvent), for the line on leaving */
    public static volatile long played;
    public static volatile int timingWay;
    private static final long[][] DELAYS = new long[2][50000];
    private static final int[] DELAY_N = new int[2];

    public static java.util.function.Consumer<Channel> timed(java.util.function.Consumer<Channel> play) {
        if (!timing) return play;
        long at = playAt != 0 ? playAt : System.nanoTime();
        int way = timingWay;
        return channel -> {
            long d = System.nanoTime() - at;
            synchronized (DELAYS) {
                if (DELAY_N[way] < DELAYS[way].length) DELAYS[way][DELAY_N[way]++] = d;
            }
            play.accept(channel);
        };
    }

    /** the delays measured for a way since the last call, sorted */
    public static long[] delays(int way) {
        synchronized (DELAYS) {
            long[] d = java.util.Arrays.copyOf(DELAYS[way], DELAY_N[way]);
            DELAY_N[way] = 0;
            java.util.Arrays.sort(d);
            return d;
        }
    }

    private static boolean told;

    /** leaving a level: one line if sounds were started and the start never came here (another mod changed the game's play) */
    public static void leaving() {
        if (told || applied || played < 100 || !Config.soundNoWait) return;
        told = true;
        GunFeel.LOGGER.info("[sound] no-wait start not in the game (SoundEngine.play changed by another mod?), sounds start the game's way");
    }

    public static void stopAll() {
        GENERATION.incrementAndGet();
    }

    private static void off(String why) {
        if (failed) return;
        failed = true;
        GunFeel.LOGGER.info("[sound] no-wait start off, sounds start the game's way: {}", why);
    }
}
