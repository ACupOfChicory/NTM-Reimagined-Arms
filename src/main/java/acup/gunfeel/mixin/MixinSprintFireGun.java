package acup.gunfeel.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.SprintFire;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

// Server: the sprint time of the player holding the gun, which key NTM is pressing, and a held-back click fired once
// the time is out (sprintFireDelay, see SprintFire)
@Mixin(value = ItemGunBaseNT.class, remap = false)
public abstract class MixinSprintFireGun {
    @Inject(method = "handleKeybind(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lcom/hbm/handler/HbmKeybinds$EnumKeybind;Z)V",
            at = @At("HEAD"))
    private void gunfeel$pressing(LivingEntity entity, Container inv, ItemStack stack, EnumKeybind key, boolean down, CallbackInfo ci) {
        SprintFire.pressing(down ? key : null);
    }

    @Inject(method = "handleKeybind(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lcom/hbm/handler/HbmKeybinds$EnumKeybind;Z)V",
            at = @At("TAIL"))
    private void gunfeel$pressed(LivingEntity entity, Container inv, ItemStack stack, EnumKeybind key, boolean down, CallbackInfo ci) {
        SprintFire.pressing(null);
    }

    @Inject(method = "inventoryTick", at = @At("HEAD"))
    private void gunfeel$sprintTime(ItemStack stack, ServerLevel level, Entity entity, @Nullable EquipmentSlot slot, CallbackInfo ci) {
        if (slot == EquipmentSlot.MAINHAND && entity instanceof LivingEntity living) SprintFire.tick(stack, living);
    }

    @Inject(method = "inventoryTick", at = @At("TAIL"))
    private void gunfeel$heldClick(ItemStack stack, ServerLevel level, Entity entity, @Nullable EquipmentSlot slot, CallbackInfo ci) {
        if (slot == EquipmentSlot.MAINHAND && entity instanceof LivingEntity living) SprintFire.retry(stack, living);
    }
}
