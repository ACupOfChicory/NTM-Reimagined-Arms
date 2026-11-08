package acup.gunfeel.mixin;

import com.hbm.explosion.vanillant.ExplosionVNT;
import com.hbm.explosion.vanillant.interfaces.IEntityProcessor;
import acup.gunfeel.ExplosiveDamage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=ExplosionVNT.class,remap=false)
public abstract class MixinExplosionContext {
    @Shadow private IEntityProcessor entityProcessor;
    @Inject(method="setEntityProcessor",at=@At("HEAD"),require=1)
    private void gunfeel$bind(IEntityProcessor processor,CallbackInfoReturnable<ExplosionVNT> ci){
        if(processor instanceof ExplosiveDamage.Processor holder)holder.gunfeel$bind((ExplosionVNT)(Object)this);
    }
    @Inject(method="explode",at=@At("HEAD"),require=1)
    private void gunfeel$enter(CallbackInfo ci){
        ExplosiveDamage.enter();
        // the blast blows the gas around it away (its reach where it hurts: twice NTM's size)
        ExplosionVNT self=(ExplosionVNT)(Object)this;
        if(self.world instanceof net.minecraft.server.level.ServerLevel server)
            acup.gunfeel.gas.GasStir.blast(server,new net.minecraft.world.phys.Vec3(self.posX,self.posY,self.posZ),self.size*2F);
    }
    @Inject(method="explode",at=@At("RETURN"),require=1)
    private void gunfeel$finish(CallbackInfo ci){
        ExplosiveDamage.exit();
        if(entityProcessor instanceof ExplosiveDamage.Processor holder)ExplosiveDamage.finish(holder.gunfeel$blast());
    }
}
