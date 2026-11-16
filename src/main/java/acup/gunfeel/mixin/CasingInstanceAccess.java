package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

// where NTM's spent casing lies in the frame, relative to the camera (CasingCull)
@Mixin(targets = "com.hbm.particle.CasingParticleGroup$Instance", remap = false)
public interface CasingInstanceAccess {
    @Accessor("relX")
    double gunfeel$relX();

    @Accessor("relY")
    double gunfeel$relY();

    @Accessor("relZ")
    double gunfeel$relZ();

    @Accessor("heightQuarter")
    float gunfeel$heightQuarter();

    @Accessor("scaleLift")
    float gunfeel$scaleLift();
}
