package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

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

    private record Step(double at, float before, double spentBefore, BlockHitResult in, BlockHitResult out) {
    }

    final Materials.Penetration penetration = new Materials.Penetration();

    private final List<Step> steps = new ArrayList<>();
    private Vec3 from = Vec3.ZERO;
    // steps paid for in bullet.damage
    private int paid;

    public static PierceSteps of(EntityBulletBaseMK4 bullet) {
        return ((Holder) bullet).gunfeel$steps();
    }

    // a new tick's path from `from`
    public void start(Vec3 from) {
        this.from = from;
        steps.clear();
        paid = 0;
    }

    // a block pierced, paid for at once: the damage was `before`
    public void pierced(BlockHitResult in, BlockHitResult out, float before, double spentBefore) {
        steps.add(new Step(in.getLocation().distanceTo(from), before, spentBefore, in, out));
        paid = steps.size();
    }

    // a creature is hit at `at`: the bullet's damage as it is there
    public void enter(EntityBulletBaseMK4 bullet, Vec3 at) {
        if (steps.isEmpty()) return;
        double d = at.distanceTo(from);
        int k = 0;
        while (k < steps.size() && steps.get(k).at() < d) k++;
        if (paid > k) {
            // the blocks beyond it were paid too early: back to the damage before the first of them
            bullet.damage = steps.get(k).before();
            penetration.restore(steps.get(k).spentBefore());
            paid = k;
            if (TestLog.server) TestLog.s(bullet.getId(), "pierce_later", "blocks", steps.size() - k, "damage", bullet.damage);
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
        Level level = bullet.level();
        while (paid < k && !bullet.isRemoved()) {
            Step s = steps.get(paid++);
            float left = PassableClip.pierced(level, s.in(), s.out(), bullet.damage, bullet.config, penetration);
            if (TestLog.server) TestLog.s(bullet.getId(), "block", "block", level.getBlockState(s.in().getBlockPos()), "pos", s.in().getBlockPos(),
                    "at", s.in().getLocation(), "material", TestLog.material(level, s.in().getBlockPos()), "result", left < 0F ? "stop" : "pierce",
                    "why", "after_entity", "damage", bullet.damage, "left", left);
            if (left < 0F) {
                // it stops in this block now that it hit a creature before it
                Vec3 p = s.in().getLocation();
                bullet.setPos(p.x, p.y, p.z);
                bullet.discard();
                return;
            }
            bullet.damage = left;
        }
    }
}
