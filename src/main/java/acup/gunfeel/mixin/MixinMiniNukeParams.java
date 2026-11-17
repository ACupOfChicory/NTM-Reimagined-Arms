package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.explosion.ExplosionNukeSmall;

import acup.gunfeel.nuke.MiniNukeCloud;

import net.minecraft.world.level.Level;

// Server: the radius of NTM's small nukes that send their muke before the blast (ExplosionNukeSmall: the artillery
// mini nuke, HIMARS, the Micro missile, the UFO, the waffle), for MiniNukeCloud to size the cloud by. Not applied:
// those clouds take the Fat Mine's size.
@Mixin(value = ExplosionNukeSmall.class, remap = false)
public abstract class MixinMiniNukeParams {
    @Inject(method = "explode(Lnet/minecraft/world/level/Level;DDDLcom/hbm/explosion/ExplosionNukeSmall$MukeParams;)V", at = @At("HEAD"))
    private static void gunfeel$small(Level level, double x, double y, double z, ExplosionNukeSmall.MukeParams params, CallbackInfo ci) {
        if (params != null) MiniNukeCloud.hint(level, x, y + 0.5, z, params.blastRadius);
    }

    // the ground round it laid waste as a nuke's is (MiniWasteMutator: grass, leaves and plants dead or gone, uranium
    // ore to schrabidium; logs, planks, doors and coal left), within its blast radius: one more pass of NTM's rays
    // after the blast, over the crater it left, with no harm and no sound. Server key miniNukeWaste (true)
    @Inject(method = "explode(Lnet/minecraft/world/level/Level;DDDLcom/hbm/explosion/ExplosionNukeSmall$MukeParams;)V", at = @At("TAIL"))
    private static void gunfeel$waste(Level level, double x, double y, double z, ExplosionNukeSmall.MukeParams params, CallbackInfo ci) {
        if (params == null || !params.miniNuke || params.safe || params.blastRadius <= 0F) return;
        if (!(level instanceof net.minecraft.server.level.ServerLevel) || !acup.gunfeel.ReaimTimes.flag("miniNukeWaste", true)) return;
        acup.gunfeel.nuke.MiniWasteMutator.apply(new com.hbm.explosion.vanillant.ExplosionVNT(level, x, y, z, params.blastRadius)).explode();
    }
}
