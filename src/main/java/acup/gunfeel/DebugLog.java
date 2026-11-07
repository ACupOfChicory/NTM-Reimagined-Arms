package acup.gunfeel;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AsyncAppender;
import org.apache.logging.log4j.core.appender.RollingRandomAccessFileAppender;
import org.apache.logging.log4j.core.appender.rolling.DefaultRolloverStrategy;
import org.apache.logging.log4j.core.appender.rolling.SizeBasedTriggeringPolicy;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

// The mod's own lines (the "ntm-gunfeel" logger, every class's) go to logs/ntm-gunfeel-debug.log, always: a player
// comes with a bug met by chance, and the file is already there. Warnings and errors go to the game's log as well
// (the console, latest.log); nothing else of the mod's does. The file keeps the last 8 MB and two older ones
// (ntm-gunfeel-debug.1.log, .2.log); a background thread writes it (log4j's async appender), so a frame never waits
// on the disk, and lines past a full queue are dropped rather than waited for. Dev runs (runClient, the checks) keep
// all the lines in the console too: the stands read latest.log.
public final class DebugLog {
    static final String FILE = "logs/ntm-gunfeel-debug.log";
    static final String NAME = "ntm-gunfeel";
    // the mod's logger, for every class: GunFeel (client only) hands out this one, and code that a dedicated server
    // runs takes it here, as GunFeel does not load there
    public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(NAME);
    static final long SIZE = 8L * 1024 * 1024;
    static final int OLD = 2;
    private static boolean done;

    private DebugLog() {
    }

    // once, as early as the mod is loaded; console = the mod's every line in the game's log too (dev runs)
    public static void setup(boolean console) {
        setup(console, "logs", SIZE);
    }

    // dir: where the file goes; size: how large it grows before it is moved to .1
    static synchronized void setup(boolean console, String dir, long size) {
        if (done) return;
        done = true;
        try {
            // the context the mod's loggers are in (log4j may keep one per class loader)
            LoggerContext ctx = ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(NAME)).getContext();
            Configuration cfg = ctx.getConfiguration();
            if (cfg.getLoggers().containsKey(NAME)) return;
            PatternLayout layout = PatternLayout.newBuilder().withConfiguration(cfg)
                    .withPattern("[%d{ddMMMyyyy HH:mm:ss.SSS}] [%t/%level] %msg%n%throwable").build();
            RollingRandomAccessFileAppender file = RollingRandomAccessFileAppender.newBuilder().setName("NtmGunfeelDebugFile")
                    .withFileName(dir + "/ntm-gunfeel-debug.log").withFilePattern(dir + "/ntm-gunfeel-debug.%i.log")
                    .withPolicy(SizeBasedTriggeringPolicy.createPolicy(String.valueOf(size)))
                    .withStrategy(DefaultRolloverStrategy.newBuilder().withMin("1").withMax(String.valueOf(OLD)).withFileIndex("min").withConfig(cfg).build())
                    .withImmediateFlush(false).setLayout(layout).setConfiguration(cfg).build();
            file.start();
            cfg.addAppender(file);
            AsyncAppender async = AsyncAppender.newBuilder().setName("NtmGunfeelDebug")
                    .setAppenderRefs(new AppenderRef[] {AppenderRef.createAppenderRef(file.getName(), null, null)})
                    .setBlocking(false).setBufferSize(4096).setConfiguration(cfg).build();
            async.start();
            cfg.addAppender(async);
            LoggerConfig own = LoggerConfig.newBuilder().withLoggerName(NAME).withLevel(Level.DEBUG).withAdditivity(false).withConfig(cfg).build();
            own.addAppender(async, null, null);
            // the game's own appenders (console, latest.log, debug.log): warnings and errors only, unless a dev run
            for (Appender a : cfg.getRootLogger().getAppenders().values()) own.addAppender(a, console ? null : Level.WARN, null);
            cfg.addLogger(NAME, own);
            ctx.updateLoggers();
        } catch (Throwable t) {
            // another log4j than the game's usual: the lines stay where they were
            org.slf4j.LoggerFactory.getLogger(NAME).warn("Could not set up {}: {}", FILE, t.toString());
        }
    }
}
