package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// DebugLog (26.2's DebugLogTest): the mod's lines go to their own file, the game's log gets only its warnings and
// errors, and the file is kept to its size and two older ones.
class DebugLogTest {
    static final class Seen extends AbstractAppender {
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>());

        Seen() {
            super("Seen", null, null, true);
        }

        @Override
        public void append(LogEvent e) {
            lines.add(e.getLevel() + " " + e.getMessage().getFormattedMessage());
        }
    }

    @Test
    void ownFileWarningsInTheGameLogKeptToItsSize(@TempDir File dir) throws Exception {
        LoggerContext ctx = ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(DebugLog.NAME)).getContext();
        Seen seen = new Seen();
        seen.start();
        ctx.getConfiguration().getLoggerConfig("").addAppender(seen, null, null);
        ctx.updateLoggers();

        DebugLog.setup(false, dir, 64 * 1024);
        Logger log = LogManager.getLogger(DebugLog.NAME);
        log.info("[modeldebug] a line for the file");
        log.debug("a debug line for the file");
        log.warn("a warning for both");
        StringBuilder filler = new StringBuilder();
        for (int i = 0; i < 200; i++) filler.append('x');
        for (int i = 0; i < 2000; i++) {
            log.info("filler {} {}", i, filler);
            // the queue holds 8192 lines; the test waits for the writer now and then rather than drop any
            if (i % 500 == 0) Thread.sleep(50);
        }
        LogManager.getLogger("NTM Reimagined Arms").info("the start line");
        DebugLog.stop();

        assertTrue(seen.lines.contains("WARN a warning for both"), seen.lines.toString());
        assertTrue(seen.lines.contains("INFO the start line"), seen.lines.toString());
        for (String l : seen.lines) assertFalse(l.contains("modeldebug") || l.contains("filler") || l.contains("debug line"), l);

        File file = new File(dir, "ntm-gunfeel-debug.log");
        assertTrue(file.exists());
        assertTrue(new File(dir, "ntm-gunfeel-debug.1.log").exists());
        assertTrue(new File(dir, "ntm-gunfeel-debug.2.log").exists());
        assertFalse(new File(dir, "ntm-gunfeel-debug.3.log").exists());
        assertEquals(3, dir.list().length);
        for (String f : new String[] {"ntm-gunfeel-debug.log", "ntm-gunfeel-debug.1.log", "ntm-gunfeel-debug.2.log"})
            assertTrue(new File(dir, f).length() <= 64 * 1024 + 1024, f + " " + new File(dir, f).length());
        assertTrue(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).contains("filler 1999"), "the last line in the file");
    }
}
