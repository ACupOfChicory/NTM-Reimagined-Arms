package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.factory.XFactory40mm;

import acup.gunfeel.gas.WpSmoke;
import net.minecraft.world.phys.HitResult;

// Server: NTM's 40 mm white phosphorus round lets this mod's white smoke out where it bursts, with NTM's blast and fire
// on the ground (the incendiary round, the same code without phosphorus, none).
@Mixin(value = XFactory40mm.class, remap = false)
public abstract class MixinWp40mm {
    @Inject(method = "spawnFire", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z", shift = At.Shift.AFTER))
    private static void gunfeel$smoke(EntityBulletBaseMK4 bullet, HitResult hit, boolean phosphorus, int duration, CallbackInfo ci) {
        if (phosphorus) WpSmoke.round(bullet.level(), hit, WpSmoke.G40);
    }
}
