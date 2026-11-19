package acup.gunfeel;

// Только отдельный запуск стенда. Без свойства JVM JIT убирает измерения.
public final class MobBenchMetrics {
    public static final boolean ENABLED=Boolean.getBoolean("ntmgunfeel.benchMobs");
    public static final boolean LEGACY=ENABLED && Boolean.getBoolean("ntmgunfeel.benchLegacy");
    public static volatile int mode=-1;
    // поток отрисовки: CAPTURE публикация слоёв, SYNC отправка, CONTOUR и DRAW F3+B, GEOMETRY снятие вершин и частей
    // (ModelPart, группы NTM, поток вершин), PUBLISH проверка и запись позы
    public static final int CAPTURE=0,SYNC=1,CONTOUR=2,DRAW=3,GEOMETRY=4,PUBLISH=5,STAGES=6;
    static final long[] nanos=new long[STAGES];
    static long published,shapes;
    static final java.util.concurrent.atomic.LongAdder bytes=new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder packets=new java.util.concurrent.atomic.LongAdder();
    // другие потоки: упаковка пакета позы (где бы ни шла) и приём позы сервером
    static final java.util.concurrent.atomic.LongAdder encode=new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder server=new java.util.concurrent.atomic.LongAdder();
    private MobBenchMetrics() {}
    public static long start() { return ENABLED?System.nanoTime():0; }
    public static void add(int part,long started) { if(ENABLED) nanos[part]+=System.nanoTime()-started; }
    public static void packet(int size) { if(ENABLED) { bytes.add(size); packets.increment(); } }
}
