package acup.gunfeel;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

// The blocks a bullet pierced this tick, in order along its path, so that a creature hit on the way gets the damage the
// bullet has where it is. NTM asks for the blocks of the whole tick's path first (BulletServer.clip, which pays for
// them at once) and only then looks for creatures on the path up to where the blocks stop it: a golem standing before
// ground the bullet pierces got what was left after the ground. Now the damage is taken back to before the first block
// beyond the creature (enter), and the blocks the bullet has not paid for again are paid by the damage it has after
// the hit (the hit lowers it for some rounds), when it meets the next creature or at the tick's end (finish). The
// account of the blocks is core's (PierceLedger); here the bullet, its damage and the game's hits.
public final class PierceSteps {
    // the bullet's own steps (MixinBulletServer)
    public interface Holder {
        PierceSteps gunfeel$steps();
    }

    final Materials.Penetration penetration = new Materials.Penetration();

    // the blocks pierced this tick, and those paid for in bullet.damage
    private final PierceLedger<BlockHitResult> steps = new PierceLedger<>();
    private Vec3 from = Vec3.ZERO;

    public static PierceSteps of(EntityBulletBaseMK4 bullet) {
        return ((Holder) bullet).gunfeel$steps();
    }

    // a new tick's path from `from`
    public void start(Vec3 from) {
        this.from = from;
        steps.clear();
    }

    // a block pierced, paid for at once: the damage was `before`
    public void pierced(BlockHitResult in, BlockHitResult out, float before, double spentBefore) {
        steps.pierced(in.getLocation().distanceTo(from), before, spentBefore, in, out);
    }

    // a creature is hit at `at`: the bullet's damage as it is there
    public void enter(EntityBulletBaseMK4 bullet, Vec3 at) {
        if (steps.size() == 0) return;
        int k = steps.firstFrom(at.distanceTo(from));
        if (steps.paidPast(k)) {
            // the blocks beyond it were paid too early: back to the damage before the first of them
            bullet.damage = steps.damageBefore(k);
            penetration.restore(steps.spentBefore(k));
            steps.unpayTo(k);
            if (TestLog.server) TestLog.s(bullet.getId(), "pierce_later", "blocks", steps.size() - k, "damage", bullet.damage);
        } else {
            payTo(bullet, k);
        }
    }

    // the tick is over: what is still owed is paid by the damage the bullet has now
    public void finish(EntityBulletBaseMK4 bullet) {
        payTo(bullet, steps.size());
        steps.clear();
    }

    private void payTo(EntityBulletBaseMK4 bullet, int k) {
        Level level = bullet.level();
        while (steps.owes(k) && !bullet.isRemoved()) {
            int i = steps.payNext();
            BlockHitResult in = steps.wayIn(i), out = steps.wayOut(i);
            float wk = acup.gunfeel.water.WaterTick.k(bullet, in.getLocation());
            float left = wk == 1F ? PassableClip.pierced(level, in, out, bullet.damage, bullet.config, penetration)
                    : wk <= 0F ? -1F : PassableClip.pierced(level, in, out, bullet.damage * wk, bullet.config, penetration) / wk;
            if (TestLog.server) TestLog.s(bullet.getId(), "block", "block", level.getBlockState(in.getBlockPos()), "pos", in.getBlockPos(),
                    "at", in.getLocation(), "material", WorldLog.material(level, in), "result", left < 0F ? "stop" : "pierce",
                    "why", "after_entity", "damage", bullet.damage, "left", left);
            if (left < 0F) {
                // it stops in this block now that it hit a creature before it
                Vec3 p = in.getLocation();
                bullet.setPos(p.x, p.y, p.z);
                bullet.discard();
                return;
            }
            bullet.damage = left;
        }
    }
}
