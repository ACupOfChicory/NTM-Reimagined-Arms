package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// DebugLog: the mod's lines go to their own file, the game's log gets only its warnings and errors, and the file is
// kept to its size and two older ones.
class DebugLogTest {
    // what reaches the game's own log (the root logger's appenders)
    static final class Seen extends AbstractAppender {
        final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        Seen() {
            super("Seen", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent e) {
            lines.add(e.getLevel() + " " + e.getMessage().getFormattedMessage());
        }
    }

    @Test
    void ownFileWarningsInTheGameLogKeptToItsSize(@TempDir Path dir) throws Exception {
        LoggerContext ctx = ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(DebugLog.NAME)).getContext();
        Seen seen = new Seen();
        seen.start();
        ctx.getConfiguration().getRootLogger().addAppender(seen, null, null);
        ctx.updateLoggers();

        DebugLog.setup(false, dir.toString(), 64 * 1024);
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DebugLog.NAME);
        log.info("[modeldebug] a line for the file");
        log.debug("a debug line for the file");
        log.warn("a warning for both");
        String filler = "x".repeat(200);
        for (int i = 0; i < 2000; i++) log.info("filler {} {}", i, filler);
        // another logger's lines are as they were
        org.slf4j.LoggerFactory.getLogger("NTM Reimagined Arms").info("the start line");
        ctx.stop();

        assertTrue(seen.lines.contains("WARN a warning for both"), seen.lines.toString());
        assertTrue(seen.lines.contains("INFO the start line"), seen.lines.toString());
        assertFalse(seen.lines.stream().anyMatch(l -> l.contains("modeldebug") || l.contains("filler") || l.contains("debug line")), "the mod's info lines in the game's log");

        Path file = dir.resolve("ntm-gunfeel-debug.log");
        assertTrue(Files.exists(file));
        // 2000 lines of 200 characters, the file 64 KB: the newest in the file, two older ones, nothing older kept
        assertTrue(Files.exists(dir.resolve("ntm-gunfeel-debug.1.log")));
        assertTrue(Files.exists(dir.resolve("ntm-gunfeel-debug.2.log")));
        assertFalse(Files.exists(dir.resolve("ntm-gunfeel-debug.3.log")));
        try (var files = Files.list(dir)) {
            assertEquals(3, files.count());
        }
        for (String f : List.of("ntm-gunfeel-debug.log", "ntm-gunfeel-debug.1.log", "ntm-gunfeel-debug.2.log"))
            assertTrue(Files.size(dir.resolve(f)) <= 64 * 1024 + 1024, f + " " + Files.size(dir.resolve(f)));
        assertTrue(Files.readString(file).contains("filler 1999"), "the last line in the file");
    }
}
