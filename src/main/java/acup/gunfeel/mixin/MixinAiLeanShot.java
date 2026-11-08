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
// too): with a block between its eye and that start, the bullet starts at the eye.
@Mixin(value = EntityBulletBaseMK4.class, remap = false)
public abstract class MixinAiLeanShot {
    @Inject(method = "<init>(Lnet/minecraft/world/entity/LivingEntity;Lcom/hbm/items/weapon/sedna/BulletConfig;FFDDD)V", at = @At("TAIL"))
    private void gunfeel$fromLeanedEye(LivingEntity shooter, BulletConfig config, float baseDamage, float gunSpread, double sideOffset,
            double heightOffset, double frontOffset, CallbackInfo ci) {
        if (shooter == null || AiBots.get(shooter) == null) return;
        Entity bullet = (Entity) (Object) this;
        Vec3 at = bullet.position().add(AiLean.eyeShift(shooter)), eye = AiLean.eye(shooter);
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
