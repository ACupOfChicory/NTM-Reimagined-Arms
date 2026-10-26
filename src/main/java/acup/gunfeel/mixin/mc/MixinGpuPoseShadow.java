package acup.gunfeel.mixin.mc;

import acup.gunfeel.GpuPoseAccess;
import acup.gunfeel.ModelHitClient;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Чтение persistently mapped GPU-памяти дорого. Сохраняем готовые CPU-числа
// только у обнаруженных частей мобов; экземпляры машин ничего не копируют.
@Pseudo
@Mixin(targets="dev.engine_room.flywheel.lib.instance.TransformedInstance",remap=false)
public abstract class MixinGpuPoseShadow implements GpuPoseAccess {
    @Unique private volatile boolean gunfeel$watched;
    @Unique private volatile Matrix4f gunfeel$shadow;
    @Unique private volatile long gunfeel$revision;
    public Matrix4fc gunfeel$pose() { gunfeel$watched=true; return gunfeel$shadow; }
    public long gunfeel$revision() { return gunfeel$revision; }
    public void gunfeel$seed(Matrix4fc pose) { gunfeel$shadow=new Matrix4f(pose); gunfeel$revision++; }
    @Inject(method="setTransform",at=@At("HEAD"))
    private void gunfeel$copy(Matrix4fc pose,CallbackInfoReturnable<?> cir) {
        if(!gunfeel$watched) return;
        if(!ModelHitClient.enabled()) { if(gunfeel$shadow!=null) { gunfeel$shadow=null; gunfeel$revision++; } return; }
        Matrix4f copy=gunfeel$shadow;
        if(copy!=null && copy.equals(pose)) return;
        if(copy==null) copy=new Matrix4f();
        copy.set(pose); gunfeel$shadow=copy; gunfeel$revision++;
    }
}
