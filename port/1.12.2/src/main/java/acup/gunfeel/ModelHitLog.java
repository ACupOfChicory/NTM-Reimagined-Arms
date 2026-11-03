package acup.gunfeel;

// port: copy of 26.2 src/main/java/acup/gunfeel/ModelHitLog.java at 0762086; changes marked "port:"

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import net.minecraft.entity.Entity; // port: 1.7.10 package

// Диагностика отдельно от testlog: причины отказа нужны и тогда, когда тестовый журнал не включён.
public final class ModelHitLog {
    private static final Map<String, LongAdder> COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST = new java.util.HashMap<>();
    private static final org.apache.logging.log4j.Logger LOGGER = GunFeel.LOGGER; // port: log4j

    private ModelHitLog() {}

    public static void count(String side, String stage) {
        COUNTS.computeIfAbsent(side + "/" + stage, k -> new LongAdder()).increment();
    }

    public static void event(String side, String stage, Entity entity, Supplier<String> details) {
        count(side, stage);
        String type = entity == null ? "unknown" : EntityKeys.type(entity) /* port: 1.7.10 entity names */;
        String key = side + "/" + stage + "/" + type;
        long now = System.currentTimeMillis();
        synchronized (LAST) {
            if (now - LAST.getOrDefault(key, 0L) < 5000) return;
            if (LAST.size() > 512) LAST.clear();
            LAST.put(key, now);
        }
        LOGGER.info("[modeldebug] {} {} entity={}#{} {}", side, stage, type, entity == null ? -1 : entity.getEntityId() /* port */, details.get());
    }

    public static Map<String, Long> counts(String side) {
        Map<String, Long> out = new TreeMap<>();
        COUNTS.forEach((k, v) -> { if (k.startsWith(side + "/")) out.put(k.substring(side.length() + 1), v.sum()); });
        return out;
    }
}
