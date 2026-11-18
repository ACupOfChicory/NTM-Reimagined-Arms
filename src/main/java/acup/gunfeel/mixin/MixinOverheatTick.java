package acup.gunfeel.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hbm.items.weapon.sedna.ItemGunBaseNT;

import acup.gunfeel.heat.OverheatServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

// The barrel's heat each tick NTM gives the gun (server): water and rain, the hiss, the M2's cook-off (OverheatServer.tick).
// Before NTM's own tick, which goes on as it is
@Mixin(value = ItemGunBaseNT.class, remap = false)
public abstract class MixinOverheatTick {
    @Inject(method = "inventoryTick", at = @At("HEAD"), require = 1)
    private void gunfeel$heatTick(ItemStack stack, ServerLevel level, Entity entity, @Nullable EquipmentSlot slot, CallbackInfo ci) {
        OverheatServer.tick(stack, level, entity, slot);
    }
}
