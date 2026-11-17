package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.entity.mob.EntityCreeperNuclear;

import acup.gunfeel.nuke.MiniNukeCloud;

import net.minecraft.server.level.ServerLevel;

// Server: a charged nuclear creeper sends its muke before its MK5 blast (radius 50) starts: the radius given first, for
// MiniNukeCloud to size the cloud by. Not applied: that cloud takes the Fat Mine's size.
@Mixin(value = EntityCreeperNuclear.class, remap = false)
public abstract class MixinMiniNukeCreeper {
    @Inject(method = "detonate(Lnet/minecraft/server/level/ServerLevel;)V", at = @At("HEAD"))
    private void gunfeel$charged(ServerLevel level, CallbackInfo ci) {
        EntityCreeperNuclear self = (EntityCreeperNuclear) (Object) this;
        if (self.isPowered()) MiniNukeCloud.hint(level, self.getX(), self.getY() + 0.5, self.getZ(), 50F);
    }
}
