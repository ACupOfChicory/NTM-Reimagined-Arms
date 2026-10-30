package acup.gunfeel;

/** Общие часы магазина и руки: пауза, извлечение, свободный полёт, ловля, возврат. */
public final class MagazineInspect {
    public static final int PREPARE = 250, MIN_RELEASE = 350, MAX_RELEASE = 1000;
    private MagazineInspect() {}
    public static int release(float value) {
        return Float.isFinite(value) ? Math.clamp(Math.round(value), MIN_RELEASE, MAX_RELEASE) : 550;
    }
    public static int handTime(int time, float release) {
        int r=release(release);
        return time>=r && time<r+700?r:time;
    }
    public static float handWeight(float time,float release) {
        int r=release(release);
        float w=Math.clamp(Math.min(time/PREPARE,(r+1350-time)/200F),0F,1F);
        return w*w*(3-2*w);
    }
}
