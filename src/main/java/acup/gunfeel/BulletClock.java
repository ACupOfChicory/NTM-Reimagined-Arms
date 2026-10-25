package acup.gunfeel;

// Время траектории в игровых тиках. Повторные кадры и пауза не двигают пулю,
// а коррекция серверных часов назад не заставляет трассер лететь обратно.
public final class BulletClock {
    private double time = Double.NaN;
    public double sample(long tick, double partial) {
        double now = tick + Math.clamp(partial, 0, 1);
        if (Double.isNaN(time) || now > time) time = now;
        return time;
    }
    public double current() { return Double.isNaN(time) ? 0 : time; }
    public void reset() { time = Double.NaN; }
}
