package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.sound.EntitySounds;

import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

// Server: a gun fired with the shooter's eyes in water sounds muffled (waterMuffle): volume × 0.6, pitch × 0.7. Every
// sound NTM's guns play from their shooter goes through EntitySounds.play (the shot, the full-auto refire, the
// orchestras of reloads and inspections); under water all of them are dull alike.
@Mixin(value = EntitySounds.class, remap = false)
public abstract class MixinWaterMuffle {
    @Unique
    private static final ThreadLocal<boolean[]> gunfeel$inside = ThreadLocal.withInitial(() -> new boolean[1]);

    @Inject(method = "play(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FF)V",
            at = @At("HEAD"), cancellable = true)
    private static void gunfeel$muffle(Entity emitter, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source,
            float volume, float pitch, CallbackInfo ci) {
        boolean[] inside = gunfeel$inside.get();
        if (inside[0] || !(emitter instanceof LivingEntity shooter) || !shooter.isEyeInFluid(FluidTags.WATER)
                || !acup.gunfeel.water.WaterSettings.muffle()) return;
        inside[0] = true;
        try {
            EntitySounds.play(emitter, x, y, z, sound, source, volume * 0.6F, pitch * 0.7F);
        } finally {
            inside[0] = false;
        }
        ci.cancel();
    }
}
