package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.BalanceGuns;
import net.minecraft.world.entity.LivingEntity;

// The balance's gun damage (BalanceGuns): a gun's bullet, made by its shooter (NTM's shot constructor: the standard
// fire, the accelerators'), gets its gun and ammo's mult. Submunitions are made by the other constructors: not again.
@Mixin(value = EntityBulletBaseMK4.class, remap = false)
public abstract class MixinBalanceGunShot {
    @Inject(method = "<init>(Lnet/minecraft/world/entity/LivingEntity;Lcom/hbm/items/weapon/sedna/BulletConfig;FFDDD)V", at = @At("RETURN"), require = 1)
    private void gunfeel$shot(LivingEntity entity, BulletConfig config, float baseDamage, float gunSpread, double side, double height, double front, CallbackInfo ci) {
        EntityBulletBaseMK4 self = (EntityBulletBaseMK4) (Object) this;
        self.damage = BalanceGuns.shot(self, entity, config, self.damage);
    }
}
