package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.packet.toclient.HbmAnimationPacket;
import com.hbm.render.anim.AnimationEnums.GunAnimation;

import acup.gunfeel.ArmAnims;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

// The type of each gun animation NTM starts (ArmAnims; 26.2's animations carry it: HbmAnimations.getGunAnimation).
@Mixin(value = HbmAnimationPacket.Handler.class, remap = false)
public abstract class MixinHbmAnimationPacket {
    @Inject(method = "handleSedna", at = @At("RETURN"))
    private static void gunfeel$type(EntityPlayer player, ItemStack stack, int slot, GunAnimation type, int receiverIndex, int gunIndex,
            CallbackInfo ci) {
        ArmAnims.started(slot, gunIndex, type);
    }
}
