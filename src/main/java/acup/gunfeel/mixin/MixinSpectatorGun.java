package acup.gunfeel.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

import com.hbm.handler.HbmKeybinds.EnumKeybind;
import com.hbm.items.weapon.sedna.ItemGunBaseNT;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import acup.gunfeel.SpectatorGuns;
import acup.gunfeel.TestLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

// Server: a spectator's gun keys do nothing (spectatorNoGuns, see SpectatorGuns). The whole method is wrapped, so the
// mod's own hooks in it (MixinHoldAim, MixinSprintFireGun) do not run either.
@Mixin(value = ItemGunBaseNT.class, remap = false)
public abstract class MixinSpectatorGun {
    @WrapMethod(method = "handleKeybind(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/Container;Lnet/minecraft/world/item/ItemStack;Lcom/hbm/handler/HbmKeybinds$EnumKeybind;Z)V")
    private void gunfeel$spectatorKeys(LivingEntity entity, Container inv, ItemStack stack, EnumKeybind key, boolean down, Operation<Void> original) {
        if (SpectatorGuns.blocks(entity)) {
            if (TestLog.server) TestLog.s(-1, "spectator", "key", key.name(), "down", down);
            return;
        }
        original.call(entity, inv, stack, key, down);
    }

    // A spectator's gun is put away as NTM puts away a gun out of the hand (slot null): no step of the gun runs, so a
    // started reload, an automatic reload on an empty magazine, a burst or a power armor's swing does not go on, and
    // the aim is let go. The keys held as the player became a spectator are let go quietly, without their release
    // actions (a charged shot). Back in a game mode, the gun is drawn again. The mod's own hooks in the method
    // (MixinHoldAim, MixinSprintFireGun) see no gun in the hand either.
    @WrapMethod(method = "inventoryTick")
    private void gunfeel$spectatorHeld(ItemStack stack, ServerLevel level, Entity entity, @Nullable EquipmentSlot slot, Operation<Void> original) {
        if (slot == EquipmentSlot.MAINHAND && SpectatorGuns.blocks(entity)) {
            int configs = ((ItemGunBaseNT) (Object) this).getConfigCount();
            boolean let = false;
            for (int i = 0; i < configs; i++) {
                if (ItemGunBaseNT.getPrimary(stack, i)) { ItemGunBaseNT.setPrimary(stack, i, false); let = true; }
                if (ItemGunBaseNT.getSecondary(stack, i)) { ItemGunBaseNT.setSecondary(stack, i, false); let = true; }
                if (ItemGunBaseNT.getTertiary(stack, i)) { ItemGunBaseNT.setTertiary(stack, i, false); let = true; }
                if (ItemGunBaseNT.getReloadKey(stack, i)) { ItemGunBaseNT.setReloadKey(stack, i, false); let = true; }
            }
            if (let && TestLog.server) TestLog.s(-1, "spectator", "letGo", true);
            slot = null;
        }
        original.call(stack, level, entity, slot);
    }
}
