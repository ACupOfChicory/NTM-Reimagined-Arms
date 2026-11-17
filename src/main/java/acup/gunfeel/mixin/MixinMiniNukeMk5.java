package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.entity.logic.EntityNukeExplosionMK5;

import acup.gunfeel.nuke.MiniNukeCloud;

import net.minecraft.world.level.Level;

// Server: the radius of the MK5 blast the Fat Man's high-yield shell starts before its muke, for MiniNukeCloud to size
// the cloud by. Not applied: that cloud takes the Fat Mine's size.
@Mixin(value = EntityNukeExplosionMK5.class, remap = false)
public abstract class MixinMiniNukeMk5 {
    @Inject(method = "statFac(Lnet/minecraft/world/level/Level;IDDD)Lcom/hbm/entity/logic/EntityNukeExplosionMK5;", at = @At("HEAD"))
    private static void gunfeel$mk5(Level level, int r, double x, double y, double z, CallbackInfoReturnable<EntityNukeExplosionMK5> cir) {
        MiniNukeCloud.hint(level, x, y, z, r);
    }
}
