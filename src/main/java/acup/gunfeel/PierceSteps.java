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
// account of the blocks is core's (PierceStepsBase); here the bullet, its damage and the game's hits.
public final class PierceSteps extends PierceStepsBase {
    // the bullet's own steps (MixinBulletServer)
    public interface Holder {
        PierceSteps gunfeel$steps();
    }

    public PierceSteps() {
        from = Vec3.ZERO;
    }

    public static PierceSteps of(EntityBulletBaseMK4 bullet) {
        return ((Holder) bullet).gunfeel$steps();
    }

    // a new tick's path from `from`
    public void start(Vec3 from) {
        startOf(from);
    }

    // a block pierced, paid for at once: the damage was `before`
    public void pierced(BlockHitResult in, BlockHitResult out, float before, double spentBefore) {
        piercedOf(in, out, before, spentBefore);
    }

    // a creature is hit at `at`: the bullet's damage as it is there
    public void enter(EntityBulletBaseMK4 bullet, Vec3 at) {
        enterOf(bullet, at);
    }

    // the tick is over: what is still owed is paid by the damage the bullet has now
    public void finish(EntityBulletBaseMK4 bullet) {
        finishOf(bullet);
    }

    /** The system's live reads and writes, kept here for NTM's CORE probes. */
    static final class Reads implements acup.gunfeel.host.PierceShots {
        @Override public Object location(Object hit) { return ((BlockHitResult) hit).getLocation(); }
        @Override public double distance(Object point, Object from) { return ((Vec3) point).distanceTo((Vec3) from); }
        @Override public Object level(Object bullet) { return ((EntityBulletBaseMK4) bullet).level(); }
        @Override public boolean removed(Object bullet) { return ((EntityBulletBaseMK4) bullet).isRemoved(); }
        @Override public float water(Object bullet, Object in) { return acup.gunfeel.water.WaterTick.k((EntityBulletBaseMK4) bullet, ((BlockHitResult) in).getLocation()); }
        @Override public float damage(Object bullet) { return ((EntityBulletBaseMK4) bullet).damage; }
        @Override public void damage(Object bullet, float value) { ((EntityBulletBaseMK4) bullet).damage = value; }
        @Override public float pierced(Object level, Object in, Object out, float damage, Object bullet, Materials.Penetration penetration) {
            return PassableClip.pierced((Level) level, (BlockHitResult) in, (BlockHitResult) out, damage,
                    ((EntityBulletBaseMK4) bullet).config, penetration);
        }
        @Override public void stopAt(Object bullet, Object in) {
            Vec3 p = ((BlockHitResult) in).getLocation();
            ((EntityBulletBaseMK4) bullet).setPos(p.x, p.y, p.z);
            ((EntityBulletBaseMK4) bullet).discard();
        }
        @Override public int id(Object bullet) { return ((EntityBulletBaseMK4) bullet).getId(); }
        @Override public void logLater(int id, int blocks, Object raw) {
            EntityBulletBaseMK4 bullet = (EntityBulletBaseMK4) raw;
            TestLog.s(id, "pierce_later", "blocks", blocks, "damage", bullet.damage);
        }
        @Override public void logBlock(Object raw, Object world, Object hit, float left) {
            EntityBulletBaseMK4 bullet = (EntityBulletBaseMK4) raw;
            Level level = (Level) world;
            BlockHitResult in = (BlockHitResult) hit;
            TestLog.s(bullet.getId(), "block", "block", level.getBlockState(in.getBlockPos()), "pos", in.getBlockPos(),
                    "at", in.getLocation(), "material", WorldLog.material(level, in), "result", left < 0F ? "stop" : "pierce",
                    "why", "after_entity", "damage", bullet.damage, "left", left);
        }
    }
}
