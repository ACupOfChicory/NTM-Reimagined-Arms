package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import acup.gunfeel.water.WaterMuffle;
import net.minecraft.world.World;

// Server, waterMuffle (26.2: MixinWaterMuffle on NTM's EntitySounds.play): a sound NTM's gun plays at its shooter whose
// eyes are in water goes out muffled, volume × 0.6, pitch × 0.7 (WaterMuffle). Every sound played at a place,
// World.playSound(BlockPos, ...) too, comes through this one.
@Mixin(World.class)
public abstract class MixinWaterMuffle {
    @ModifyVariable(method = "playSound(Lnet/minecraft/entity/player/EntityPlayer;DDDLnet/minecraft/util/SoundEvent;Lnet/minecraft/util/SoundCategory;FF)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float gunfeel$volume(float volume, net.minecraft.entity.player.EntityPlayer player, double x, double y, double z,
            net.minecraft.util.SoundEvent sound, net.minecraft.util.SoundCategory category, float volumeArg, float pitchArg) {
        return WaterMuffle.muffled(x, y, z) ? volume * WaterMuffle.VOLUME : volume;
    }

    @ModifyVariable(method = "playSound(Lnet/minecraft/entity/player/EntityPlayer;DDDLnet/minecraft/util/SoundEvent;Lnet/minecraft/util/SoundCategory;FF)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private float gunfeel$pitch(float pitch, net.minecraft.entity.player.EntityPlayer player, double x, double y, double z,
            net.minecraft.util.SoundEvent sound, net.minecraft.util.SoundCategory category, float volumeArg, float pitchArg) {
        return WaterMuffle.muffled(x, y, z) ? pitch * WaterMuffle.PITCH : pitch;
    }
}
