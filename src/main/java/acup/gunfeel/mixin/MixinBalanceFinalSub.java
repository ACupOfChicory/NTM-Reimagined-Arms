package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.BalanceFinal;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

// The final scheme's pair of a submunition (BalanceFinal): a bullet made from another's (the Shredder's plasma from its
// shell) by the constructor of a thrower and a place takes the pair its thrower has just fired.
@Mixin(value = EntityBulletBaseMK4.class, remap = false)
public abstract class MixinBalanceFinalSub {
    @Inject(method = "<init>(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lcom/hbm/items/weapon/sedna/BulletConfig;FFDDDDDD)V", at = @At("RETURN"), require = 1)
    private void gunfeel$sub(Level level, LivingEntity thrower, BulletConfig config, float baseDamage, float spread, double x, double y, double z, double a, double b, double c,
            CallbackInfo ci) {
        BalanceFinal.submunition((EntityBulletBaseMK4) (Object) this, thrower);
    }
}
