package acup.gunfeel;

import com.hbm.entity.projectile.EntityBullet;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.XFactory762mm;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.WorldServer;

// 26.2's LegacyBullets (X547). NTM's old bullet, EntityBullet: the hunter chopper's and the cyber crab's; no gun of a
// player fires it. NTM counts its hit at the creature's middle, leaves no hole and breaks only weak glass. With npcShots
// (server, on by default; MixinLegacyBullet) it is counted where it met the model (head x1.75, limbs x0.5, as an NTM
// bullet), follows the bullet rules for blocks ("break", "pass"; not "pierce": it has no BulletConfig to pierce with)
// and leaves a hole with the hit's particles and sound where it stops in a block.
// port: the hole goes to the players around as TracerNet.Impact (26.2: ImpactHolePayload).
// port: NTM CE's other old bullet, EntityBulletBase (the masked man, the UFO, BOT Prime), is counted at the head and limbs
// under the same key (MixinOldBulletBase); its blocks stay NTM's.
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
    public static float damage(Entity target, Vec3d point, float amount) {
        if (point == null || !(target instanceof EntityLivingBase)) return amount;
        return HeadDamage.beamDamage(amount, (EntityLivingBase) target, new RayTraceResult(target, point));
    }

    // it stopped in this block: its hole on the players' screens around
    public static void stuck(EntityBullet bullet, RayTraceResult hit) {
        if (!(bullet.world instanceof WorldServer) || hit == null || hit.hitVec == null || hit.sideHit == null) return;
        double dx = bullet.motionX, dy = bullet.motionY, dz = bullet.motionZ;
        if (dx * dx + dy * dy + dz * dz < 1e-12) {
            dx = -hit.sideHit.getXOffset();
            dy = -hit.sideHit.getYOffset();
            dz = -hit.sideHit.getZOffset();
        }
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        Vec3d at = hit.hitVec;
        int bx = hit.getBlockPos().getX(), by = hit.getBlockPos().getY(), bz = hit.getBlockPos().getZ();
        if (TestLog.server) TestLog.s(bullet.getEntityId(), "legacy_hole", "block", TestLog.block(bullet.world, bx, by, bz),
                "pos", new int[] {bx, by, bz}, "at", TestLog.point(at.x, at.y, at.z));
        TracerNet.send(bullet, new TracerNet.Impact(bullet.getEntityId(), holeRound().id, bx, by, bz, hit.sideHit.getIndex(), at.x, at.y, at.z,
                dx / l, dy / l, dz / l));
    }
}
