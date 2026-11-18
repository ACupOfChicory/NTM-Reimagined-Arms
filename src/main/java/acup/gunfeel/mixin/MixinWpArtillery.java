package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import acup.gunfeel.gas.WpSmoke;
import net.minecraft.world.level.Level;

// Server: NTM's white phosphorus artillery shell (PHOSPHORUS; the cluster's shells go off through it) lets this mod's
// white smoke out in place of its mushroom sprite, the burst's flash kept; its blast, shrapnel, fire and phosphorus on
// the living as they were. With smoke off, or the spot shut in, the mushroom as before.
@Mixin(targets = "com.hbm.items.weapon.ItemAmmoArty$ArtilleryShellType$6", remap = false)
public abstract class MixinWpArtillery {
    @WrapOperation(method = "onImpact",
            at = @At(value = "INVOKE", target = "Lcom/hbm/particle/helper/ExplosionCreator;composeEffectRBMKMush(Lnet/minecraft/world/level/Level;DDDF)V"))
    private void gunfeel$smoke(Level level, double x, double y, double z, float scale, Operation<Void> original) {
        if (!WpSmoke.mushroom(level, x, y, z, WpSmoke.ARTILLERY)) original.call(level, x, y, z, scale);
    }
}
