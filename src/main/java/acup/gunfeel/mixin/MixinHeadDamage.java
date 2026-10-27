package acup.gunfeel.mixin;

import com.hbm.entity.projectile.EntityBulletBaseMK4;
import com.hbm.items.weapon.sedna.BulletConfig;
import com.hbm.util.EntityDamageUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value=BulletConfig.class,remap=false)
public abstract class MixinHeadDamage {
    @Redirect(method="lambda$static$1",at=@At(value="INVOKE",target="Lcom/hbm/util/EntityDamageUtil;attackEntityFromNT(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;FZZDFF)Z"),require=1)
    private static boolean gunfeel$head(LivingEntity target,DamageSource source,float damage,boolean first,boolean second,double knockback,float threshold,float piercing,
            EntityBulletBaseMK4 bullet,HitResult impact) {
        return EntityDamageUtil.attackEntityFromNT(target,source,acup.gunfeel.HeadDamage.damage(bullet,target,impact,damage),first,second,knockback,threshold,piercing);
    }
}
