package acup.gunfeel.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hbm.items.weapon.sedna.Receiver;

import acup.gunfeel.GunshotRange;

import java.util.function.Supplier;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ItemStack;

// A shot heard as far as its gun's class carries (GunshotRange): the fire volume NTM plays the shot at, for players and
// AI mobs both (the server sends a sound to the players within SoundEvent.getRange(volume), and AiBots.sound hears by
// the same range).
@Mixin(value = Receiver.class, remap = false)
public abstract class MixinGunshotRange {
    @Shadow
    public abstract Supplier<SoundEvent> getFireSound(ItemStack stack);

    @Inject(method = "getFireVolume(Lnet/minecraft/world/item/ItemStack;)F", at = @At("RETURN"), cancellable = true)
    private void gunfeel$range(ItemStack stack, CallbackInfoReturnable<Float> cir) {
        Supplier<SoundEvent> s = getFireSound(stack);
        SoundEvent sound = s == null ? null : s.get();
        if (sound != null) cir.setReturnValue(GunshotRange.volume(sound, cir.getReturnValueF(), GunshotRange.scale()));
    }
}
