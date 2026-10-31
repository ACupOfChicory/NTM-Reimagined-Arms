package acup.gunfeel.mixins;

import java.util.function.BiFunction;
import java.util.function.BiConsumer;
import acup.gunfeel.GunFeelClient;
import acup.gunfeel.Grips;
import acup.gunfeel.MagazineInspect;
import com.hbm.items.weapon.sedna.GunConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.items.weapon.sedna.factory.Orchestras;
import com.hbm.items.weapon.sedna.factory.XFactory556mm;
import com.hbm.render.anim.AnimationEnums.GunAnimation;
import com.hbm.render.anim.BusAnimation;
import com.hbm.render.anim.BusAnimationKeyframe.IType;
import com.hbm.render.anim.BusAnimationSequence;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// port: copied from 26.2 (mixin/MixinG3Inspect.java, 0762086, notes/ports/port-1.7.10-copied.md). The G3's inspect:
// the magazine pulled, tossed and caught at armInspectReleaseMs (MagazineInspect), as the left arm follows it (Arms).
// The gun's id is GunFeelClient.gunId (26.2: the registry key), LambdaContext.configIndex a field (26.2: a record).
// The late mixins are the client's: in single player the game's own server takes it as well, a dedicated server
// keeps NTM's inspect (its length and sounds).
@Mixin(value=GunConfig.class,remap=false)
public abstract class MixinG3Inspect {
    private static float gunfeel$release(ItemStack stack) {
        String id=stack==null?null:GunFeelClient.gunId(stack.getItem()); // port: gunId
        if(id==null)return -1;
        String setting=Grips.overrides(id).get("armInspectReleaseMs");
        try{return Float.parseFloat(setting);}catch(RuntimeException e){return -1;}
    }
    private static final BiFunction<ItemStack,GunAnimation,BusAnimation> INSPECT=(stack,type)->{
        var animation=XFactory556mm.LAMBDA_G3_ANIMS.apply(stack,type);
        if(type!=GunAnimation.INSPECT || animation==null)return animation;
        float value=gunfeel$release(stack);
        if(!Float.isFinite(value) || value<0)return animation;
        int r=MagazineInspect.release(value), pull=(r-MagazineInspect.PREPARE)/2;
        return animation
                .addBus("MAG",new BusAnimationSequence().hold(MagazineInspect.PREPARE)
                        .addPos(0,-1,0,pull)
                        .addPos(2,-1,0,r-MagazineInspect.PREPARE-pull)
                        .addPos(2,8,0,350,IType.SIN_DOWN)
                        .addPos(2,-1,0,350,IType.SIN_UP)
                        .addPos(2,-1,0,50).hold(100)
                        .addPos(0,-1,0,150,IType.SIN_FULL)
                        .addPos(0,0,0,150,IType.SIN_UP).hold(200))
                .addBus("SPEEN",new BusAnimationSequence().hold(r).addPos(0,360,360,700))
                .addBus("LIFT",new BusAnimationSequence().hold(r+1150)
                        .addPos(-2,0,0,100,IType.SIN_DOWN).addPos(0,0,0,100,IType.SIN_FULL));
    };
    private static final BiConsumer<ItemStack,ItemGunBaseNT.LambdaContext> SOUNDS=(stack,ctx)->{
        int index=ctx.configIndex;float value=gunfeel$release(stack); // port: a field (26.2: configIndex())
        if(ItemGunBaseNT.getLastAnim(stack,index)!=GunAnimation.INSPECT || !Float.isFinite(value) || value<0){
            Orchestras.ORCHESTRA_G3.accept(stack,ctx);return;
        }
        int timer=ItemGunBaseNT.getAnimTimer(stack,index), r=MagazineInspect.release(value);
        int original=timer==(MagazineInspect.PREPARE+(r-MagazineInspect.PREPARE)/3+25)/50?2:
                timer==(r+1100+25)/50?28:-1;
        if(original<0)return;
        ItemGunBaseNT.setAnimTimer(stack,index,original);
        try{Orchestras.ORCHESTRA_G3.accept(stack,ctx);}
        finally{ItemGunBaseNT.setAnimTimer(stack,index,timer);}
    };
    @Inject(method="getOrchestra",at=@At("RETURN"),cancellable=true)
    private void gunfeel$inspectSounds(ItemStack stack,CallbackInfoReturnable<BiConsumer<ItemStack,ItemGunBaseNT.LambdaContext>> cir){
        if(cir.getReturnValue()==Orchestras.ORCHESTRA_G3)cir.setReturnValue(SOUNDS);
    }
    @Inject(method="getAnims",at=@At("RETURN"),cancellable=true)
    private void gunfeel$inspect(ItemStack stack,CallbackInfoReturnable<BiFunction<ItemStack,GunAnimation,BusAnimation>> cir){
        if(cir.getReturnValue()==XFactory556mm.LAMBDA_G3_ANIMS)cir.setReturnValue(INSPECT);
    }
    @Inject(method="getInspectDuration",at=@At("RETURN"),cancellable=true)
    private void gunfeel$inspectDuration(ItemStack stack,CallbackInfoReturnable<Integer> cir){
        float value=gunfeel$release(stack);
        if(Float.isFinite(value) && value>=0 && ((GunConfig)(Object)this).getAnims(stack)==INSPECT)
            cir.setReturnValue((MagazineInspect.release(value)+1350+49)/50);
    }
}
