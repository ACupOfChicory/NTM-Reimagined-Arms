package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.water.WaterMuffle;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

// port: 26.2's MixinWaterMuffle hooks EntitySounds.play, which NTM 1.7.10 lacks; here the gun's server tick and key
// handling mark their shooter for the muffle (WaterMuffle), and the early MixinWorldMuffle muffles World's sounds
// while one is marked.
@Mixin(value = ItemGunBaseNT.class, remap = false)
public abstract class MixinWaterMuffle {
    @Unique
    private static final ThreadLocal<EntityLivingBase[]> gunfeel$before = ThreadLocal.withInitial(() -> new EntityLivingBase[1]);

    // (Item.onUpdate: its SRG name in a player's game, through the refmap)
    @Inject(method = "onUpdate", at = @At("HEAD"), remap = true)
    private void gunfeel$tickBegin(ItemStack stack, World world, Entity entity, int slot, boolean isHeld, CallbackInfo ci) {
        gunfeel$begin(world.isRemote ? null : entity);
    }

    @Inject(method = "onUpdate", at = @At("RETURN"), remap = true)
    private void gunfeel$tickEnd(ItemStack stack, World world, Entity entity, int slot, boolean isHeld, CallbackInfo ci) {
        WaterMuffle.end(gunfeel$before.get()[0]);
    }

    @Inject(method = "handleKeybind(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/inventory/IInventory;Lnet/minecraft/item/ItemStack;Lcom/hbm/handler/HbmKeybinds$EnumKeybind;Z)V",
            at = @At("HEAD"))
    private void gunfeel$keyBegin(EntityLivingBase entity, IInventory inv, ItemStack stack, EnumKeybind key, boolean down, CallbackInfo ci) {
        gunfeel$begin(entity.worldObj.isRemote ? null : entity);
    }

    @Inject(method = "handleKeybind(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/inventory/IInventory;Lnet/minecraft/item/ItemStack;Lcom/hbm/handler/HbmKeybinds$EnumKeybind;Z)V",
            at = @At("RETURN"))
    private void gunfeel$keyEnd(EntityLivingBase entity, IInventory inv, ItemStack stack, EnumKeybind key, boolean down, CallbackInfo ci) {
        WaterMuffle.end(gunfeel$before.get()[0]);
    }

    @Unique
    private static void gunfeel$begin(Entity entity) {
        gunfeel$before.get()[0] = WaterMuffle.begin(entity instanceof EntityLivingBase living ? living : null);
    }
}
