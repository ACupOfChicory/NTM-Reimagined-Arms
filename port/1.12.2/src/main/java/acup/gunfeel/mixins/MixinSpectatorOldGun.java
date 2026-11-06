package acup.gunfeel.mixins;

import org.spongepowered.asm.mixin.Mixin;

import com.hbm.items.weapon.ItemGunBase;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;

import acup.gunfeel.SpectatorGuns;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.world.World;

// port: new for 1.12.2 (spectatorNoGuns, see SpectatorGuns). NTM CE still has two guns of the old kind (ItemGunBase:
// the super shotgun with its hook and the vortex) that do not go through the gun keys: the client sends the mouse
// buttons in GunButtonPacket and the gun reads them each tick (onUpdate), whatever the game mode. For a spectator
// the buttons do nothing and the gun's tick does not run (no shot, reload or hook pull); the buttons held as the
// player became a spectator are let go.
@Mixin(value = ItemGunBase.class, remap = false)
public abstract class MixinSpectatorOldGun {
    @WrapMethod(method = "startAction")
    private void gunfeel$spectatorStart(ItemStack stack, World world, EntityPlayer player, boolean main, EnumHand hand, Operation<Void> original) {
        if (SpectatorGuns.blocks(player)) return;
        original.call(stack, world, player, main, hand);
    }

    @WrapMethod(method = "endAction")
    private void gunfeel$spectatorEnd(ItemStack stack, World world, EntityPlayer player, boolean main, EnumHand hand, Operation<Void> original) {
        if (SpectatorGuns.blocks(player)) return;
        original.call(stack, world, player, main, hand);
    }

    @WrapMethod(method = "startReloadAction")
    private void gunfeel$spectatorReload(ItemStack stack, World world, EntityPlayer player, EnumHand hand, Operation<Void> original) {
        if (SpectatorGuns.blocks(player)) return;
        original.call(stack, world, player, hand);
    }

    @WrapMethod(method = "onUpdate", remap = true)
    private void gunfeel$spectatorTick(ItemStack stack, World world, Entity entity, int slot, boolean isHeld, Operation<Void> original) {
        if (SpectatorGuns.blocks(entity)) {
            if (ItemGunBase.getIsMouseDown(stack)) ItemGunBase.setIsMouseDown(stack, false);
            if (ItemGunBase.getIsAltDown(stack)) ItemGunBase.setIsAltDown(stack, false);
            return;
        }
        original.call(stack, world, entity, slot, isHeld);
    }
}
