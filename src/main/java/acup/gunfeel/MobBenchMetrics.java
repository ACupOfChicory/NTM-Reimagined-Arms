package acup.gunfeel;

// Только отдельный запуск стенда. Без свойства JVM JIT убирает измерения.
public final class MobBenchMetrics {
    public static final boolean ENABLED=Boolean.getBoolean("ntmgunfeel.benchMobs");
    public static final boolean LEGACY=ENABLED && Boolean.getBoolean("ntmgunfeel.benchLegacy");
    public static volatile int mode=-1;
    public static final int CAPTURE=0,SYNC=1,CONTOUR=2,DRAW=3;
    static final long[] nanos=new long[4];
    static long published,shapes;
    static final java.util.concurrent.atomic.LongAdder bytes=new java.util.concurrent.atomic.LongAdder();
    static final java.util.concurrent.atomic.LongAdder packets=new java.util.concurrent.atomic.LongAdder();
    private MobBenchMetrics() {}
    public static long start() { return ENABLED?System.nanoTime():0; }
    public static void add(int part,long started) { if(ENABLED) nanos[part]+=System.nanoTime()-started; }
    public static void packet(int size) { if(ENABLED) { bytes.add(size); packets.increment(); } }
}
