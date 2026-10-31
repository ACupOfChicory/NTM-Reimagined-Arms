package acup.gunfeel.mixins;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.main.ModEventHandlerClient;

import acup.gunfeel.CameraRecoil;
import acup.gunfeel.Config;
import cpw.mods.fml.common.gameevent.TickEvent;

@Mixin(value = ModEventHandlerClient.class, remap = false)
public abstract class MixinModEventHandlerClient {
    // Take over NTM's 20 Hz camera recoil (26.2: ItemGunBaseNT.clientRecoilTick; 1.7.10 has it inline in clientTick,
    // phase END, after the GUN_VISUAL_RECOIL check): hand the new impulse to CameraRecoil, which applies it per frame,
    // and zero NTM's numbers so its own code there turns the camera by nothing.
    @Inject(method = "clientTick", at = @At(value = "FIELD", target = "Lcom/hbm/items/weapon/sedna/ItemGunBaseNT;offsetVertical:F",
            opcode = Opcodes.GETSTATIC, ordinal = 0))
    private void gunfeel$recoilTick(TickEvent.ClientTickEvent event, CallbackInfo ci) {
        if (!Config.enabled) return;
        if (ItemGunBaseNT.recoilVertical != 0F || ItemGunBaseNT.recoilHorizontal != 0F) {
            CameraRecoil.addImpulse(ItemGunBaseNT.recoilVertical, ItemGunBaseNT.recoilHorizontal);
        }
        ItemGunBaseNT.recoilVertical = 0F;
        ItemGunBaseNT.recoilHorizontal = 0F;
        ItemGunBaseNT.offsetVertical = 0F;
        ItemGunBaseNT.offsetHorizontal = 0F;
    }
}
