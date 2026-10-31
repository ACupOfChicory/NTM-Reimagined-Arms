package acup.gunfeel.mixins;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase;

import acup.gunfeel.GunFeelClient;
import acup.gunfeel.Tracers;

// The muzzle of the gun in the player's hands: NTM draws the muzzle smoke there, so the player's own tracers start
// there (26.2: MixinItemRenderWeaponBase.gunfeel$smoke). A class of its own beside MixinItemRenderWeaponBase.
@Mixin(value = ItemRenderWeaponBase.class, remap = false)
public abstract class MixinSmokeNodes {
    @Inject(method = "renderSmokeNodes(Ljava/util/List;D)V", at = @At("HEAD"))
    private static void gunfeel$muzzle(List<?> nodes, double scale, CallbackInfo ci) {
        if (GunFeelClient.current != null) Tracers.muzzle();
    }
}
