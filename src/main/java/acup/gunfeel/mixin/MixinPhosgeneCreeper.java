package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.hbm.entity.mob.EntityCreeperPhosgene;

import acup.gunfeel.gas.ChemClouds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

// Server: the phosgene creeper's blast lets this mod's phosgene cloud out in place of NTM's mist of it (the blast itself
// as it was). With smoke off, or the spot shut in, the mist goes as before.
@Mixin(value = EntityCreeperPhosgene.class, remap = false)
public abstract class MixinPhosgeneCreeper {
    @Redirect(method = "detonate(Lnet/minecraft/server/level/ServerLevel;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean gunfeel$cloud(ServerLevel level, Entity mist) {
        if (ChemClouds.creeper(level, mist.position())) return true;
        return level.addFreshEntity(mist);
    }
}
