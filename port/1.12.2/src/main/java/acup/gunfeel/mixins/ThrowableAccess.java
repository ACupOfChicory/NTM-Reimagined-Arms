package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.hbm.entity.projectile.EntityThrowableNT;

// port: new for 1.12.2. NTM CE keeps a throwable's ticks in the air protected (1.7.10: public).
@Mixin(value = EntityThrowableNT.class, remap = false)
public interface ThrowableAccess {
    @Accessor("ticksInAir")
    int gunfeel$ticksInAir();
}
