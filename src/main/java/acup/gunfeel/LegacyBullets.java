package acup.gunfeel;

import org.jspecify.annotations.Nullable;

import com.hbm.entity.projectile.EntityBullet;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.items.weapon.sedna.factory.XFactory762mm;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

// NTM's old bullet, EntityBullet: the hunter chopper's and the cyber crab's; no gun of a player fires it. NTM counts
// its hit at the creature's middle, leaves no hole and breaks only vanilla glass. With npcShots (server, on by
// default; MixinLegacyBullet) it is counted where it met the model (head x1.75, limbs x0.5, as an NTM bullet), follows
// the bullet rules for blocks ("break", "pass"; not "pierce": it has no BulletConfig to pierce with) and leaves a hole
// with the hit's particles and sound where it stops in a block.
public final class LegacyBullets {
    private LegacyBullets() {
    }

    public static boolean on() {
        return ReaimTimes.flag("npcShots", true);
    }

    // the round whose hole it leaves: a plain one (an ImpactHolePayload of a plain round is a hole and the hit's
    // effects, Tracers.impact); the hole's look comes from the block, not the round
    static BulletConfig holeRound() {
        return XFactory762mm.r762_fmj;
    }

    // its damage on `target` hit at `point` (null: NTM's middle, unchanged)
    public static float damage(Entity target, @Nullable Vec3 point, float amount) {
        if (point == null || !(target instanceof LivingEntity living)) return amount;
        return HeadDamage.beamDamage(amount, living, new EntityHitResult(target, point));
    }

    // it stopped in this block: its hole on the players' screens around
    public static void stuck(EntityBullet bullet, BlockHitResult hit) {
        if (!(bullet.level() instanceof ServerLevel level)) return;
        Vec3 dir = bullet.getDeltaMovement();
        if (dir.lengthSqr() < 1e-12) dir = new Vec3(-hit.getDirection().getStepX(), -hit.getDirection().getStepY(), -hit.getDirection().getStepZ());
        Vec3 at = hit.getLocation();
        if (TestLog.server) TestLog.s(bullet.getId(), "legacy_hole", "block", level.getBlockState(hit.getBlockPos()), "pos", hit.getBlockPos(), "at", at);
        PacketDistributor.sendToPlayersNear(level, null, at.x, at.y, at.z, 256,
                new ImpactHolePayload(holeRound().id, hit.getBlockPos(), hit.getDirection(), at, dir.normalize(), bullet.getId()));
    }
}
