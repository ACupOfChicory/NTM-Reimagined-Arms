package acup.gunfeel.mixin;

import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.explosion.vanillant.standard.EntityProcessorCrossSmooth;
import acup.gunfeel.ExplosiveDamage;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value=EntityProcessorCrossSmooth.class,remap=false)
public abstract class MixinExplosiveProcessor implements ExplosiveDamage.Processor {
    @Shadow protected float fixedDamage;
    @Shadow protected com.hbm.util.DamageClass clazz;
    @Override public boolean gunfeel$explosive(){return clazz==com.hbm.util.DamageClass.EXPLOSIVE;}
    @Unique private ExplosiveDamage.Blast gunfeel$blast;
    @Override public void gunfeel$bind(ExplosionVNT explosion){gunfeel$blast=ExplosiveDamage.bind(explosion,fixedDamage);}
    @Override public ExplosiveDamage.Blast gunfeel$blast(){return gunfeel$blast;}
    @Redirect(method="attackEntity",at=@At(value="INVOKE",target="Lcom/hbm/util/EntityDamageUtil;attackEntityFromNT(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;FZZDFF)Z"),require=1)
    private boolean gunfeel$damage(LivingEntity target,DamageSource source,float amount,boolean first,boolean second,double knockback,float threshold,float piercing){
        float total=ExplosiveDamage.damage(gunfeel$blast,target,amount);
        // the blast's parts' multiplier (BlastParts, armed by the hurt around this call) on the blast's share alone, not
        // on an explosive bullet's hit on the target (ExplosiveDamage: its contact share)
        float area=gunfeel$blast==null?total:ExplosiveDamage.area(amount),m=acup.gunfeel.BlastParts.multiplier(target);
        if(m!=1F && total>area && total>0)
            return acup.gunfeel.HeadDamage.attack(target,total,(area*m+total-area)/total,
                    d->ExplosiveDamage.attack(target,source,d,first,second,knockback,threshold,piercing));
        return ExplosiveDamage.attack(target,source,total,first,second,knockback,threshold,piercing);
    }
}
