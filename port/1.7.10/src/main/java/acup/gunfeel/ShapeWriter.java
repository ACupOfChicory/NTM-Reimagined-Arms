package acup.gunfeel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// 26.2's ShapeStore.background for 1.7.10: what MachineScan takes in the game written to config on a thread of its own,
// the newest bytes of each file only, through a temporary file so a game closed meanwhile leaves the old one whole.
final class ShapeWriter {
    private ShapeWriter() {
    }

    interface Bytes {
        byte[] get() throws Exception;
    }

    private static final Map<Path, Bytes> WAITING = new LinkedHashMap<Path, Bytes>();
    private static final ExecutorService THREAD = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ntm-gunfeel shape writer");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    // the bytes (made on the writer's thread) for the file; an earlier request for it not yet written is dropped
    static void write(Path file, Bytes bytes) {
        synchronized (WAITING) {
            boolean queued = WAITING.containsKey(file);
            WAITING.put(file, bytes);
            if (queued) return;
        }
        THREAD.execute(() -> {
            Bytes b;
            synchronized (WAITING) {
                b = WAITING.remove(file);
            }
            if (b == null) return;
            try {
                byte[] data = b.get();
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.write(tmp, data);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                GunFeel.LOGGER.warn("[ntm-gunfeel] Could not write " + file, e);
            }
        });
    }
}
