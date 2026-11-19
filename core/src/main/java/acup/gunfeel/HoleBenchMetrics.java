package acup.gunfeel;

// Только отдельный запуск измерения; обычная игра не меняет поведение.
public final class HoleBenchMetrics {
    public static final boolean ENABLED=Boolean.getBoolean("ntmgunfeel.benchHoles");
    public static final boolean LEGACY=ENABLED && Boolean.getBoolean("ntmgunfeel.holeBenchLegacy");
    public static final boolean CHECK_MOTION=ENABLED && Boolean.getBoolean("ntmgunfeel.holeBenchCheckMotion");
    public static volatile boolean omitBlast;
    static volatile boolean phaseLegacy;
    public static boolean legacy() { return ENABLED && (LEGACY || phaseLegacy); }
    static final long[] nanos=new long[17];
    static long projectiles,explosions,maxHole;
    static long poseChecks,poseUpdates;
    static volatile String failure;
    private HoleBenchMetrics() {}
    public static void add(int part,long elapsed) {
        if(!ENABLED) return;
        nanos[part]+=elapsed;
        if(part==Perf.HOLE_MAKE) maxHole=Math.max(maxHole,elapsed);
    }
    public static boolean explosion() {
        if(!ENABLED) return false;
        explosions++; return omitBlast;
    }
}
