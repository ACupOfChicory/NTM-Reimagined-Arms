package acup.gunfeel.ai;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import acup.gunfeel.DebugLog;
import acup.gunfeel.ReaimTimes;

/**
 * The AI's background threads: rebuilding the tactical map (later the paths and the rays too). aiThreads in the
 * server's properties, 2 by default: the server shares its cores with the players' own game on the same machine.
 * They run at a lower priority, which Java on Linux keeps only when started with -XX:ThreadPriorityPolicy=1; what
 * really holds them back is that the server thread hands them a bounded number of jobs.
 */
public final class AiThreads {
    public static final String KEY = "aiThreads";
    private static final AtomicInteger NAMES = new AtomicInteger();
    private static ThreadPoolExecutor pool;
    private static int size;
    private static boolean failed;

    private AiThreads() {
    }

    public static synchronized int size() {
        return Math.max(1, Math.min(8, Math.round(ReaimTimes.number(KEY, 2F))));
    }

    private static synchronized ThreadPoolExecutor pool() {
        int want = size();
        if (pool == null || want != size) {
            if (pool != null) pool.shutdown();
            size = want;
            pool = new ThreadPoolExecutor(want, want, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "Reimagined Arms AI " + NAMES.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 2);
                return t;
            });
            pool.allowCoreThreadTimeOut(true);
        }
        return pool;
    }

    public static void submit(Runnable job) {
        pool().execute(() -> {
            try {
                job.run();
            } catch (RuntimeException | Error e) {
                synchronized (AiThreads.class) {
                    if (!failed) DebugLog.LOGGER.error("AI background job failed (logged once)", e);
                    failed = true;
                }
            }
        });
    }

    public static synchronized int waiting() {
        return pool == null ? 0 : pool.getQueue().size() + pool.getActiveCount();
    }

    public static synchronized void stop() {
        if (pool != null) pool.shutdownNow();
        pool = null;
    }
}
