package acup.gunfeel.mixins.early;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import acup.gunfeel.water.WaterMuffle;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;

// port: the second half of 26.2's MixinWaterMuffle. While a gun's server tick or key handling has marked its shooter
// (MixinWaterMuffle on ItemGunBaseNT) and the shooter's eyes are in water, World's two sound calls are played again
// at volume × 0.6, pitch × 0.7: NTM's shot (playSoundEffect, Lego) and its orchestras (playSoundAtEntity), when they
// sound within 2 blocks of the shooter (a hit far off during the same tick is not the gun's).
@Mixin(World.class)
public abstract class MixinWorldMuffle {
    @Inject(method = "playSoundEffect(DDDLjava/lang/String;FF)V", at = @At("HEAD"), cancellable = true)
    private void gunfeel$muffleEffect(double x, double y, double z, String sound, float volume, float pitch, CallbackInfo ci) {
        if (!WaterMuffle.now(x, y, z)) return;
        World world = (World) (Object) this;
        WaterMuffle.replay((v, p) -> world.playSoundEffect(x, y, z, sound, v, p), volume, pitch);
        ci.cancel();
    }

    @Inject(method = "playSoundAtEntity(Lnet/minecraft/entity/Entity;Ljava/lang/String;FF)V", at = @At("HEAD"), cancellable = true)
    private void gunfeel$muffleAtEntity(Entity entity, String sound, float volume, float pitch, CallbackInfo ci) {
        if (entity == null || !WaterMuffle.now(entity.posX, entity.posY, entity.posZ)) return;
        World world = (World) (Object) this;
        WaterMuffle.replay((v, p) -> world.playSoundAtEntity(entity, sound, v, p), volume, pitch);
        ci.cancel();
    }
}
