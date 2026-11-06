package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import acup.gunfeel.water.WaterMuffle;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

// waterMuffle (port): who is firing while NTM runs a gun, for MixinWaterMuffle. The gun's sounds come from its tick
// (onUpdate: reloads, inspections, the full-auto refire) and its keys (handleKeybind: the shot). The whole method is
// wrapped with finally: a key packet whose handler throws (the server logs it and goes on) leaves no shooter behind.
@Mixin(value = ItemGunBaseNT.class, remap = false)
public abstract class MixinWaterShooter {
    @WrapMethod(method = "onUpdate", remap = true)
    private void gunfeel$tick(ItemStack stack, World world, Entity entity, int slot, boolean isHeld, Operation<Void> original) {
        WaterMuffle.begin(entity);
        try {
            original.call(stack, world, entity, slot, isHeld);
        } finally {
            WaterMuffle.end();
        }
    }

    @WrapMethod(method = "handleKeybind(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/inventory/IInventory;Lnet/minecraft/item/ItemStack;Lcom/hbm/handler/HbmKeybinds$EnumKeybind;Z)V")
    private void gunfeel$key(EntityLivingBase entity, IInventory inv, ItemStack stack, EnumKeybind key, boolean down, Operation<Void> original) {
        WaterMuffle.begin(entity);
        try {
            original.call(entity, inv, stack, key, down);
        } finally {
            WaterMuffle.end();
        }
    }
}
