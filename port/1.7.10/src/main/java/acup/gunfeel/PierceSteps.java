package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import net.minecraft.world.World;

// port: copied from 26.2 (0762086). BlockHitResult -> BlockRay.Hit, Vec3 -> its numbers, level() -> worldObj,
// isRemoved() -> isDead, setPos -> setPosition, discard -> setDead; the record Step -> a class.
// The blocks a bullet pierced this tick, in order along its path, so that a creature hit on the way gets the damage the
// bullet has where it is. NTM asks for the blocks of the whole tick's path first (BulletServer.clip, which pays for
// them at once) and only then looks for creatures on the path up to where the blocks stop it: a golem standing before
// ground the bullet pierces got what was left after the ground. Now the damage is taken back to before the first block
// beyond the creature (enter), and the blocks the bullet has not paid for again are paid by the damage it has after
// the hit (the hit lowers it for some rounds), when it meets the next creature or at the tick's end (finish).
public final class PierceSteps {
    // the bullet's own steps (MixinBulletServer)
    public interface Holder {
        PierceSteps gunfeel$steps();
    }

    private static final class Step {
        final double at, spentBefore;
        final float before;
        final BlockRay.Hit in, out;

        Step(double at, float before, double spentBefore, BlockRay.Hit in, BlockRay.Hit out) {
            this.at = at;
            this.before = before;
            this.spentBefore = spentBefore;
            this.in = in;
            this.out = out;
        }
    }

    final Materials.Penetration penetration = new Materials.Penetration();

    private final List<Step> steps = new ArrayList<Step>();
    private double fx, fy, fz;
    // steps paid for in bullet.damage
    private int paid;

    public static PierceSteps of(EntityBulletBaseMK4 bullet) {
        return ((Holder) bullet).gunfeel$steps();
    }

    // a new tick's path from (x, y, z)
    public void start(double x, double y, double z) {
        fx = x;
        fy = y;
        fz = z;
        steps.clear();
        paid = 0;
    }

    // a block pierced, paid for at once: the damage was `before`
    public void pierced(BlockRay.Hit in, BlockRay.Hit out, float before, double spentBefore) {
        steps.add(new Step(in.distance(fx, fy, fz), before, spentBefore, in, out));
        paid = steps.size();
    }

    // a creature is hit at (x, y, z): the bullet's damage as it is there
    public void enter(EntityBulletBaseMK4 bullet, double x, double y, double z) {
        if (steps.isEmpty()) return;
        double dx = x - fx, dy = y - fy, dz = z - fz;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int k = 0;
        while (k < steps.size() && steps.get(k).at < d) k++;
        if (paid > k) {
            // the blocks beyond it were paid too early: back to the damage before the first of them
            bullet.damage = steps.get(k).before;
            penetration.restore(steps.get(k).spentBefore);
            paid = k;
            if (TestLog.server) TestLog.s(bullet.getEntityId(), "pierce_later", "blocks", steps.size() - k, "damage", bullet.damage);
        } else {
            payTo(bullet, k);
        }
    }

    // the tick is over: what is still owed is paid by the damage the bullet has now
    public void finish(EntityBulletBaseMK4 bullet) {
        payTo(bullet, steps.size());
        steps.clear();
        paid = 0;
    }

    private void payTo(EntityBulletBaseMK4 bullet, int k) {
        World world = bullet.worldObj;
        while (paid < k && !bullet.isDead) {
            Step s = steps.get(paid++);
            float left = PassableClip.pierced(world, s.in, s.out, bullet.damage, bullet.config, penetration);
            if (TestLog.server) TestLog.s(bullet.getEntityId(), "block", "block", TestLog.block(world, s.in.x, s.in.y, s.in.z), "pos", TestLog.pos(s.in),
                    "at", TestLog.point(s.in), "material", TestLog.material(world, s.in.x, s.in.y, s.in.z), "result", left < 0F ? "stop" : "pierce",
                    "why", "after_entity", "damage", bullet.damage, "left", left);
            if (left < 0F) {
                // it stops in this block now that it hit a creature before it
                bullet.setPosition(s.in.ax, s.in.ay, s.in.az);
                bullet.setDead();
                return;
            }
            bullet.damage = left;
        }
    }
}
