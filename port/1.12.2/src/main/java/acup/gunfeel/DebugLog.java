package acup.gunfeel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

// 26.2's DebugLog: the mod's own lines (the "ntm-gunfeel" logger) go to logs/ntm-gunfeel-debug.log, always, and the
// game's log (the console, latest.log) gets only their warnings and errors. The file keeps the last 8 MB and two older
// ones (ntm-gunfeel-debug.1.log, .2.log). port: an appender of its own, as 1.12.2's log4j 2.8 has none of 26.2's
// builders; a frame only puts the line in a queue (a full one drops it), a thread of its own writes the file. Dev runs
// keep all the lines in the console too: the stands read latest.log.
public final class DebugLog {
    static final String FILE = "logs/ntm-gunfeel-debug.log";
    static final String NAME = "ntm-gunfeel";
    static final long SIZE = 8L * 1024 * 1024;
    static final int OLD = 2;
    private static boolean done;

    private DebugLog() {
    }

    // once, as early as the mod is loaded; console = the mod's every line in the game's log too (dev runs)
    public static void setup(boolean console) {
        setup(console, new File("logs"), SIZE);
    }

    static synchronized void setup(boolean console, File dir, long size) {
        if (done) return;
        done = true;
        try {
            // the context the mod's loggers are in (log4j may keep one per class loader)
            LoggerContext ctx = ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(NAME)).getContext();
            Configuration cfg = ctx.getConfiguration();
            if (cfg.getLoggers().containsKey(NAME)) return;
            FileAppender file = new FileAppender(dir, size);
            file.start();
            LoggerConfig own = new LoggerConfig(NAME, Level.DEBUG, false);
            own.addAppender(file, null, null);
            for (Appender a : cfg.getLoggerConfig("").getAppenders().values()) own.addAppender(a, console ? null : Level.WARN, null);
            cfg.addLogger(NAME, own);
            ctx.updateLoggers();
        } catch (Throwable t) {
            // another log4j than the game's usual: the lines stay where they were
            LogManager.getLogger(NAME).warn("Could not set up {}: {}", FILE, t.toString());
        }
    }

    // flush and close the file (the tests)
    static void stop() throws InterruptedException {
        FileAppender a = FileAppender.running;
        if (a != null) a.close();
    }

    static final class FileAppender extends AbstractAppender {
        static volatile FileAppender running;
        private static final String STOP = new String("stop");
        private final BlockingQueue<String> queue = new ArrayBlockingQueue<String>(8192);
        private final File dir;
        private final long size;
        private final Thread thread;
        private final SimpleDateFormat time = new SimpleDateFormat("ddMMMyyyy HH:mm:ss.SSS", Locale.ROOT);

        FileAppender(File dir, long size) {
            super("NtmGunfeelDebug", null, null, true);
            this.dir = dir;
            this.size = size;
            thread = new Thread(this::run, "ntm-gunfeel debug log");
            thread.setDaemon(true);
            thread.start();
            running = this;
        }

        @Override
        public void append(LogEvent e) {
            StringBuilder sb = new StringBuilder(128);
            synchronized (time) {
                sb.append('[').append(time.format(new Date())).append("] [");
            }
            sb.append(e.getThreadName()).append('/').append(e.getLevel()).append("] ").append(e.getMessage().getFormattedMessage()).append('\n');
            Throwable t = e.getThrown();
            if (t != null) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                sb.append(sw);
            }
            queue.offer(sb.toString());
        }

        private File file(int i) {
            return new File(dir, i == 0 ? "ntm-gunfeel-debug.log" : "ntm-gunfeel-debug." + i + ".log");
        }

        private Writer open() throws IOException {
            dir.mkdirs();
            return new OutputStreamWriter(new FileOutputStream(file(0), true), StandardCharsets.UTF_8);
        }

        private void run() {
            Writer w = null;
            try {
                w = open();
                long written = file(0).length();
                while (true) {
                    String line = queue.poll(1, TimeUnit.SECONDS);
                    if (line == null) {
                        w.flush();
                        continue;
                    }
                    if (line == STOP) break;
                    w.write(line);
                    written += line.length();
                    if (queue.isEmpty()) w.flush();
                    if (written >= size) {
                        // the oldest dropped, the others moved up one, a new file begun
                        w.close();
                        file(OLD).delete();
                        for (int i = OLD - 1; i >= 0; i--) file(i).renameTo(file(i + 1));
                        w = open();
                        written = 0;
                    }
                }
            } catch (IOException | InterruptedException e) {
                // the file cannot be written: the lines are lost, the game goes on
            } finally {
                try {
                    if (w != null) w.close();
                } catch (IOException e) {
                    // nothing more to do
                }
            }
        }

        void close() throws InterruptedException {
            queue.put(STOP);
            thread.join(5000);
        }
    }
}
