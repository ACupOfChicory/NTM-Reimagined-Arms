package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;

// A dev check that stops the game must end its JVM too: once the game's thread is over, a thread left running that is
// not a daemon keeps the process (and the gradle task waiting on it) alive for good, and Minecraft's shutdown watchdog,
// firing 15 s later, can hang in its crash report. Ten seconds after the game's thread ends, the threads still running
// are written to the log and to exit-threads.txt in the game directory, and the process is halted.
final class ExitWatch {
    private ExitWatch() {
    }

    static void start() {
        Thread game = Thread.currentThread();
        Thread t = new Thread(() -> {
            try {
                game.join();
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                return;
            }
            StringBuilder out = new StringBuilder("still running 10 s after the game stopped:\n");
            for (var e : Thread.getAllStackTraces().entrySet()) {
                Thread th = e.getKey();
                if (th.isDaemon() || th == Thread.currentThread()) continue;
                out.append('"').append(th.getName()).append("\" ").append(th.getState()).append('\n');
                for (var s : e.getValue()) out.append("    at ").append(s).append('\n');
            }
            DebugLog.LOGGER.error("[exitwatch] {}", out);
            try {
                Files.writeString(Path.of("exit-threads.txt"), out);
            } catch (Exception ignored) {
            }
            Runtime.getRuntime().halt(0);
        }, "ntm-gunfeel exit watch");
        t.setDaemon(true);
        t.start();
    }
}
