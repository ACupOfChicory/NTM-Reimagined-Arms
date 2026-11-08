package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.ai.AiBots;
import acup.gunfeel.ai.AiLean;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

// NTM starts a shooter's bullet at its upright eye, then about a block ahead and a little to its gun hand. An AI mob's
// starts from its eye where it is: leaning round a corner (AiLean), out past the corner, where its gun is drawn. And
// a block ahead of an eye just past a corner is in the wall (stand: 11 shots of 14 leaning, standing at a peek's edge
// too): with a block between its eye and that start, the bullet starts at the eye. Fired blind over its cover (no look
// along the gun: AiGunner.blind), it starts where the gun is drawn, in the raised hand over its head
// (AiPoseGeometry.blindMuzzle), and its heading is NTM's again with three times the gun's spread.
@Mixin(value = EntityBulletBaseMK4.class, remap = false)
public abstract class MixinAiLeanShot {
    /** a blind shot's spread, times the gun's; the gun's taken as this at least (NTM's units: radians, near enough) */
    private static final float BLIND = 3F, BLIND_LEAST = 0.01F;

    @Inject(method = "<init>(Lnet/minecraft/world/entity/LivingEntity;Lcom/hbm/items/weapon/sedna/BulletConfig;FFDDD)V", at = @At("TAIL"))
    private void gunfeel$fromLeanedEye(LivingEntity shooter, BulletConfig config, float baseDamage, float gunSpread, double sideOffset,
            double heightOffset, double frontOffset, CallbackInfo ci) {
        acup.gunfeel.ai.AiBot bot = shooter == null ? null : AiBots.get(shooter);
        if (bot == null) return;
        Entity bullet = (Entity) (Object) this;
        if (bot.gunner.blind) {
            // the heading NTM gave it (the shooter's look), spread anew: three times the gun's, a little at least
            double yaw = Math.toRadians(shooter.getYRot()), pitch = Math.toRadians(shooter.getXRot());
            ((com.hbm.entity.projectile.EntityThrowableNT) (Object) this).setThrowableHeading(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch),
                    Math.cos(yaw) * Math.cos(pitch), 1F, BLIND * Math.max(gunSpread, BLIND_LEAST));
        }
        Vec3 eye = AiLean.eye(shooter);
        Vec3 at = bot.gunner.blind ? shooter.position().add(acup.gunfeel.ai.AiPoseGeometry.blindMuzzle(acup.gunfeel.ai.AiPose.of(shooter).stance(),
                AiLean.frac(shooter), shooter.yBodyRot, bot.mob.isLeftHanded())) : bullet.position().add(AiLean.eyeShift(shooter));
        if (bullet.level().clip(new ClipContext(eye, at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bullet)).getType() != HitResult.Type.MISS)
            at = eye;
        if (at.equals(bullet.position())) return;
        bullet.setPos(at.x, at.y, at.z);
        bullet.xo = at.x;
        bullet.yo = at.y;
        bullet.zo = at.z;
        bullet.xOld = at.x;
        bullet.yOld = at.y;
        bullet.zOld = at.z;
    }
}
