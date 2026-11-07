package acup.gunfeel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

// GunFeel is a client class: a dedicated server cannot load it (NoClassDefFoundError, ClientLevel), so code the server
// runs logs through DebugLog.LOGGER. A file that does not touch the client itself must not reach for GunFeel.LOGGER.
class ServerLoggerTest {
    static final Path SRC = Path.of("src/main/java");
    static final Pattern CLIENT = Pattern.compile("net\\.minecraft\\.client|neoforge\\.client|com\\.hbm\\.render|Minecraft\\.getInstance");

    @Test
    void commonCodeLogsThroughDebugLog() throws IOException {
        List<String> bad;
        try (Stream<Path> files = Files.walk(SRC)) {
            bad = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/client/"))
                    .filter(p -> {
                        try {
                            String s = Files.readString(p, StandardCharsets.UTF_8);
                            return s.contains("GunFeel.LOGGER") && !CLIENT.matcher(s).find();
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .map(p -> SRC.relativize(p).toString())
                    .sorted().toList();
        }
        assertEquals(List.of(), bad, "common code reaching for the client's GunFeel.LOGGER");
    }
}
