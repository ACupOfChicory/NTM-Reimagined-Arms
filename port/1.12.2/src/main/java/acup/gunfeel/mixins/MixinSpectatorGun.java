package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import acup.gunfeel.SpectatorGuns;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

// port: 26.2's MixinSpectatorGun (spectatorNoGuns, see SpectatorGuns). A spectator's gun keys do nothing; the whole
// method is wrapped, so the mod's own hooks in it (MixinHoldAim, MixinSprintFireGun) do not run either.
@Mixin(value = ItemGunBaseNT.class, remap = false)
public abstract class MixinSpectatorGun {
    @WrapMethod(method = "handleKeybind(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/inventory/IInventory;Lnet/minecraft/item/ItemStack;Lcom/hbm/handler/HbmKeybinds$EnumKeybind;Z)V")
    private void gunfeel$spectatorKeys(EntityLivingBase entity, IInventory inv, ItemStack stack, EnumKeybind key, boolean down, Operation<Void> original) {
        if (SpectatorGuns.blocks(entity)) return;
        original.call(entity, inv, stack, key, down);
    }

    // A spectator's gun is put away as NTM puts away a gun out of the hand (isHeld false): no step of the gun runs, so
    // a started reload, an automatic reload on an empty magazine, a burst or a power armor's swing does not go on, and
    // the aim is let go. The keys held as the player became a spectator are let go quietly, without their release
    // actions (a charged shot). Back in a game mode, the gun is drawn again.
    @WrapMethod(method = "onUpdate", remap = true)
    private void gunfeel$spectatorHeld(ItemStack stack, World world, Entity entity, int slot, boolean isHeld, Operation<Void> original) {
        if (isHeld && SpectatorGuns.blocks(entity)) {
            int configs = ((ItemGunBaseNT) (Object) this).getConfigCount();
            for (int i = 0; i < configs; i++) {
                if (ItemGunBaseNT.getPrimary(stack, i)) ItemGunBaseNT.setPrimary(stack, i, false);
                if (ItemGunBaseNT.getSecondary(stack, i)) ItemGunBaseNT.setSecondary(stack, i, false);
                if (ItemGunBaseNT.getTertiary(stack, i)) ItemGunBaseNT.setTertiary(stack, i, false);
                if (ItemGunBaseNT.getReloadKey(stack, i)) ItemGunBaseNT.setReloadKey(stack, i, false);
            }
            isHeld = false;
        }
        original.call(stack, world, entity, slot, isHeld);
    }
}
