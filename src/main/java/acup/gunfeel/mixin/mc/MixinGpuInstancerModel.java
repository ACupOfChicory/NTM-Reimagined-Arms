package acup.gunfeel.mixin.mc;

import acup.gunfeel.GpuInstancerModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="dev.engine_room.flywheel.backend.engine.AbstractInstancer",remap=false)
public abstract class MixinGpuInstancerModel implements GpuInstancerModel {
    @Unique private Object gunfeel$model;
    @Inject(method="<init>",at=@At("RETURN"))
    private void gunfeel$bind(@Coerce Object key,@Coerce Object recreate,CallbackInfo ci) {
        gunfeel$model=((dev.engine_room.flywheel.backend.engine.InstancerKey<?>)key).model();
    }
    public Object gunfeel$model() { return gunfeel$model; }
}
