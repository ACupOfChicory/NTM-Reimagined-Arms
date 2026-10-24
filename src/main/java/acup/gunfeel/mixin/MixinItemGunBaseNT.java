package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.config.GunVisualConfig;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.CameraRecoil;
import acup.gunfeel.Config;
import acup.gunfeel.GunFeel;

@Mixin(value = ItemGunBaseNT.class, remap = false)
public abstract class MixinItemGunBaseNT {
    // Take over NTM's 20 Hz camera recoil: hand the new impulse to CameraRecoil, which applies it per frame.
    @Inject(method = "clientRecoilTick", at = @At("HEAD"), cancellable = true)
    private static void gunfeel$recoilTick(CallbackInfo ci) {
        if (!Config.enabled || !GunVisualConfig.visualRecoil) return;
        if (ItemGunBaseNT.recoilVertical != 0F || ItemGunBaseNT.recoilHorizontal != 0F) {
            CameraRecoil.addImpulse(ItemGunBaseNT.recoilVertical, ItemGunBaseNT.recoilHorizontal);
        }
        ItemGunBaseNT.recoilVertical = 0F;
        ItemGunBaseNT.recoilHorizontal = 0F;
        ItemGunBaseNT.offsetVertical = 0F;
        ItemGunBaseNT.offsetHorizontal = 0F;
        ci.cancel();
    }

    // NTM keeps each gun's last shot on the item (one for every stack of it), in the level's game time. In a world
    // whose time is behind the last one's (a new world) that shot lies in the future: the smoke handler then smokes
    // without end and ever thicker (its alpha grows past 1), till the gun fires. A shot later than now is forgotten.
    @Inject(method = "clientHeldTick", at = @At("HEAD"))
    private static void gunfeel$staleShot(CallbackInfo ci) {
        if (!Config.enabled) return;
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null || !(player.getMainHandItem().getItem() instanceof ItemGunBaseNT gun) || gun.lastShot == null) return;
        long now = com.hbm.util.GameTime.millis(player.level());
        // a second's slack: the client's clock may run a little behind the shot's
        for (int i = 0; i < gun.lastShot.length; i++) if (gun.lastShot[i] > now + 1000L) gun.lastShot[i] = 0L;
    }
}
