package acup.gunfeel.gl54;

import net.minecraft.world.phys.Vec3;

// Where the barrel has to point for the grenade to meet a point in the world, from where the shooter stands now: the heading
// toward the point and the launch angle that Gl54Ballistics finds for the point's distance and height against the muzzle
// (not flat ground). The muzzle is where NTM puts the grenade: the receiver's offset from the eye, 0.75 forward along the look and
// 0.0625 down, so it moves a little with the aim, and the solution is taken again from where it then is. Aimed, NTM puts the
// shot on the line of sight with no sideways shift (from the hip it is 0.1875 to the right); the marker is for the aimed gun.
public final class Gl54Aim {
    public static final double FORWARD = 0.75, DOWN = 0.0625;

    private Gl54Aim() {
    }

    // the direction to fly (a unit vector), the muzzle it is for, the horizontal distance from there to the point, the launch
    // angle, and whether the grenade reaches that far
    public record Aim(Vec3 direction, Vec3 muzzle, double horizontal, double elevation, boolean reachable) {
    }

    // where a grenade fired along `look` from the eye begins
    public static Vec3 muzzle(Vec3 eye, Vec3 look) {
        Vec3 f = look.normalize();
        double h = Math.hypot(f.x, f.z);
        Vec3 right = h < 1e-6 ? new Vec3(-1, 0, 0) : new Vec3(-f.z / h, 0, f.x / h);
        Vec3 up = right.cross(f);
        return eye.add(f.scale(FORWARD)).add(up.scale(-DOWN));
    }

    // `look` only seeds the muzzle's place; the answer is the same for any look near the aim
    public static Aim solve(Vec3 eye, Vec3 look, Vec3 target) {
        Vec3 dir = look.normalize();
        Vec3 muzzle = muzzle(eye, dir);
        Gl54Ballistics.Holdover h = new Gl54Ballistics.Holdover(0, true);
        for (int i = 0; i < 4; i++) {
            Vec3 d = target.subtract(muzzle);
            double flat = Math.hypot(d.x, d.z);
            if (flat < 1e-3) {
                // straight above or below: up or down
                return new Aim(new Vec3(0, d.y >= 0 ? 1 : -1, 0), muzzle, flat, d.y >= 0 ? Math.PI / 2 : -Math.PI / 2, true);
            }
            h = Gl54Ballistics.elevation(flat, d.y);
            double c = Math.cos(h.angle()), s = Math.sin(h.angle());
            dir = new Vec3(d.x / flat * c, s, d.z / flat * c);
            muzzle = muzzle(eye, dir);
        }
        Vec3 d = target.subtract(muzzle);
        return new Aim(dir, muzzle, Math.hypot(d.x, d.z), h.angle(), h.reachable());
    }
}
