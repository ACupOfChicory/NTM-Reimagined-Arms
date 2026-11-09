package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.projectile.EntityBulletBeamBase;
import com.hbm.items.weapon.sedna.BulletConfig;

import acup.gunfeel.BalanceGuns;
import net.minecraft.world.entity.LivingEntity;

// The balance's gun damage (BalanceGuns) for a beam: a beam hits what it hits inside its shot constructor
// (performHitscan), so its damage gets the mult just before (the standard fire, the Tau's charged shot).
@Mixin(value = EntityBulletBeamBase.class, remap = false)
public abstract class MixinBalanceGunBeam {
    @Inject(method = "<init>(Lnet/minecraft/world/entity/LivingEntity;Lcom/hbm/items/weapon/sedna/BulletConfig;FFDDD)V",
            at = @At(value = "INVOKE", target = "Lcom/hbm/entity/projectile/EntityBulletBeamBase;performHitscan()V"), require = 1)
    private void gunfeel$shot(LivingEntity entity, BulletConfig config, float baseDamage, float spread, double side, double height, double front, CallbackInfo ci) {
        EntityBulletBeamBase self = (EntityBulletBeamBase) (Object) this;
        self.damage = BalanceGuns.shot(self, entity, config, self.damage);
    }
}
