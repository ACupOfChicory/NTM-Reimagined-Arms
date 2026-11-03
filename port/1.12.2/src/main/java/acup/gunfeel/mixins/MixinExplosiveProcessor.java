package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.explosion.vanillant.standard.EntityProcessorCrossSmooth;
import com.hbm.util.EntityDamageUtil;

import acup.gunfeel.ExplosiveDamage;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.DamageSource;

// port: 26.2's MixinExplosiveProcessor (e284339): the weapons' blast (Lego.standardExplode, tinyExplode) pays its area
// share, and the contact share to the creature the bullet hit
@Mixin(value = EntityProcessorCrossSmooth.class, remap = false)
public abstract class MixinExplosiveProcessor implements ExplosiveDamage.Processor {
    @Shadow
    protected float fixedDamage;
    @Unique
    private ExplosiveDamage.Blast gunfeel$blast;

    @Override
    public void gunfeel$bind(ExplosionVNT explosion) {
        gunfeel$blast = ExplosiveDamage.bind(explosion, fixedDamage);
    }

    @Override
    public ExplosiveDamage.Blast gunfeel$blast() {
        return gunfeel$blast;
    }

    @Redirect(method = "attackEntity", at = @At(value = "INVOKE",
            target = "Lcom/hbm/util/EntityDamageUtil;attackEntityFromNT(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/util/DamageSource;FZZDFF)Z"),
            require = 1)
    private boolean gunfeel$damage(EntityLivingBase target, DamageSource source, float amount, boolean first, boolean second, double knockback,
            float threshold, float piercing) {
        return ExplosiveDamage.attack(target, source, ExplosiveDamage.damage(gunfeel$blast, target, amount), first, second, knockback, threshold,
                piercing);
    }
}
