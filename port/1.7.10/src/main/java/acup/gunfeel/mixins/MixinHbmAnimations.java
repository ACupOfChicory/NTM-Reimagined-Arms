package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.hbm.render.anim.HbmAnimations;

import acup.gunfeel.Config;
import acup.gunfeel.GunFeelClient;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

@Mixin(value = HbmAnimations.class, remap = false)
public abstract class MixinHbmAnimations {
    // Tone down NTM's own "RECOIL" animation bus in first person; the spring kick replaces most of it.
    @Inject(method = "getRelevantTransformation(Ljava/lang/String;I)[D", at = @At("RETURN"), cancellable = true)
    private static void gunfeel$recoilBus(String bus, int index, CallbackInfoReturnable<double[]> cir) {
        if (!Config.enabled || GunFeelClient.current == null || !"RECOIL".equals(bus)) return;
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        ItemStack held = player == null ? null : player.getHeldItem();
        boolean aiming = held != null && ItemGunBaseNT.getIsAiming(held);
        float strength = Config.ntmRecoilAnim * (aiming ? Config.adsRecoil : 1F);
        if (strength == 1F) return;
        double[] t = cir.getReturnValue();
        if (t == null) return;
        double[] scaled = t.clone();
        for (int i = 0; i < Math.min(3, scaled.length); i++) scaled[i] *= strength;
        cir.setReturnValue(scaled);
    }
}
