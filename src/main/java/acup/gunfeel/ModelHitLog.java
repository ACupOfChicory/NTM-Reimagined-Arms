package acup.gunfeel;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;

// Диагностика отдельно от testlog: причины отказа нужны и тогда, когда тестовый журнал не включён.
public final class ModelHitLog {
    private static final Map<String, LongAdder> COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST = new java.util.HashMap<>();
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ntm-gunfeel");

    private ModelHitLog() {}

    public static void count(String side, String stage) {
        COUNTS.computeIfAbsent(side + "/" + stage, k -> new LongAdder()).increment();
    }

    public static void event(String side, String stage, Entity entity, Supplier<String> details) {
        count(side, stage);
        String type = entity == null ? "unknown" : BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        String key = side + "/" + stage + "/" + type;
        long now = System.currentTimeMillis();
        synchronized (LAST) {
            if (now - LAST.getOrDefault(key, 0L) < 5000) return;
            if (LAST.size() > 512) LAST.clear();
            LAST.put(key, now);
        }
        LOGGER.info("[modeldebug] {} {} entity={}#{} {}", side, stage, type, entity == null ? -1 : entity.getId(), details.get());
    }

    public static Map<String, Long> counts(String side) {
        Map<String, Long> out = new TreeMap<>();
        COUNTS.forEach((k, v) -> { if (k.startsWith(side + "/")) out.put(k.substring(side.length() + 1), v.sum()); });
        return out;
    }
}
