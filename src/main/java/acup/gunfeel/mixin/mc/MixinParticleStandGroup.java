package acup.gunfeel.mixin.mc;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.ParticleStand;
import net.minecraft.client.particle.ParticleGroup;

// The particle stand only (ParticleStand, off in the game): each particle group's tick timed
@Mixin(ParticleGroup.class)
public abstract class MixinParticleStandGroup {
    @Unique
    private long gunfeel$standAt;

    @Inject(method = "tickParticles", at = @At("HEAD"))
    private void gunfeel$standStart(CallbackInfo ci) {
        if (ParticleStand.ENABLED) gunfeel$standAt = System.nanoTime();
    }

    @Inject(method = "tickParticles", at = @At("RETURN"))
    private void gunfeel$standEnd(CallbackInfo ci) {
        if (ParticleStand.ENABLED) ParticleStand.groupTicked(this, System.nanoTime() - gunfeel$standAt);
    }
}
