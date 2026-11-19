package acup.gunfeel;

// Время траектории в игровых тиках. Повторные кадры и пауза не двигают пулю,
// а коррекция серверных часов назад не заставляет трассер лететь обратно.
public final class BulletClock {
    public record Segment(double head, double tail, boolean expired) {
        public boolean visible() { return head > tail; }
    }
    public static Segment segment(double age, double end, double life, double trailTicks) {
        double head = Math.min(age, Double.isNaN(end) ? life : end);
        double tail = Math.max(0, age - trailTicks);
        // На первом кадре head==tail==0: это ещё не начавшийся выстрел, а не закончившийся.
        return new Segment(head, tail, age > 0 && tail >= head);
    }
    private double time = Double.NaN;
    public double sample(long tick, double partial) {
        double now = tick + Math.clamp(partial, 0, 1);
        if (Double.isNaN(time) || now > time) time = now;
        return time;
    }
    public double current() { return Double.isNaN(time) ? 0 : time; }
    public void reset() { time = Double.NaN; }
}
