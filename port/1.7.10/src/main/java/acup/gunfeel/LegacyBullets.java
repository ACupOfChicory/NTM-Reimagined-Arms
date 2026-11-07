package acup.gunfeel;

import com.hbm.entity.projectile.EntityBullet;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.XFactory762mm;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldServer;

// port: 26.2's LegacyBullets (X547). NTM's old bullet, EntityBullet: the hunter chopper's and the cyber crab's; no gun of
// a player fires it. NTM counts its hit at the creature's middle, leaves no hole and breaks only vanilla glass where it
// stopped. With npcShots (server, on by default; MixinLegacyBullet) it is counted where it met the model (head x1.75,
// limbs x0.5, as an NTM bullet), follows the bullet rules for blocks ("break", "pass"; not "pierce": it has no
// BulletConfig to pierce with) and leaves a hole with the hit's particles and sound where it stops in a block.
// port: the hole goes to the players around as TracerNet.Impact (26.2: ImpactHolePayload), as in the 1.12.2 port.
// port: NTM 1.7.10's other old bullet, EntityBulletBaseNT (the masked man's laser, the UFO, BOT Prime), is counted at
// the head and limbs under the same key (MixinOldBulletBase); its blocks stay NTM's.
public final class LegacyBullets {
    private LegacyBullets() {
    }

    public static boolean on() {
        return ReaimTimes.flag("npcShots", true);
    }

    // the round whose hole it leaves: a plain one (an Impact of a plain round is a hole and the hit's effects,
    // Tracers.impact); the hole's look comes from the block, not the round
    static BulletConfig holeRound() {
        return XFactory762mm.r762_fmj;
    }

    // its damage on `target` hit at `point` (null: NTM's middle, unchanged)
    public static float damage(Entity target, Vec3 point, float amount) {
        if (point == null || !(target instanceof EntityLivingBase)) return amount;
        return HeadDamage.beamDamage(amount, (EntityLivingBase) target, new MovingObjectPosition(target, point));
    }

    // it stopped in this block: its hole on the players' screens around
    public static void stuck(EntityBullet bullet, MovingObjectPosition hit) {
        if (!(bullet.worldObj instanceof WorldServer) || hit == null || hit.hitVec == null || hit.sideHit < 0 || hit.sideHit > 5) return;
        double dx = bullet.motionX, dy = bullet.motionY, dz = bullet.motionZ;
        if (dx * dx + dy * dy + dz * dz < 1e-12) {
            dx = -BlockRay.stepX(hit.sideHit);
            dy = -BlockRay.stepY(hit.sideHit);
            dz = -BlockRay.stepZ(hit.sideHit);
        }
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Vec3 at = hit.hitVec;
        int bx = hit.blockX, by = hit.blockY, bz = hit.blockZ;
        if (TestLog.server) TestLog.s(bullet.getEntityId(), "legacy_hole", "block", TestLog.block(bullet.worldObj, bx, by, bz),
                "pos", new int[] {bx, by, bz}, "at", TestLog.point(at.xCoord, at.yCoord, at.zCoord));
        TracerNet.send(bullet, new TracerNet.Impact(bullet.getEntityId(), holeRound().id, bx, by, bz, hit.sideHit, at.xCoord, at.yCoord,
                at.zCoord, dx / l, dy / l, dz / l));
    }
}
