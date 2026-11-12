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
    @Unique private ExplosionVNT gunfeel$explosion;
    @Override public void gunfeel$bind(ExplosionVNT explosion){gunfeel$explosion=explosion;gunfeel$blast=ExplosiveDamage.bind(explosion,fixedDamage);}
    @Override public ExplosiveDamage.Blast gunfeel$blast(){return gunfeel$blast;}
    @Redirect(method="attackEntity",at=@At(value="INVOKE",target="Lcom/hbm/util/EntityDamageUtil;attackEntityFromNT(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;FZZDFF)Z"),require=1)
    private boolean gunfeel$damage(LivingEntity target,DamageSource source,float amount,boolean first,boolean second,double knockback,float threshold,float piercing){
        // a rocket's direct hit (before damage() pays its contact share): its power on tier 7 (balanceBlastPowerRocketHigh)
        boolean rocketDirect=ExplosiveDamage.rocketDirect(gunfeel$blast,target);
        // the scheme's ladder (BalanceFinal.blastAt): the round struck the target, known before damage() pays its contact
        boolean struck=ExplosiveDamage.struck(gunfeel$blast,target);
        float total=ExplosiveDamage.damage(gunfeel$blast,target,amount);
        // the blast's parts' multiplier (BlastParts, armed by the hurt around this call) on the blast's share alone, not
        // on an explosive bullet's hit on the target (ExplosiveDamage: its contact share)
        float area=gunfeel$blast==null?total:ExplosiveDamage.area(amount),m=acup.gunfeel.BlastParts.multiplier(target);
        // a weapon's blast hits the combat sets stronger (BalanceArmor.blastPower): a launcher's round or a blast of
        // no bullet (hand grenades); not an explosive bullet's
        // (a blast with no bound bullet inside a bullet's impact is that bullet's: a launcher's round's or not)
        boolean weapon=clazz==com.hbm.util.DamageClass.EXPLOSIVE && (gunfeel$blast!=null ? ExplosiveDamage.launched(gunfeel$blast) : ExplosiveDamage.launchedNow());
        // an explosive bullet's blast (inside a bullet's impact, not a launcher's round), or a gun's blast of another
        // class (the Tesla Cannon's, electric): squeezed and capped at any raw damage
        boolean bullet=clazz!=com.hbm.util.DamageClass.EXPLOSIVE || gunfeel$blast!=null || ExplosiveDamage.current()!=null;
        // (the ladder's blast has no head nor limb: no parts' multiplier on it)
        return acup.gunfeel.BalanceFinal.blastAt(gunfeel$explosion,gunfeel$blast,target,struck,clazz==com.hbm.util.DamageClass.EXPLOSIVE,()->
                acup.gunfeel.BalanceArmor.withBlastPower(weapon,rocketDirect,bullet,total,()->{
            if(m!=1F && total>area && total>0 && !acup.gunfeel.BalanceFinal.blastNow(target))
                return acup.gunfeel.HeadDamage.attack(target,total,(area*m+total-area)/total,
                        d->ExplosiveDamage.attack(target,source,d,first,second,knockback,threshold,piercing));
            return ExplosiveDamage.attack(target,source,total,first,second,knockback,threshold,piercing);
        }));
    }
}
