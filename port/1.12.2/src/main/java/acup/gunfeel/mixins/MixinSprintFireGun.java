package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.SprintFire;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

// port: copied from 26.2 (ef36e84), as on 1.7.10. CE's handleKeybind takes an IInventory, and inventoryTick is
// onUpdate(stack, world, entity, slot, isHeld): the gun in hand is isHeld (as MixinHoldAim).
// Server: the sprint time of the player holding the gun, which key NTM is pressing, and a held-back click fired once
// the time is out (sprintFireDelay, see SprintFire)
@Mixin(value = ItemGunBaseNT.class, remap = false)
public abstract class MixinSprintFireGun {
    @Inject(method = "handleKeybind(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/inventory/IInventory;Lnet/minecraft/item/ItemStack;Lcom/hbm/handler/HbmKeybinds$EnumKeybind;Z)V",
            at = @At("HEAD"))
    private void gunfeel$pressing(EntityLivingBase entity, IInventory inv, ItemStack stack, EnumKeybind key, boolean down, CallbackInfo ci) {
        SprintFire.pressing(down ? key : null);
    }

    @Inject(method = "handleKeybind(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/inventory/IInventory;Lnet/minecraft/item/ItemStack;Lcom/hbm/handler/HbmKeybinds$EnumKeybind;Z)V",
            at = @At("TAIL"))
    private void gunfeel$pressed(EntityLivingBase entity, IInventory inv, ItemStack stack, EnumKeybind key, boolean down, CallbackInfo ci) {
        SprintFire.pressing(null);
    }

    // (Item.onUpdate: its SRG name in a player's game, through the refmap)
    @Inject(method = "onUpdate", at = @At("HEAD"), remap = true)
    private void gunfeel$sprintTime(ItemStack stack, World world, Entity entity, int slot, boolean isHeld, CallbackInfo ci) {
        if (isHeld && entity instanceof EntityLivingBase living) SprintFire.tick(stack, living);
    }

    @Inject(method = "onUpdate", at = @At("TAIL"), remap = true)
    private void gunfeel$heldClick(ItemStack stack, World world, Entity entity, int slot, boolean isHeld, CallbackInfo ci) {
        if (isHeld && entity instanceof EntityLivingBase living) SprintFire.retry(stack, living);
    }
}
