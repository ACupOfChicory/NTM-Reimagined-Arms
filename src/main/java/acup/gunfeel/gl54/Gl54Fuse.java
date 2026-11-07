package acup.gunfeel.gl54;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

// A grenade fired while the gun holds a measured range bursts after flying that far (horizontally, from where it was fired),
// from the hip as well as aimed (design.md: "the grenade bursts after flying it, wherever it is fired"). An impact before that
// still bursts it, and the fuse is armed after the same 3 blocks as the rest (a range under 3 blocks fuses at 3). With no
// measurement there is no fuse. The distance is taken from the gun in the shooter's hand on the grenade's first tick.
final class Gl54Fuse {
    private static final Map<Entity, Double> FUSES = Collections.synchronizedMap(new WeakHashMap<>());

    private Gl54Fuse() {
    }

    // the horizontal distance at which a grenade for this stored range bursts: the range and the shooter's shift (the offset);
    // NaN for none. Not before the arming distance.
    static double distance(float range, int offset) {
        return Float.isNaN(range) ? Double.NaN : Math.max(range + offset, Gl54Grenade.ARM_DISTANCE);
    }

    // where in a step that went from horizontal distance d0 to d1 the fuse f is crossed (0..1); NaN if it is not
    static double crossing(double d0, double d1, double f) {
        if (Double.isNaN(f) || d0 >= f || d1 < f) return Double.NaN;
        return d1 == d0 ? 1.0 : (f - d0) / (d1 - d0);
    }

    // The burst for a point in the world: the horizontal distance from where the grenade begins to the point, and the shift. The
    // distance is taken at the shot, so a shooter who has moved since the measurement still has it burst at the point.
    static double distance(Vec3 target, Vec3 start, int offset) {
        if (target == null) return Double.NaN;
        return Math.max(Math.hypot(target.x - start.x, target.z - start.z) + offset, Gl54Grenade.ARM_DISTANCE);
    }

    // the grenade's first tick (it begins at `start`): reads the gun in the shooter's hand, the point it found if it kept one,
    // else the range
    static void light(EntityBulletBaseMK4 bullet, Vec3 start) {
        if (!Gl54Server.enabled() || !(bullet.getThrower() instanceof LivingEntity shooter)) return;
        net.minecraft.world.item.ItemStack stack = shooter.getMainHandItem();
        Vec3 target = Gl54Range.target(stack);
        double f = target != null ? distance(target, start, Gl54Range.offset(stack)) : distance(Gl54Range.get(stack), Gl54Range.offset(stack));
        if (!Double.isNaN(f)) FUSES.put(bullet, f);
    }

    // every tick after the grenade moved: bursts it at the fuse's distance (start = where it was fired)
    static void step(EntityBulletBaseMK4 bullet, Vec3 start) {
        Double f = FUSES.get(bullet);
        if (f == null || bullet.isRemoved()) return;
        double d0 = Math.hypot(bullet.xo - start.x, bullet.zo - start.z), d1 = Math.hypot(bullet.getX() - start.x, bullet.getZ() - start.z);
        double t = crossing(d0, d1, f);
        if (Double.isNaN(t)) return;
        Vec3 at = new Vec3(bullet.xo, bullet.yo, bullet.zo).lerp(bullet.position(), t);
        FUSES.remove(bullet);
        Gl54Grenade.impact(bullet, new BlockHitResult(at, Direction.UP, BlockPos.containing(at), false));
    }

    static Double of(Entity e) {
        return FUSES.get(e);
    }
}
