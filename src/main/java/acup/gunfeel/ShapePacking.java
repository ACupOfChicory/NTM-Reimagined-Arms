package acup.gunfeel;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

/** Только опубликованные неизменяемые формы; временные массивы строителя сюда не передаются. */
final class ShapePacking {
    static final ShapePacking SHARED=new ShapePacking(PassableClip::pack);
    private final Map<long[],String> cache=new WeakHashMap<>();
    private final Function<long[],String> encoder;
    ShapePacking(Function<long[],String> encoder){this.encoder=encoder;}
    String pack(long[] shape) {
        synchronized(cache){String found=cache.get(shape);if(found!=null)return found;}
        // Сжатие не держит блокировку: сервер не ждёт фонового сохранения клиента.
        String packed=encoder.apply(shape);
        synchronized(cache){cache.put(shape,packed);}
        return packed;
    }
}
