package acup.gunfeel.gl54;

// How the GL-54's grenade flies, as NTM's bullet entity moves it each tick (EntityThrowableNT.tick): the position advances by
// the motion vector times the speed, then the motion keeps its drag (EntityBulletBaseMK4.getAirDrag: 1, none) and loses
// the gravity from its height. The motion starts as the unit heading, so the drop in blocks a tick is the gravity times
// the speed. The numbers come from the grenade's config (Gl54Grenade); this only walks them. Flat ground is assumed and
// the barrel's offset from the eye is ignored: the angles are against the horizontal through the eye.
public final class Gl54Ballistics {
    // NTM's EntityBulletBaseMK4.getAirDrag
    public static final double AIR_DRAG = 1.0;
    // the grenade lives this long (Gl54Grenade.LIFE)
    public static final int TICKS = Gl54Grenade.LIFE;
    // the launch angles tried, radians above the horizontal, at most this
    private static final double STEEPEST = Math.toRadians(80);

    private Gl54Ballistics() {
    }

    // The horizontal distance at which a grenade fired `angle` radians above the horizontal comes down to `below` blocks under
    // the barrel (0: the eye's height, 1.62: the floor under a standing player), by the ticks' own steps; the last step is
    // cut where it crosses. Zero if it never does.
    public static double range(double angle, double speed, double gravity, double drag, double below) {
        double mx = Math.cos(angle), my = Math.sin(angle);
        double x = 0, y = 0;
        for (int tick = 0; tick < TICKS; tick++) {
            double nx = x + mx * speed, ny = y + my * speed;
            if (ny <= -below && (y > -below || tick > 0 && my < 0)) {
                double t = (y + below) / (y - ny);
                return x + (nx - x) * (Double.isFinite(t) ? t : 1.0);
            }
            x = nx;
            y = ny;
            mx *= drag;
            my = my * drag - gravity;
        }
        return 0;
    }

    public static double range(double angle, double below) {
        return range(angle, Gl54Grenade.SPEED, Gl54Grenade.GRAVITY, AIR_DRAG, below);
    }

    // The steepest angle at which the grenade is still down within its life (steeper it is gone before it lands). With a drop as
    // light as Earth's the 45 degrees that would carry farthest take longer than that, so the farthest is this one.
    static double steepestInLife(double speed, double gravity, double drag) {
        if (range(STEEPEST, speed, gravity, drag, 0) > 0) return STEEPEST;
        double lo = 0, hi = STEEPEST;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            if (range(mid, speed, gravity, drag, 0) > 0) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    // The height of the grenade, blocks above the barrel, when it has flown `distance` blocks out (horizontally) after a launch
    // `angle` above the horizontal, by the ticks' own steps; NaN if it is gone (its life ends) or never gets out that far.
    public static double heightAt(double angle, double speed, double gravity, double drag, double distance) {
        double mx = Math.cos(angle), my = Math.sin(angle);
        double x = 0, y = 0;
        for (int tick = 0; tick < TICKS; tick++) {
            double nx = x + mx * speed, ny = y + my * speed;
            if (nx >= distance) {
                double t = nx == x ? 1.0 : (distance - x) / (nx - x);
                return y + (ny - y) * t;
            }
            x = nx;
            y = ny;
            mx *= drag;
            my = my * drag - gravity;
        }
        return Double.NaN;
    }

    // The launch angle that puts the grenade through a point `distance` blocks out and `height` blocks above (or, negative,
    // below) the barrel: the low arc, the first angle upward from straight down at which it gets as high as the point. The
    // range is not the eye's height now but the point's, so it is a hill or a hollow. Past the grenade's reach it is the
    // angle of the farthest range, and not reachable.
    public static Holdover elevation(double distance, double height, double speed, double gravity, double drag) {
        double step = Math.toRadians(0.5), from = -Math.toRadians(80);
        double previous = from;
        for (double a = from; a <= STEEPEST; a += step) {
            double h = heightAt(a, speed, gravity, drag, distance);
            if (!Double.isNaN(h) && h >= height) {
                if (a == from) return new Holdover(a, true);
                double lo = previous, hi = a;
                for (int i = 0; i < 50; i++) {
                    double mid = (lo + hi) / 2;
                    double hm = heightAt(mid, speed, gravity, drag, distance);
                    if (Double.isNaN(hm) || hm < height) lo = mid;
                    else hi = mid;
                }
                return new Holdover((lo + hi) / 2, true);
            }
            previous = a;
        }
        return new Holdover(farthestAngle(speed, gravity, drag), false);
    }

    public static Holdover elevation(double distance, double height) {
        return elevation(distance, height, Gl54Grenade.SPEED, Gl54Grenade.GRAVITY, AIR_DRAG);
    }

    // the launch angle that gives the farthest range at the eye's height (golden-section search; the range rises, then falls,
    // or rises to the life's end)
    public static double farthestAngle(double speed, double gravity, double drag) {
        double lo = 0, hi = steepestInLife(speed, gravity, drag);
        double phi = (Math.sqrt(5) - 1) / 2;
        double a = hi - phi * (hi - lo), b = lo + phi * (hi - lo);
        double ra = range(a, speed, gravity, drag, 0), rb = range(b, speed, gravity, drag, 0);
        for (int i = 0; i < 60; i++) {
            if (ra < rb) {
                lo = a;
                a = b;
                ra = rb;
                b = lo + phi * (hi - lo);
                rb = range(b, speed, gravity, drag, 0);
            } else {
                hi = b;
                b = a;
                rb = ra;
                a = hi - phi * (hi - lo);
                ra = range(a, speed, gravity, drag, 0);
            }
        }
        return (lo + hi) / 2;
    }

    // The holdover: the barrel's raise above the line to the target for a target `distance` blocks away on level ground
    // (radians; the low arc), and whether the grenade reaches that far. Beyond reach it is the farthest angle.
    public record Holdover(double angle, boolean reachable) {
    }

    public static Holdover holdover(double distance, double speed, double gravity, double drag) {
        double best = farthestAngle(speed, gravity, drag);
        double reach = range(best, speed, gravity, drag, 0);
        if (distance > reach) return new Holdover(best, false);
        // the least range is the flat shot's (the first steps carry it forward before it can fall to the eye's height)
        if (distance <= range(0, speed, gravity, drag, 0)) return new Holdover(0, true);
        double lo = 0, hi = best;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            if (range(mid, speed, gravity, drag, 0) < distance) lo = mid;
            else hi = mid;
        }
        return new Holdover((lo + hi) / 2, true);
    }

    public static Holdover holdover(double distance) {
        return holdover(distance, Gl54Grenade.SPEED, Gl54Grenade.GRAVITY, AIR_DRAG);
    }

    // the farthest a grenade goes at the eye's height
    public static double maxRange() {
        return range(farthestAngle(Gl54Grenade.SPEED, Gl54Grenade.GRAVITY, AIR_DRAG), 0);
    }
}
