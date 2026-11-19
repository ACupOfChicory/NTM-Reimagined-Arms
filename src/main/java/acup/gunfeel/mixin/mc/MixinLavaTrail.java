package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.gas.client.WpSparks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.LavaParticle;
import net.minecraft.core.particles.ParticleOptions;

// The burning phosphorus' sparks (WpSparks) let out no smoke as they fly: vanilla's lava spark, made while WpSparks
// makes one, is marked a spark, and its tick's smoke is not let out. Lava's own sparks and any other mod's keep their
// smoke. Should the tick change (another mod's), nothing here applies and the sparks smoke as lava's.
@Mixin(LavaParticle.class)
public abstract class MixinLavaTrail {
    @Unique
    private final boolean gunfeel$spark = WpSparks.making();

    @WrapOperation(method = "tick", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private void gunfeel$trail(ClientLevel level, ParticleOptions smoke, double x, double y, double z, double xd, double yd, double zd,
            Operation<Void> add) {
        if (!gunfeel$spark) add.call(level, smoke, x, y, z, xd, yd, zd);
    }
}
