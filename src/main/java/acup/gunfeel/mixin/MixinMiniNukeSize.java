package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.explosion.vanillant.ExplosionVNT;

import acup.gunfeel.nuke.MiniNukeCloud;

// Server: a blast's radius where it goes off, for the muke NTM sends there right after (the Fat Mine, the Fat Man's
// shells, the grenades: MiniNukeCloud sizes the cloud by it). Not applied: those clouds take the Fat Mine's size.
@Mixin(value = ExplosionVNT.class, remap = false)
public abstract class MixinMiniNukeSize {
    @Inject(method = "<init>*", at = @At("RETURN"))
    private void gunfeel$size(CallbackInfo ci) {
        ExplosionVNT self = (ExplosionVNT) (Object) this;
        MiniNukeCloud.hint(self.world, self.posX, self.posY, self.posZ, self.size);
    }
}
