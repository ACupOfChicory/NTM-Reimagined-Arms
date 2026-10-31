package acup.gunfeel.mixins;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.hbm.entity.projectile.EntityBulletBaseMK4;

import acup.gunfeel.ExplosiveDamage;
import net.minecraft.util.MovingObjectPosition;

// port: 26.2's MixinExplosiveBullet (e284339): the bullet's own onImpact and onUpdate handlers run inside
// ExplosiveDamage's context, so the blast they set off knows its bullet
@Mixin(value = EntityBulletBaseMK4.class, remap = false)
public abstract class MixinExplosiveBullet {
    @Unique
    private boolean gunfeel$impactSent;

    @Redirect(method = "onImpact", at = @At(value = "INVOKE", target = "Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V",
            ordinal = 0), require = 1)
    private void gunfeel$impact(BiConsumer<Object, Object> callback, Object bullet, Object hit) {
        gunfeel$impactSent |= ExplosiveDamage.impact(callback, (EntityBulletBaseMK4) bullet, (MovingObjectPosition) hit, !gunfeel$impactSent);
    }

    @Redirect(method = "onUpdate", at = @At(value = "INVOKE", target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"), require = 1,
            remap = true)
    private void gunfeel$update(Consumer<Object> callback, Object bullet) {
        ExplosiveDamage.update(callback, (EntityBulletBaseMK4) bullet);
    }
}
