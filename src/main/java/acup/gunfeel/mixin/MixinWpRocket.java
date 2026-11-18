package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.factory.XFactoryRocket;

import acup.gunfeel.gas.WpSmoke;
import net.minecraft.world.phys.HitResult;

// Server: NTM's white phosphorus rocket lets this mod's white smoke out where it bursts, with NTM's blast and fire
// on the ground (the incendiary rocket, the same code without phosphorus, none).
@Mixin(value = XFactoryRocket.class, remap = false)
public abstract class MixinWpRocket {
    @Inject(method = "spawnFire", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z", shift = At.Shift.AFTER))
    private static void gunfeel$smoke(EntityBulletBaseMK4 bullet, HitResult hit, boolean phosphorus, int duration, CallbackInfo ci) {
        if (phosphorus) WpSmoke.round(bullet.level(), hit, WpSmoke.ROCKET);
    }
}
