package acup.gunfeel.mixins;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.entity.projectile.EntityBulletBeamBase;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.util.EntityDamageUtil;

import acup.gunfeel.HeadDamage;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MovingObjectPosition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// port: copied from 26.2 (mixin/MixinHeadDamage.java, e284339). The damage of NTM's standard bullet hit
// (LAMBDA_STANDARD_ENTITY_HIT, lambda$static$1) and beam hits (LAMBDA_STANDARD_BEAM_HIT, LAMBDA_BEAM_HIT: $2, $3) by the
// head zone of the model (HeadDamage). The lambdas have the same numbers in NTM X5808 and X5863.
@Mixin(value = BulletConfig.class, remap = false)
public abstract class MixinHeadDamage {
    @Redirect(method = {"lambda$static$2", "lambda$static$3"}, at = @At(value = "INVOKE", target = "Lcom/hbm/util/EntityDamageUtil;attackEntityFromNT"
            + "(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/util/DamageSource;FZZDFF)Z", remap = false), require = 2)
    private static boolean gunfeel$beamZones(EntityLivingBase target, DamageSource source, float damage, boolean first, boolean second, double knockback,
            float threshold, float piercing, EntityBulletBeamBase bullet, MovingObjectPosition impact) {
        return EntityDamageUtil.attackEntityFromNT(target, source, HeadDamage.beamDamage(damage, target, impact), first, second, knockback, threshold, piercing);
    }

    @Redirect(method = "lambda$static$1", at = @At(value = "INVOKE", target = "Lcom/hbm/util/EntityDamageUtil;attackEntityFromNT"
            + "(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/util/DamageSource;FZZDFF)Z", remap = false), require = 1)
    private static boolean gunfeel$head(EntityLivingBase target, DamageSource source, float damage, boolean first, boolean second, double knockback,
            float threshold, float piercing, EntityBulletBaseMK4 bullet, MovingObjectPosition impact) {
        return EntityDamageUtil.attackEntityFromNT(target, source, HeadDamage.damage(bullet, target, impact, damage), first, second, knockback, threshold, piercing);
    }
}
